package com.papyrus.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papyrus.app.MainApplication
import com.papyrus.app.R
import com.papyrus.app.scanner.DocumentProcessor
import com.papyrus.app.scanner.PdfExporter
import com.papyrus.app.scanner.ScanFilter
import com.papyrus.app.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class ScannerViewModel(private val app: MainApplication) : ViewModel() {

    data class ScanPage(val id: Long, val file: File)

    data class UiState(
        val pages: List<ScanPage> = emptyList(),
        val filter: ScanFilter = ScanFilter.ORIGINAL,
        val isProcessing: Boolean = false,
        val isExporting: Boolean = false,
        val message: UiText? = null,
    )

    private val sessionDir = File(app.cacheDir, "scan-session").apply { mkdirs() }
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var nextId = 0L

    /** Called from the CameraX executor thread with the (unrotated) captured frame. */
    fun onCaptured(raw: Bitmap, rotationDegrees: Int) {
        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true) }
            try {
                val file = withContext(Dispatchers.Default) { processToFile(raw, rotationDegrees) }
                _state.update { it.copy(pages = it.pages + ScanPage(nextId++, file), isProcessing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isProcessing = false, message = UiText(R.string.scanner_error_capture, e.readable())) }
            }
        }
    }

    private fun processToFile(raw: Bitmap, rotationDegrees: Int): File {
        val upright = if (rotationDegrees == 0) raw else Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
        ).also { raw.recycle() }
        val scanned = DocumentProcessor.detectAndWarp(upright)
        val file = File(sessionDir, "page-${System.nanoTime()}.jpg")
        try {
            file.outputStream().buffered().use { scanned.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } finally {
            if (scanned !== upright) scanned.recycle()
            upright.recycle()
        }
        return file
    }

    fun setFilter(filter: ScanFilter) = _state.update { it.copy(filter = filter) }

    fun removePage(id: Long) {
        _state.value.pages.firstOrNull { it.id == id }?.file?.delete()
        _state.update { s -> s.copy(pages = s.pages.filterNot { it.id == id }) }
    }

    fun reportCaptureError(detail: String?) =
        _state.update { it.copy(message = UiText(R.string.scanner_error_capture, detail ?: "?")) }

    fun reportCameraError(detail: String?) =
        _state.update { it.copy(message = UiText(R.string.scanner_error_camera, detail ?: "?")) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun export(target: Uri, onExported: (Long) -> Unit) {
        val snapshot = _state.value
        if (snapshot.pages.isEmpty() || snapshot.isExporting) return
        viewModelScope.launch {
            _state.update { it.copy(isExporting = true) }
            try {
                PdfExporter.export(app, snapshot.pages.map { it.file }, snapshot.filter, target)
                val id = app.repository.registerScan(target, snapshot.pages.size, snapshot.filter)
                clearSession()
                onExported(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, target) } }
                _state.update { it.copy(isExporting = false, message = UiText(R.string.scanner_error_export, e.readable())) }
            }
        }
    }

    private fun clearSession() {
        sessionDir.listFiles()?.forEach { it.delete() }
        _state.value = UiState()
    }

    override fun onCleared() {
        sessionDir.listFiles()?.forEach { it.delete() }
    }

    private fun Exception.readable() = localizedMessage ?: javaClass.simpleName
}
