package com.papyrus.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papyrus.app.R
import com.papyrus.app.data.CreatePersistableDocument
import com.papyrus.app.scanner.ScanFilter
import com.papyrus.app.ui.AppViewModelProvider
import com.papyrus.app.ui.asString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onDocumentSaved: (Long) -> Unit,
    viewModel: ScannerViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val saveLauncher = rememberLauncherForActivityResult(CreatePersistableDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) viewModel.export(uri, onDocumentSaved)
    }

    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.asString(context))
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraContent(
                state = state,
                onCaptured = viewModel::onCaptured,
                onCaptureError = viewModel::reportCaptureError,
                onCameraError = viewModel::reportCameraError,
                onFilter = viewModel::setFilter,
                onRemovePage = viewModel::removePage,
                onSave = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    saveLauncher.launch("Scan-$stamp.pdf")
                },
                onBack = onBack,
            )
        } else {
            PermissionRationale(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onBack = onBack,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
        if (state.isExporting) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun CameraContent(
    state: ScannerViewModel.UiState,
    onCaptured: (Bitmap, Int) -> Unit,
    onCaptureError: (String?) -> Unit,
    onCameraError: (String?) -> Unit,
    onFilter: (ScanFilter) -> Unit,
    onRemovePage: (Long) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    // Cap capture at ~8 MP so a decoded bitmap stays around 32 MB.
    val resolution = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolution)
            .build()
    }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { captureExecutor.shutdown() } }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var flashMode by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }

    // CameraX starts and stops the camera with the nav entry's lifecycle.
    LaunchedEffect(lifecycleOwner) {
        try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            val preview = Preview.Builder().setResolutionSelector(resolution).build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onCameraError(e.localizedMessage)
        }
    }
    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }

    val busy = state.isProcessing || state.isExporting
    fun capture() {
        imageCapture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        onCaptured(image.toBitmap(), image.imageInfo.rotationDegrees)
                    } catch (e: Exception) {
                        onCaptureError(e.localizedMessage)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) = onCaptureError(exception.localizedMessage)
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        GuideOverlay(
            Modifier.fillMaxSize().pointerInput(camera) {
                detectTapGestures { tap ->
                    camera?.let { cam ->
                        val point = previewView.meteringPointFactory.createPoint(tap.x, tap.y)
                        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    }
                }
            },
        )

        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (state.isProcessing) {
                Text(stringResource(R.string.scanner_processing), color = Color.White, modifier = Modifier.padding(end = 12.dp))
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                TextButton(onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                }) {
                    val label = when (flashMode) {
                        ImageCapture.FLASH_MODE_AUTO -> R.string.scanner_flash_auto
                        ImageCapture.FLASH_MODE_ON -> R.string.scanner_flash_on
                        else -> R.string.scanner_flash_off
                    }
                    Text(stringResource(label), color = Color.White)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.pages.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                        PageThumbnail(page, index, onRemove = { onRemovePage(page.id) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { onFilter(filter) },
                        label = { Text(stringResource(filter.label)) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.scanner_pages, state.pages.size),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                ShutterButton(enabled = !busy, onClick = ::capture)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Button(onClick = onSave, enabled = state.pages.isNotEmpty() && !busy) {
                        Text(stringResource(R.string.action_save_pdf))
                    }
                }
            }
        }
    }
}

/** Corner brackets showing roughly where an A4-proportioned page should sit. */
@Composable
private fun GuideOverlay(modifier: Modifier) {
    Canvas(modifier) {
        val margin = size.width * 0.08f
        val w = size.width - 2 * margin
        val h = minOf(w * 1.414f, size.height * 0.62f)
        val left = margin
        val top = (size.height - h) / 2f - size.height * 0.05f
        val right = left + w
        val bottom = top + h
        val len = 32.dp.toPx()
        val stroke = 4.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(Color.White, Offset(x, y), Offset(x + dx * len, y), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(x, y), Offset(x, y + dy * len), stroke, StrokeCap.Round)
        }
        corner(left, top, 1f, 1f)
        corner(right, top, -1f, 1f)
        corner(left, bottom, 1f, -1f)
        corner(right, bottom, -1f, -1f)
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.cd_capture)
    Box(
        Modifier.size(72.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.Gray)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun PageThumbnail(page: ScannerViewModel.ScanPage, index: Int, onRemove: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, page.file) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(page.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
        }
    }
    val removeLabel = stringResource(R.string.cd_remove_page, index + 1)
    Box(Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray)) {
        bitmap?.let { bmp ->
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(20.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = removeLabel },
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.padding(3.dp))
        }
    }
}

@Composable
private fun PermissionRationale(onGrant: () -> Unit, onSettings: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.scanner_permission_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(
            stringResource(R.string.scanner_permission_body),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onGrant) { Text(stringResource(R.string.scanner_grant)) }
        TextButton(onClick = onSettings) { Text(stringResource(R.string.scanner_settings)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}
