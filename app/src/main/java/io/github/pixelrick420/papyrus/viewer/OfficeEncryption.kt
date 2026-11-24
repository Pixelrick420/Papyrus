package io.github.pixelrick420.papyrus.viewer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DocumentPasswordException : IOException("Password-protected document")

internal fun localSourceFile(uri: String): File? = runCatching {
    val parsed = URI.create(uri)
    if (parsed.scheme != "file") return@runCatching null
    parsed.path?.let(::File)?.takeIf { it.isFile }
}.getOrNull()

object OfficeEncryption {

    enum class Party { NOT_ENCRYPTED, PASSWORD, UNSUPPORTED, ERROR }

    // [newParserFactory] is injected because [Xml.newPullParser] is a platform stub, so a JVM unit
    // test would get null instead of a parser (same seam `OfficeTextExtractor` uses).
    fun status(
        openStream: () -> InputStream,
        newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
        knownLength: Long? = null,
        sourceFile: File? = null,
    ): Party =
        try {
            val head = readHead(openStream)
            when {
                isOle2(head) -> oleStatus(openStream, knownLength)
                isZip(head) -> odfStatus(openStream, newParserFactory, sourceFile)
                else -> Party.NOT_ENCRYPTED
            }
        } catch (expected: IOException) {
            Party.ERROR
        } catch (expected: XmlPullParserException) {
            Party.ERROR
        } catch (expected: GeneralSecurityException) {
            Party.ERROR
        } catch (expected: IllegalArgumentException) {
            Party.ERROR
        }

    fun status(file: File, newParserFactory: () -> XmlPullParser = { Xml.newPullParser() }): Party =
        status({ file.inputStream() }, newParserFactory, file.length(), file)

    fun isEncrypted(
        openStream: () -> InputStream,
        newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
        knownLength: Long? = null,
        sourceFile: File? = null,
    ): Boolean = when (status(openStream, newParserFactory, knownLength, sourceFile)) {
        Party.PASSWORD, Party.UNSUPPORTED -> true
        Party.NOT_ENCRYPTED, Party.ERROR -> false
    }

    fun isEncrypted(file: File, newParserFactory: () -> XmlPullParser = { Xml.newPullParser() }): Boolean =
        when (status(file, newParserFactory)) {
            Party.PASSWORD, Party.UNSUPPORTED -> true
            Party.NOT_ENCRYPTED, Party.ERROR -> false
        }

    fun decrypt(
        openStream: () -> InputStream,
        password: String,
        target: File,
        newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
        ensureActive: () -> Unit = {},
        knownLength: Long? = null,
        sourceFile: File? = null,
    ) {
        var completed = false
        try {
            val head = readHead(openStream)
            when {
                isOle2(head) -> decryptAgile(openStream, password, target, newParserFactory, ensureActive, knownLength)
                isZip(head) -> decryptOdf(openStream, password, target, newParserFactory, ensureActive, sourceFile)
                else -> throw IOException("unsupported encryption")
            }
            completed = true
        } finally {
            if (!completed) target.delete()
        }
    }

    fun decrypt(
        file: File,
        password: String,
        target: File,
        newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
        ensureActive: () -> Unit = {},
    ) = decrypt({ file.inputStream() }, password, target, newParserFactory, ensureActive, file.length(), file)

    private fun oleStatus(openStream: () -> InputStream, knownLength: Long?): Party {
        val cfb = CompoundFile(openStream, knownLength)
        if (!cfb.hasStream(ENCRYPTION_INFO)) return Party.NOT_ENCRYPTED
        val size = cfb.streamSize(ENCRYPTION_INFO) ?: return Party.NOT_ENCRYPTED
        if (size < ENCRYPTION_INFO_HEADER || size > MAX_ENCRYPTION_INFO) return Party.UNSUPPORTED
        val version = cfb.read(ENCRYPTION_INFO, 0, 4)
        return if (u16(version, 0) == AGILE_MAJOR && u16(version, 2) == AGILE_MINOR) Party.PASSWORD else Party.UNSUPPORTED
    }

    private fun decryptAgile(
        openStream: () -> InputStream,
        password: String,
        target: File,
        newParserFactory: () -> XmlPullParser,
        ensureActive: () -> Unit,
        knownLength: Long?,
    ) {
        val cfb = CompoundFile(openStream, knownLength)
        val size = cfb.streamSize(ENCRYPTION_INFO) ?: throw IOException("EncryptionInfo not found")
        if (size < ENCRYPTION_INFO_HEADER || size > MAX_ENCRYPTION_INFO) throw IOException("unsupported EncryptionInfo")
        val info = cfb.read(ENCRYPTION_INFO, 0, size.toInt())
        val version = u16(info, 0) to u16(info, 2)
        if (version != AGILE_MAJOR to AGILE_MINOR) throw IOException("unsupported EncryptionInfo version")
        val agile = parseAgile(info.copyOfRange(ENCRYPTION_INFO_HEADER, info.size), newParserFactory)

        val h = deriveIteratedHash(password, agile.passwordSalt, agile.hashName, agile.spinCount, ensureActive)
        if (wrongPassword(agile, h)) throw DocumentPasswordException()
        val secretKey = aesCbcDecrypt(
            agile.encryptedKeyValue,
            deriveKey(h, BLOCK_KEY_ENCRYPTED_VALUE, agile.hashName, agile.keyBits),
            agile.passwordSalt,
        )
        if (secretKey.size != agile.keyBits / 8) throw IOException("invalid key size")

        val packageSize = cfb.streamSize(ENCRYPTED_PACKAGE) ?: throw IOException("EncryptedPackage not found")
        cfb.openReader(ENCRYPTED_PACKAGE).use { reader ->
            decryptPackage(reader, packageSize, secretKey, agile.keyDataSalt, agile.keyDataHashName ?: agile.hashName, target, ensureActive)
        }
    }

    private fun wrongPassword(agile: AgileInfo, h: ByteArray): Boolean {
        val actual = MessageDigest.getInstance(jcaHash(agile.hashName)).digest(
            aesCbcDecrypt(
                agile.encryptedVerifierHashInput,
                deriveKey(h, BLOCK_KEY_VERIFIER_HASH_INPUT, agile.hashName, agile.keyBits),
                agile.passwordSalt,
            ),
        )
        val expected = aesCbcDecrypt(
            agile.encryptedVerifierHashValue,
            deriveKey(h, BLOCK_KEY_VERIFIER_HASH_VALUE, agile.hashName, agile.keyBits),
            agile.passwordSalt,
        )
        return expected.size < actual.size || !expected.copyOf(actual.size).contentEquals(actual)
    }

    private fun decryptPackage(
        reader: CompoundFile.Reader,
        packageSize: Long,
        key: ByteArray,
        keyDataSalt: ByteArray,
        hashName: String,
        target: File,
        ensureActive: () -> Unit,
    ) {
        val header = reader.read(0, PACKAGE_SIZE_HEADER)
        val totalSize = u32(header, 0) or (u32(header, 4) shl 32)
        if (totalSize < 0 || totalSize > packageSize) throw IOException("invalid package size")
        val md = MessageDigest.getInstance(jcaHash(hashName))
        target.outputStream().buffered().use { out ->
            var offset = PACKAGE_SIZE_HEADER.toLong()
            var remaining = totalSize
            var segment = 0
            while (remaining > 0) {
                ensureActive()
                val available = packageSize - offset
                if (available <= 0) throw IOException("truncated encrypted package")
                val chunkSize = minOf(SEGMENT_LENGTH.toLong(), available).toInt()
                val cipherText = reader.read(offset, chunkSize)
                md.reset()
                val iv = md.digest(keyDataSalt + le32(segment)).copyOf(IV_LENGTH)
                val plain = aesCbcDecrypt(cipherText, key, iv)
                val take = minOf(remaining, plain.size.toLong()).toInt()
                out.write(plain, 0, take)
                remaining -= take
                offset += chunkSize
                segment++
            }
        }
    }

    private class AgileInfo {
        var hashName = "SHA512"
        var keyDataHashName: String? = null
        var keyBits = 256
        var spinCount = 0
        var keyDataSalt = ByteArray(0)
        var passwordSalt = ByteArray(0)
        var encryptedVerifierHashInput = ByteArray(0)
        var encryptedVerifierHashValue = ByteArray(0)
        var encryptedKeyValue = ByteArray(0)
    }

    private fun parseAgile(xml: ByteArray, newParserFactory: () -> XmlPullParser): AgileInfo {
        val parser = newParserFactory()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml), null)
        val info = AgileInfo()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "keyData" -> {
                        info.keyDataHashName = attr(parser, "hashAlgorithm") ?: info.keyDataHashName
                        info.keyDataSalt = decodeBase64(attr(parser, "saltValue"))
                    }
                    "encryptedKey" -> {
                        info.spinCount = attr(parser, "spinCount")?.toIntOrNull() ?: 0
                        info.hashName = attr(parser, "hashAlgorithm") ?: info.hashName
                        info.keyBits = attr(parser, "keyBits")?.toIntOrNull() ?: info.keyBits
                        info.passwordSalt = decodeBase64(attr(parser, "saltValue"))
                        info.encryptedVerifierHashInput = decodeBase64(attr(parser, "encryptedVerifierHashInput"))
                        info.encryptedVerifierHashValue = decodeBase64(attr(parser, "encryptedVerifierHashValue"))
                        info.encryptedKeyValue = decodeBase64(attr(parser, "encryptedKeyValue"))
                    }
                    else -> Unit
                }
                XmlPullParser.END_DOCUMENT -> return info
            }
        }
    }

    private fun odfStatus(
        openStream: () -> InputStream,
        newParserFactory: () -> XmlPullParser,
        sourceFile: File?,
    ): Party {
        val entries = parseOdfManifest(openStream, newParserFactory, sourceFile)
        val encrypted = entries.filter { it.encryption != null }
        if (encrypted.isEmpty()) return Party.NOT_ENCRYPTED
        return if (encrypted.all { entry -> entry.encryption?.let(::supportedOdf) == true }) {
            Party.PASSWORD
        } else {
            Party.UNSUPPORTED
        }
    }

    private fun decryptOdf(
        openStream: () -> InputStream,
        password: String,
        target: File,
        newParserFactory: () -> XmlPullParser,
        ensureActive: () -> Unit,
        sourceFile: File?,
    ) {
        val entries = parseOdfManifest(openStream, newParserFactory, sourceFile).associateBy { it.path }
        val deriver = OdfKeyDeriver(password, ensureActive)
        openStream().use { input ->
            BoundedZipInputStream(input).use { zip ->
                ZipOutputStream(target.outputStream().buffered()).use { out ->
                    val pending = ArrayList<OdfPlainEntry>()
                    var mimetypeWritten = false
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val data = readBounded(zip)
                        val encryption = entries[entry.name]?.encryption
                        if (entry.name == ODF_MIMETYPE) {
                            if (mimetypeWritten) continue
                            val sink = ByteArrayOutputStream()
                            if (encryption == null) sink.write(data) else decryptOdfEntry(data, encryption, deriver, ensureActive, sink)
                            writeMimetype(out, sink.toByteArray())
                            mimetypeWritten = true
                            pending.forEach { writeOdfEntry(out, it) }
                            pending.clear()
                        } else if (!mimetypeWritten) {
                            val sink = ByteArrayOutputStream()
                            if (encryption == null) sink.write(data) else decryptOdfEntry(data, encryption, deriver, ensureActive, sink)
                            pending += OdfPlainEntry(entry.name, sink.toByteArray())
                        } else {
                            out.putNextEntry(ZipEntry(entry.name))
                            if (encryption == null) out.write(data) else decryptOdfEntry(data, encryption, deriver, ensureActive, out)
                            out.closeEntry()
                        }
                    }
                    if (!mimetypeWritten) pending.forEach { writeOdfEntry(out, it) }
                }
            }
        }
    }

    private class OdfPlainEntry(val name: String, val data: ByteArray)

    private fun writeMimetype(out: ZipOutputStream, data: ByteArray) {
        val checksum = CRC32().also { it.update(data) }
        val entry = ZipEntry(ODF_MIMETYPE).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = checksum.value
        }
        out.putNextEntry(entry)
        out.write(data)
        out.closeEntry()
    }

    private fun writeOdfEntry(out: ZipOutputStream, entry: OdfPlainEntry) {
        out.putNextEntry(ZipEntry(entry.name))
        out.write(entry.data)
        out.closeEntry()
    }

    private class OdfKeyDeriver(private val password: String, private val ensureActive: () -> Unit) {
        private var salt: ByteArray? = null
        private var iterations = 0
        private var keySize = 0
        private var startKeyName: String? = null
        private var key: ByteArray? = null

        fun keyFor(encryption: OdfEncryption): ByteArray {
            val cached = key
            if (cached != null && encryption.salt.contentEquals(salt) &&
                encryption.iterationCount == iterations && encryption.keySize == keySize &&
                encryption.startKeyName == startKeyName
            ) return cached
            val derived = pbkdf2Sha1(
                startKey(password, encryption.startKeyName),
                encryption.salt,
                encryption.iterationCount,
                encryption.keySize,
                ensureActive,
            )
            salt = encryption.salt.copyOf()
            iterations = encryption.iterationCount
            keySize = encryption.keySize
            startKeyName = encryption.startKeyName
            key = derived
            return derived
        }
    }

    private fun decryptOdfEntry(
        data: ByteArray,
        encryption: OdfEncryption,
        deriver: OdfKeyDeriver,
        ensureActive: () -> Unit,
        out: OutputStream,
    ) {
        ensureActive()
        // The manifest sets the work the derivation does, so it is checked here too, not only for the prompt.
        if (!supportedOdf(encryption)) throw IOException("unsupported encryption")
        if (data.size % AES_BLOCK != 0) throw IOException("invalid encrypted entry")
        val decrypted = aesCbcDecrypt(data, deriver.keyFor(encryption), encryption.iv)
        val compressed = unpad(decrypted)
        verifyOdfChecksum(compressed, encryption)
        inflate(compressed, out)
    }

    private fun startKey(password: String, name: String?): ByteArray {
        val bytes = password.toByteArray(Charsets.UTF_8)
        return when {
            name == null || name.contains("sha1", ignoreCase = true) -> sha1(bytes)
            name.contains("sha256", ignoreCase = true) -> sha256(bytes)
            else -> throw IOException("unsupported start key generation")
        }
    }

    private fun verifyOdfChecksum(compressed: ByteArray, encryption: OdfEncryption) {
        val expected = encryption.checksum
        if (expected == null) return
        val digest = when {
            encryption.checksumType == null -> return
            encryption.checksumType.contains("sha256", ignoreCase = true) -> sha256(compressed.copyOf(minOf(CHECKSUM_BYTES, compressed.size)))
            encryption.checksumType.contains("sha1", ignoreCase = true) -> sha1(compressed.copyOf(minOf(CHECKSUM_BYTES, compressed.size)))
            else -> return
        }
        if (!digest.contentEquals(expected)) throw DocumentPasswordException()
    }

    private fun supportedOdf(enc: OdfEncryption): Boolean =
        enc.algorithmName?.substringAfterLast('#')?.equals(ALGORITHM_AES256_CBC, ignoreCase = true) == true &&
            enc.keyDerivationName?.contains("pbkdf2", ignoreCase = true) == true &&
            enc.iv.size == IV_LENGTH &&
            enc.salt.isNotEmpty() &&
            enc.keySize == ODF_KEY_BYTES &&
            enc.iterationCount in 1..MAX_PBKDF2_ITERATIONS

    private class OdfEntry(val path: String, val encryption: OdfEncryption?)

    private class OdfEncryption(
        val algorithmName: String?,
        val iv: ByteArray,
        val keyDerivationName: String?,
        val salt: ByteArray,
        val iterationCount: Int,
        val startKeyName: String?,
        val keySize: Int,
        val checksumType: String?,
        val checksum: ByteArray?,
    )

    private class OdfEntryBuilder(val path: String) {
        var algorithmName: String? = null
        var iv: ByteArray = ByteArray(0)
        var keyDerivationName: String? = null
        var salt: ByteArray = ByteArray(0)
        var iterationCount = 0
        var startKeyName: String? = null
        var keySize = 32
        var checksumType: String? = null
        var checksum: ByteArray? = null

        fun encryption(): OdfEncryption? =
            if (algorithmName == null) null else OdfEncryption(
                algorithmName, iv, keyDerivationName, salt, iterationCount, startKeyName, keySize, checksumType, checksum,
            )
    }

    private fun parseOdfManifest(
        openStream: () -> InputStream,
        newParserFactory: () -> XmlPullParser,
        sourceFile: File?,
    ): List<OdfEntry> {
        val manifest = readZipEntry(sourceFile, openStream, ODF_MANIFEST, MAX_MANIFEST_BYTES) ?: return emptyList()
        val parser = newParserFactory()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(manifest), null)
        val out = ArrayList<OdfEntry>()
        var current: OdfEntryBuilder? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "file-entry" -> current = OdfEntryBuilder(attr(parser, "full-path") ?: "")
                    "encryption-data" -> current?.apply {
                        checksumType = attr(parser, "checksum-type")
                        checksum = decodeBase64Nullable(attr(parser, "checksum"))
                    }
                    "algorithm" -> current?.apply {
                        algorithmName = attr(parser, "algorithm-name")
                        iv = decodeBase64(attr(parser, "initialisation-vector"))
                    }
                    "key-derivation" -> current?.apply {
                        keyDerivationName = attr(parser, "key-derivation-name")
                        salt = decodeBase64(attr(parser, "salt"))
                        iterationCount = attr(parser, "iteration-count")?.toIntOrNull() ?: 0
                        keySize = attr(parser, "key-size")?.toIntOrNull() ?: keySize
                    }
                    "start-key-generation" -> current?.apply {
                        startKeyName = attr(parser, "start-key-generation-name")
                    }
                    else -> Unit
                }
                XmlPullParser.END_TAG -> if (local(parser.name) == "file-entry") {
                    current?.let { out += OdfEntry(it.path, it.encryption()) }
                    current = null
                }
                XmlPullParser.END_DOCUMENT -> return out
            }
        }
    }

    internal fun deriveIteratedHash(
        password: String,
        salt: ByteArray,
        hashName: String,
        spinCount: Int,
        ensureActive: () -> Unit = {},
    ): ByteArray {
        if (spinCount < 0 || spinCount > MAX_SPIN_COUNT) throw IOException("unsupported spin count")
        val md = MessageDigest.getInstance(jcaHash(hashName))
        md.update(salt)
        md.update(password.toByteArray(Charsets.UTF_16LE))
        var h = md.digest()
        for (i in 0 until spinCount) {
            if (i % KDF_CHECK_INTERVAL == 0) ensureActive()
            md.reset()
            md.update(le32(i))
            md.update(h)
            h = md.digest()
        }
        return h
    }

    internal fun deriveKey(h: ByteArray, blockKey: ByteArray, hashName: String, keyBits: Int): ByteArray {
        if (keyBits <= 0 || keyBits % 8 != 0) throw IOException("invalid key size")
        val md = MessageDigest.getInstance(jcaHash(hashName))
        md.update(h)
        md.update(blockKey)
        val digest = md.digest()
        val length = keyBits / 8
        return if (length <= digest.size) {
            digest.copyOf(length)
        } else {
            digest + ByteArray(length - digest.size) { 0x36 }
        }
    }

    internal fun pbkdf2Sha1(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int,
        ensureActive: () -> Unit = {},
    ): ByteArray {
        if (iterations <= 0 || dkLen <= 0) throw IOException("invalid key derivation parameters")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(password, "HmacSHA1"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        for (block in 1..blocks) {
            mac.reset()
            mac.update(salt)
            mac.update(be32(block))
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hLen)
            for (round in 1 until iterations) {
                if (round % KDF_CHECK_INTERVAL == 0) ensureActive()
                mac.reset()
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (block - 1) * hLen, hLen)
        }
        return out.copyOf(dkLen)
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        if (data.size % AES_BLOCK != 0) throw IOException("invalid ciphertext length")
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** ODF compresses with raw RFC 1951 deflate; a stray zlib wrapper from another producer is retried. */
    private fun inflate(data: ByteArray, out: OutputStream) {
        val nowrap = try {
            inflateStream(data, nowrap = true, DISCARD)
            true
        } catch (_: DataFormatException) {
            try {
                inflateStream(data, nowrap = false, DISCARD)
                false
            } catch (expected: DataFormatException) {
                throw IOException("invalid deflate stream")
            }
        }
        inflateStream(data, nowrap, out)
    }

    private fun inflateStream(data: ByteArray, nowrap: Boolean, out: OutputStream) {
        val inflater = Inflater(nowrap)
        try {
            inflater.setInput(data)
            val buffer = ByteArray(INFLATE_BUFFER)
            var total = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw DataFormatException("truncated deflate stream")
                total += n
                if (total > MAX_DECOMPRESSED) throw IOException("entry is too large")
                out.write(buffer, 0, n)
            }
        } finally {
            inflater.end()
        }
    }

    private val DISCARD = object : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data[data.size - 1].toInt() and 0xFF
        return if (pad in 1..AES_BLOCK && pad <= data.size) data.copyOf(data.size - pad) else data
    }

    private fun jcaHash(name: String): String = when (name.uppercase()) {
        "SHA1" -> "SHA-1"
        "SHA256" -> "SHA-256"
        "SHA384" -> "SHA-384"
        "SHA512" -> "SHA-512"
        else -> throw IOException("unsupported hash algorithm")
    }

    private fun readHead(openStream: () -> InputStream): ByteArray {
        openStream().use { input ->
            val head = ByteArray(OLE_SIGNATURE.size)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            return if (read == head.size) head else head.copyOf(read)
        }
    }

    private fun readZipEntry(
        sourceFile: File?,
        openStream: () -> InputStream,
        name: String,
        max: Int,
    ): ByteArray? {
        if (sourceFile != null) {
            ZipFile(sourceFile).use { zip ->
                val entries = zip.entries()
                if (entries.hasMoreElements() && entries.nextElement().name == OOXML_CONTENT_TYPES) return null
                val entry = zip.getEntry(name) ?: return null
                if (entry.size > max) throw IOException("entry is too large")
                val compressed = entry.compressedSize.coerceAtLeast(1L)
                val data = CountingInputStream(zip.getInputStream(entry)).use { readBounded(it, max) }
                val limits = ZipLimits()
                if (data.size >= limits.ratioFloorBytes && data.size / compressed > limits.maxRatio) {
                    throw IOException("implausible compression")
                }
                return data
            }
        }
        openStream().use { input ->
            BoundedZipInputStream(input).use { zip ->
                val first = zip.nextEntry ?: return null
                if (first.name == OOXML_CONTENT_TYPES) return null
                if (first.name == name) return readBounded(zip, max)
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    if (entry.name == name) return readBounded(zip, max)
                }
            }
        }
    }

    private fun readBounded(stream: InputStream, max: Int = MAX_ENTRY_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > max) throw IOException("entry is too large")
            out.write(buffer, 0, n)
        }
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).substringAfterLast(':') == name) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""

    private fun decodeBase64(value: String?): ByteArray = decodeBase64Nullable(value) ?: ByteArray(0)

    private fun decodeBase64Nullable(value: String?): ByteArray? = value?.let { Base64.getDecoder().decode(it) }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    /** PBKDF2's block index is big-endian per RFC 2898, unlike MS-OFFCRYPTO's little-endian rounds. */
    private fun be32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}

private val OLE_SIGNATURE = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)

private fun isOle2(head: ByteArray): Boolean =
    head.size >= OLE_SIGNATURE.size && OLE_SIGNATURE.indices.all { (head[it].toInt() and 0xFF) == OLE_SIGNATURE[it] }

private fun isZip(head: ByteArray): Boolean =
    head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && head[2] == 0x03.toByte() && head[3] == 0x04.toByte()

private const val ENCRYPTION_INFO = "EncryptionInfo"
private const val ENCRYPTED_PACKAGE = "EncryptedPackage"
private const val AGILE_MAJOR = 4
private const val AGILE_MINOR = 4
private const val ENCRYPTION_INFO_HEADER = 8
private const val MAX_ENCRYPTION_INFO = 1024 * 1024

private const val SEGMENT_LENGTH = 4096
private const val PACKAGE_SIZE_HEADER = 8
private const val IV_LENGTH = 16
private const val AES_BLOCK = 16

private val BLOCK_KEY_VERIFIER_HASH_INPUT = byteArrayOf(0xFE.toByte(), 0xA7.toByte(), 0xD2.toByte(), 0x76, 0x3B, 0x4B, 0x9E.toByte(), 0x79)
private val BLOCK_KEY_VERIFIER_HASH_VALUE = byteArrayOf(0xD7.toByte(), 0xAA.toByte(), 0x0F, 0x6D, 0x30, 0x61, 0x34, 0x4E)
private val BLOCK_KEY_ENCRYPTED_VALUE = byteArrayOf(0x14, 0x6E, 0x0B, 0xE7.toByte(), 0xAB.toByte(), 0xAC.toByte(), 0xD0.toByte(), 0xD6.toByte())

private const val ODF_MANIFEST = "META-INF/manifest.xml"
private const val ODF_MIMETYPE = "mimetype"
private const val OOXML_CONTENT_TYPES = "[Content_Types].xml"
private const val ALGORITHM_AES256_CBC = "aes256-cbc"
private const val CHECKSUM_BYTES = 1024

private const val MAX_SPIN_COUNT = 5_000_000
private const val KDF_CHECK_INTERVAL = 4096

/** An order of magnitude over what producers write (LibreOffice: 100,000); one derivation runs per entry. */
private const val MAX_PBKDF2_ITERATIONS = 1_000_000

/** AES-256, the only cipher accepted; a longer derived key only multiplies the work before the cipher rejects it. */
private const val ODF_KEY_BYTES = 32
private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
private const val MAX_DECOMPRESSED = 64 * 1024 * 1024
private const val READ_BUFFER = 64 * 1024
private const val INFLATE_BUFFER = 64 * 1024
