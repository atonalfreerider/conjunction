package com.metavirtuoso.conjunction.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.metavirtuoso.conjunction.AppContainer
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.core.palm.Hand
import com.metavirtuoso.conjunction.core.palm.LineLength
import com.metavirtuoso.conjunction.core.palm.PalmLine
import com.metavirtuoso.conjunction.palm.PalmScan
import com.metavirtuoso.conjunction.palm.PalmScanException
import com.metavirtuoso.conjunction.palm.PalmScanner
import com.metavirtuoso.conjunction.ui.components.ChoiceChips
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GhostButton
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.OrnatePanel
import com.metavirtuoso.conjunction.ui.components.OrreryLoader
import com.metavirtuoso.conjunction.ui.components.ReadingCard
import com.metavirtuoso.conjunction.ui.components.ReadingState
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PalmStage {
    data object Intro : PalmStage
    data object Camera : PalmStage
    data class Analyzing(val photo: Bitmap) : PalmStage
    data class Result(val scan: PalmScan) : PalmStage
}

/**
 * Palm reading: photograph the open palm (CameraX with the guide overlay) or pick a photo, find
 * the hand with MediaPipe, measure the lines on device, and have the phone's model write the
 * reading. The photo lives in memory only and is dropped when you leave, unless you save it
 * (app-private storage, never uploaded).
 */
@Composable
fun PalmScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<PalmStage>(PalmStage.Intro) }
    var hand by rememberSaveable { mutableStateOf(Hand.RIGHT) }
    var error by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<ReadingState>(ReadingState.Loading()) }
    var cameraRationale by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    fun analyze(photo: Bitmap) {
        stage = PalmStage.Analyzing(photo)
        error = null
        saved = false
        scope.launch {
            try {
                val scan = container.palmScanner.scan(photo, hand)
                stage = PalmStage.Result(scan)
                reading = ReadingState.Loading()
                reading = ReadingState.Ready(container.readings.palm(scan.features, scan.photo) { status -> reading = ReadingState.Loading(status) })
            } catch (e: PalmScanException) {
                error = e.message
                stage = PalmStage.Intro
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "The palm could not be read: ${e.message}"
                stage = PalmStage.Intro
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                analyze(withContext(Dispatchers.IO) { PalmScanner.loadUri(context, uri) })
            } catch (e: PalmScanException) {
                error = e.message
            }
        }
    }
    val cameraDenied = stringResource(R.string.perm_camera_denied)
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) stage = PalmStage.Camera else error = cameraDenied
    }
    // The photo lives only in this composition's state: leaving the tab drops it (privacy: photos
    // are not kept unless saved).

    when (val s = stage) {
        PalmStage.Camera -> {
            BackHandler { stage = PalmStage.Intro }
            PalmCamera(hand, onCaptured = ::analyze, onCancel = { stage = PalmStage.Intro }, onError = { error = it; stage = PalmStage.Intro })
        }
        else -> TabPage("Palm Reading", subtitle = "Read on this phone — your photo is never uploaded") {
            when (s) {
                PalmStage.Intro -> {
                    Image(painterResource(R.drawable.palm_lines_diagram), contentDescription = "Palm with heart, head, life and fate lines", modifier = Modifier.size(240.dp))
                    OrnatePanel(Modifier.fillMaxWidth()) {
                        Text("Which hand?", style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
                        FinePrint("Palmists read your dominant hand for the life you are making.")
                        ChoiceChips(listOf(Hand.LEFT to "Left hand", Hand.RIGHT to "Right hand"), hand, { hand = it }, Modifier.padding(top = 8.dp))
                        Text("Lay the hand flat, palm up, fingers slightly apart, in soft even light. Fill the outline.", style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone, modifier = Modifier.padding(top = 10.dp))
                    }
                    GoldButton("Use the camera", modifier = Modifier.fillMaxWidth(), onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) stage = PalmStage.Camera
                        else cameraRationale = true
                    })
                    GhostButton("Choose a photo", modifier = Modifier.fillMaxWidth(), onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    error?.let { Text(it, color = Palette.RoseCopper, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                }
                is PalmStage.Analyzing -> {
                    Box(contentAlignment = Alignment.Center) {
                        Image(s.photo.asImageBitmap(), contentDescription = "Your palm photo", modifier = Modifier.fillMaxWidth().aspectRatio(s.photo.width.toFloat() / s.photo.height).graphicsLayer { alpha = 0.6f })
                        OrreryLoader(size = 96.dp)
                    }
                    Text("Tracing your lines…", style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
                }
                is PalmStage.Result -> {
                    PalmOverlayImage(s.scan)
                    PalmSummary(s.scan)
                    ReadingCard("Your palm", reading)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(if (saved) "Saved" else "Save photo", enabled = !saved, modifier = Modifier.weight(1f), onClick = {
                            scope.launch { saved = savePalmPhoto(context, s.scan.photo) }
                        })
                        GoldButton("New reading", modifier = Modifier.weight(1f), onClick = { stage = PalmStage.Intro })
                    }
                    FinePrint("Saved palm photos stay in this app's private storage and are erased by Delete account.")
                }
                PalmStage.Camera -> Unit
            }
        }
    }
    if (cameraRationale) {
        PermissionRationaleDialog(
            title = stringResource(R.string.perm_camera_title),
            body = stringResource(R.string.perm_camera_body),
            button = stringResource(R.string.perm_camera_button),
            art = R.drawable.palm_lines_diagram,
            onConfirm = { cameraRationale = false; cameraPermission.launch(Manifest.permission.CAMERA) },
            onDismiss = { cameraRationale = false },
        )
    }
}

private suspend fun savePalmPhoto(context: Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File(context.filesDir, "palms").apply { mkdirs() }
        val name = "palm_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".jpg"
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        true
    }.getOrDefault(false)
}

/** The photo with the detected landmarks and the four lines traced in gold. */
@Composable
private fun PalmOverlayImage(scan: PalmScan) {
    val photo = scan.photo
    val lineColors = mapOf(PalmLine.HEART to Palette.RoseCopper, PalmLine.HEAD to Palette.GoldPale, PalmLine.LIFE to Palette.Gold, PalmLine.FATE to Palette.Moonstone)
    Box(
        Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat() / photo.height).border(1.dp, GoldBrushes.leaf, androidx.compose.ui.graphics.RectangleShape)
            .semantics { contentDescription = "Your palm with the detected lines traced: ${scan.detection.lines.keys.joinToString { it.displayName }}" },
    ) {
        Image(photo.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / photo.width
            val sy = size.height / photo.height
            scan.landmarks.points.forEach { drawCircle(Palette.GoldPale.copy(alpha = 0.7f), radius = 3.dp.toPx(), center = Offset(it.x * sx, it.y * sy)) }
            scan.detection.lines.forEach { (line, detected) ->
                val color = lineColors[line] ?: Color.White
                detected.polyline.zipWithNext { a, b ->
                    drawLine(color, Offset(a.x.toFloat() * sx, a.y.toFloat() * sy), Offset(b.x.toFloat() * sx, b.y.toFloat() * sy), strokeWidth = 3.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun PalmSummary(scan: PalmScan) {
    val f = scan.features
    OrnatePanel(Modifier.fillMaxWidth()) {
        Text("${f.handShape.displayName} hand".uppercase(), style = MaterialTheme.typography.titleMedium, color = Palette.Gold)
        FinePrint("Palm width/length ${"%.2f".format(f.palmRatio)} · middle finger/palm ${"%.2f".format(f.fingers.fingerToPalm)} · index/ring ${"%.2f".format(f.fingers.indexToRing)}")
        PalmLine.entries.forEach { line ->
            val lf = f.line(line)
            val desc = if (!lf.present) "not clearly visible" else listOf(
                when (lf.lengthClass) { LineLength.LONG -> "long"; LineLength.MEDIUM -> "medium"; LineLength.SHORT -> "short"; LineLength.ABSENT -> "faint" },
                if (lf.isCurved) "curved" else "straight",
            ).joinToString(", ")
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(line.displayName.replaceFirstChar(Char::uppercase) + " line", style = MaterialTheme.typography.bodyMedium, color = Palette.GoldPale, modifier = Modifier.weight(1f))
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
            }
        }
        if (f.quality < 0.5) FinePrint("The photo was hard to read (light or focus); this reading is extra playful.")
    }
}

/** Full-screen CameraX capture with the gold hand-outline guide (mirrored for a left hand). */
@Composable
private fun PalmCamera(hand: Hand, onCaptured: (Bitmap) -> Unit, onCancel: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var busy by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            } catch (e: Exception) {
                onError("The camera could not start: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Image(
            painterResource(R.drawable.palm_guide_overlay),
            contentDescription = "Hand outline guide",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(24.dp).graphicsLayer { scaleX = if (hand == Hand.LEFT) -1f else 1f },
        )
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Fit your ${if (hand == Hand.LEFT) "left" else "right"} palm inside the outline", style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale, textAlign = TextAlign.Center)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton("Cancel", onClick = onCancel)
            Box(
                Modifier.size(78.dp).border(3.dp, GoldBrushes.leaf, CircleShape).padding(8.dp).background(if (busy) Palette.GoldDeep else Palette.Gold, CircleShape)
                    .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Take palm photo") {
                        busy = true
                        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val rotation = image.imageInfo.rotationDegrees
                                val bitmap = image.toBitmap()
                                image.close()
                                busy = false
                                onCaptured(PalmScanner.rotate(bitmap, rotation))
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                onError("The photo could not be taken: ${exception.message}")
                            }
                        })
                    }
                    .semantics { contentDescription = "Take palm photo" },
            )
            Box(Modifier.size(80.dp))
        }
    }
}
