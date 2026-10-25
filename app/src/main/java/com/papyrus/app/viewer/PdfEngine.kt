package com.papyrus.app.viewer

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.graphics.pdf.content.PdfPageTextContent
import android.graphics.pdf.models.PageMatchBounds
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.util.Log
import java.io.Closeable

private const val TAG = "PdfEngine"

/** Which platform PDF renderer backs a document. */
internal enum class PdfEngineKind {
    /** Plain [PdfRenderer], render only: every device, and the fallback when a newer API is missing. */
    LEGACY,

    /** [PdfRendererPreV], backported to older releases through SDK extension 13. */
    PRE_V,

    /** [PdfRenderer] with its API 35 text, form and annotation surface. */
    V35,
}

/** What the engine actually in use can do on this device. */
internal data class PdfCapabilities(
    val kind: PdfEngineKind,
    val text: Boolean,
    val search: Boolean,
    val selection: Boolean,
    val forms: Boolean,
    val annotations: Boolean,
) {

    companion object {
        /** Rendering only, for a device whose advertised platform API turned out to be missing. */
        fun legacy(): PdfCapabilities = PdfCapabilities(
            kind = PdfEngineKind.LEGACY,
            text = false,
            search = false,
            selection = false,
            forms = false,
            annotations = false,
        )
    }
}

/**
 * What a device at a given API level and PDF SDK extension can do. Pure, so the whole matrix is
 * tested on the JVM instead of needing one device per release.
 */
internal object PdfEngineAvailability {

    /** The renderer a device with [sdkInt] and PDF SDK extension [extension] can use. */
    fun kindOf(sdkInt: Int, extension: Int): PdfEngineKind = when {
        sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> PdfEngineKind.V35
        sdkInt >= Build.VERSION_CODES.S && extension >= PRE_V_EXTENSION -> PdfEngineKind.PRE_V
        else -> PdfEngineKind.LEGACY
    }

    /** Everything the platform API at [sdkInt] / [extension] offers. */
    fun of(sdkInt: Int, extension: Int): PdfCapabilities {
        val kind = kindOf(sdkInt, extension)
        val platform = kind != PdfEngineKind.LEGACY
        return PdfCapabilities(
            kind = kind,
            text = platform,
            search = platform,
            selection = platform,
            forms = platform,
            // Page annotations shipped in SDK extension 18, and are built in from API 37.
            annotations = extension >= ANNOTATION_EXTENSION || sdkInt >= 37,
        )
    }

    /** 13 is the first SDK extension carrying PdfRendererPreV; 18 the first with page annotations. */
    private const val PRE_V_EXTENSION = 13
    private const val ANNOTATION_EXTENSION = 18
}

/** Reads this device's PDF SDK extension level, which is what decides between the platform engines. */
internal fun devicePdfCapabilities(): PdfCapabilities {
    val extension = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // A module that does not know the level answers with an exception; 0 is the safe reading.
        runCatching { SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) }.getOrDefault(0)
    } else {
        0
    }
    return PdfEngineAvailability.of(Build.VERSION.SDK_INT, extension)
}

/** One document's platform renderer, reduced to what the viewer needs; one page open at a time. */
internal interface PdfEngine : Closeable {
    val kind: PdfEngineKind
    val pageCount: Int

    /** Opens [index]; the caller closes the page before opening another. */
    fun open(index: Int): PdfEnginePage
}

/** An open page. Text, search and selection are empty on an engine that predates those APIs. */
internal interface PdfEnginePage : Closeable {
    val index: Int
    val width: Int
    val height: Int

    /** Renders the whole page into [bitmap], annotation layers included. */
    fun render(bitmap: Bitmap)

    /** The page's text as lines with page-space boxes; empty when the engine has no text API. */
    fun text(): List<PlatformLine>

    /** Every match of [query] in reading order; empty when the engine has no search. */
    fun search(query: String): List<PlatformMatch>

    /** The lines between two boundaries, or null when the engine has no selection API. */
    fun select(start: PlatformBoundary, stop: PlatformBoundary): List<PlatformLine>?
}

/** A selection end: a character index into the page's text, or a point in page pixels. */
internal sealed interface PlatformBoundary {
    data class Index(val value: Int) : PlatformBoundary

    data class Point(val x: Int, val y: Int) : PlatformBoundary
}

/** Opens the best engine the device advertises, or the legacy one when that API is not really there. */
internal object PdfEngineFactory {

    fun create(descriptor: ParcelFileDescriptor, capabilities: PdfCapabilities): PdfEngine {
        if (capabilities.kind != PdfEngineKind.LEGACY) {
            try {
                return when (capabilities.kind) {
                    PdfEngineKind.V35 -> PdfEngineV35(PdfRenderer(descriptor))
                    PdfEngineKind.PRE_V -> PdfEnginePreV(PdfRendererPreV(descriptor))
                    PdfEngineKind.LEGACY -> error("legacy handled below")
                }
            } catch (e: LinkageError) {
                // The capability check found the API level, but not its class or method on this
                // build: rendering still works, so one engine down is better than no document.
                Log.w(TAG, "platform PDF engine missing: ${e.javaClass.simpleName}")
            }
        }
        return PdfEngineLegacy(PdfRenderer(descriptor))
    }
}

internal class PdfEngineV35(private val renderer: PdfRenderer) : PdfEngine {
    override val kind = PdfEngineKind.V35
    override val pageCount: Int get() = renderer.pageCount

    override fun open(index: Int): PdfEnginePage = V35Page(renderer.openPage(index))

    override fun close() = renderer.close()

    private class V35Page(private val page: PdfRenderer.Page) : PdfEnginePage {
        override val index: Int get() = page.index
        override val width: Int get() = page.width
        override val height: Int get() = page.height

        override fun render(bitmap: Bitmap) = page.render(bitmap, null, null, PdfDisplayParams.value)

        override fun text(): List<PlatformLine> = page.textContents.map { it.line() }

        override fun search(query: String): List<PlatformMatch> = page.searchText(query).map { it.match() }

        override fun select(start: PlatformBoundary, stop: PlatformBoundary): List<PlatformLine>? =
            page.selectContent(start.boundary(), stop.boundary())?.lines()

        override fun close() = page.close()
    }
}

internal class PdfEnginePreV(private val renderer: PdfRendererPreV) : PdfEngine {
    override val kind = PdfEngineKind.PRE_V
    override val pageCount: Int get() = renderer.pageCount

    override fun open(index: Int): PdfEnginePage = PreVPage(renderer.openPage(index))

    override fun close() = renderer.close()

    private class PreVPage(private val page: PdfRendererPreV.Page) : PdfEnginePage {
        override val index: Int get() = page.index
        override val width: Int get() = page.width
        override val height: Int get() = page.height

        override fun render(bitmap: Bitmap) = page.render(bitmap, null, null, PdfDisplayParams.value)

        override fun text(): List<PlatformLine> = page.textContents.map { it.line() }

        override fun search(query: String): List<PlatformMatch> = page.searchText(query).map { it.match() }

        override fun select(start: PlatformBoundary, stop: PlatformBoundary): List<PlatformLine>? =
            page.selectContent(start.boundary(), stop.boundary())?.lines()

        override fun close() = page.close()
    }
}

internal class PdfEngineLegacy(private val renderer: PdfRenderer) : PdfEngine {
    override val kind = PdfEngineKind.LEGACY
    override val pageCount: Int get() = renderer.pageCount

    override fun open(index: Int): PdfEnginePage = LegacyPage(renderer.openPage(index))

    override fun close() = renderer.close()

    private class LegacyPage(private val page: PdfRenderer.Page) : PdfEnginePage {
        override val index: Int get() = page.index
        override val width: Int get() = page.width
        override val height: Int get() = page.height

        override fun render(bitmap: Bitmap) {
            @Suppress("DEPRECATION") // The RenderParams overload exists only from API 35, which is V35's job.
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }

        override fun text(): List<PlatformLine> = emptyList()

        override fun search(query: String): List<PlatformMatch> = emptyList()

        override fun select(start: PlatformBoundary, stop: PlatformBoundary): List<PlatformLine>? = null

        override fun close() = page.close()
    }
}

/**
 * Display render with every annotation layer on, so the reader sees the page as a viewer would.
 * Its own class rather than a file-level value: [RenderParams] exists only where the platform PDF
 * extension does, and this file's facade class loads on every device.
 */
private object PdfDisplayParams {
    val value: RenderParams = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY)
        .setRenderFlags(
            RenderParams.FLAG_RENDER_FREETEXT_ANNOTATIONS or
                RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or
                RenderParams.FLAG_RENDER_STAMP_ANNOTATIONS or
                RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS,
        )
        .build()
}

private fun PdfPageTextContent.line(): PlatformLine = PlatformLine(text, bounds.map { it.pageRect() })

private fun RectF.pageRect(): PageRect = PageRect(left, top, right, bottom)

private fun PageMatchBounds.match(): PlatformMatch = PlatformMatch(textStartIndex, bounds.map { it.pageRect() })

private fun PageSelection.lines(): List<PlatformLine> = selectedTextContents.map { it.line() }

private fun PlatformBoundary.boundary(): SelectionBoundary = when (this) {
    is PlatformBoundary.Index -> SelectionBoundary(value)
    is PlatformBoundary.Point -> SelectionBoundary(Point(x, y))
}
