package app.phopsd.ui.editor

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.phopsd.editor.EditorState
import app.phopsd.editor.RotationHud
import app.phopsd.editor.Tool
import app.phopsd.editor.TwoFingerTarget
import app.phopsd.ui.icons.Lucide
import app.phopsd.ui.theme.PhoType
import kotlin.math.roundToInt
import app.phopsd.engine.geometry.Affine
import app.phopsd.engine.geometry.PointD
import app.phopsd.engine.model.RasterLayer
import app.phopsd.platform.EditorViewModel
import app.phopsd.platform.PreviewFrame
import app.phopsd.ui.theme.PhoColors
import kotlin.math.pow

/**
 * The document viewport. All coordinate conversion goes through the engine [Viewport]
 * (screen <-> document) and layer transforms (document <-> layer); nothing here has its own
 * zoom maths. Overlays (layer bounds) are drawn in screen space and never touch pixels.
 *
 * Input:
 *  - one pointer: the active tool (Move drags the layer, Hand pans, Zoom taps),
 *  - two pointers: pan + pinch zoom (+ rotation after a deliberate 12° twist), any tool,
 *  - mouse wheel: zoom at the pointer,
 *  - Space held: temporary Hand (see KeyShortcuts).
 */
@Composable
fun CanvasView(
    state: EditorState,
    base: PreviewFrame?,
    detail: PreviewFrame?,
    vm: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val c = vm.controller
    val currentTool by rememberUpdatedState(state.effectiveTool)
    val zoomOutMode by rememberUpdatedState(state.zoomToolZoomsOut)
    val density = LocalDensity.current
    val checker = remember(density) { checkerboard(with(density) { 8.dp.roundToPx() }) }
    val matrix = remember { Matrix() }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }

    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(PhoColors.Pasteboard)
            .onSizeChanged { vm.setCanvasSizePx(it.width, it.height) }
            .semantics { contentDescription = "Document canvas" }
            .pointerInput(Unit) {
                // Mouse wheel: zoom at pointer.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll) {
                            val ch = e.changes.firstOrNull() ?: continue
                            val dy = ch.scrollDelta.y
                            if (dy != 0f) {
                                c.zoomAt(ch.position.x.toDouble(), ch.position.y.toDouble(), 1.15.pow(-dy.toDouble()))
                                ch.consume()
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tool = currentTool
                    val start = down.position
                    var last = start
                    var moved = false
                    var multi = false
                    var moving = false
                    val slop = viewConfiguration.touchSlop
                    vm.setInteracting(true)
                    var finished = false
                    try {
                        if (tool == Tool.Move) moving = c.beginMove(start.x.toDouble(), start.y.toDouble())

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed >= 2) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (!multi) {
                                    multi = true
                                    if (moving) {
                                        c.cancelMove()
                                        moving = false
                                    }
                                    val start = if (centroid != Offset.Unspecified) centroid else Offset.Zero
                                    c.beginPinch(start.x.toDouble(), start.y.toDouble())
                                }
                                if (centroid != Offset.Unspecified) {
                                    // Rotation thresholds and snapping live in the controller (unit-tested).
                                    val pan = event.calculatePan()
                                    c.updatePinch(
                                        centroid.x.toDouble(), centroid.y.toDouble(),
                                        pan.x.toDouble(), pan.y.toDouble(),
                                        event.calculateZoom().toDouble(), event.calculateRotation().toDouble(),
                                    )
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (multi) {
                                // After a pinch, ignore the remaining finger until all are lifted.
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: continue
                            val pos = ch.position
                            if (!moved && (pos - start).getDistance() > slop) moved = true
                            when (tool) {
                                Tool.Hand -> {
                                    val d = pos - last
                                    c.panBy(d.x.toDouble(), d.y.toDouble())
                                }
                                Tool.Move -> if (moving) c.updateMove(pos.x.toDouble(), pos.y.toDouble())
                                else -> {}
                            }
                            last = pos
                            ch.consume()
                        }
                        if (!multi) {
                            when (tool) {
                                Tool.Move -> if (moving) c.endMove()
                                Tool.Zoom -> if (!moved) c.zoomToolTap(start.x.toDouble(), start.y.toDouble(), zoomOutMode)
                                else -> {}
                            }
                        } else {
                            if (moving) c.cancelMove()
                            c.endPinch()
                        }
                        finished = true
                    } finally {
                        // Gesture cancelled (e.g. pointer input reset): never leave a half-applied drag.
                        if (!finished && moving) c.cancelMove()
                        if (!finished && multi) c.cancelPinch()
                        vm.setInteracting(false)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val vp = state.viewport ?: return@Canvas
            val doc = state.document ?: return@Canvas
            val m = vp.documentToScreen
            val docQuad = quad(m, 0.0, 0.0, doc.width.toDouble(), doc.height.toDouble())

            // Soft drop shadow edge, then transparency checkerboard inside the document.
            drawPath(docQuad, color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), style = Stroke(width = 6f))
            clipPath(docQuad) {
                drawRect(ShaderBrush(ImageShader(checker, TileMode.Repeated, TileMode.Repeated)))
            }
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                base?.let { drawFrame(nc, it, m, matrix, paint, filter = true) }
                detail?.let { if (it.document == doc) drawFrame(nc, it, m, matrix, paint, filter = !it.nearest) }
            }
            drawPath(docQuad, color = PhoColors.Divider, style = Stroke(width = 1f))

            // Active layer bounds (Move tool / layer pinch), drawn from the live document so it tracks instantly.
            if (state.effectiveTool == Tool.Move) {
                val layer = doc.activeLayer as? RasterLayer
                if (layer != null && layer.visible) {
                    val lm = layer.transform.layerToDocument(layer.width, layer.height).then(m)
                    drawPath(
                        quad(lm, 0.0, 0.0, layer.width.toDouble(), layer.height.toDouble()),
                        color = PhoColors.Accent,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }
        RotationReadout(state.rotationHud, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
    }
}

/**
 * Angle readout while rotating with two fingers. A haptic tick fires each time the
 * rotation locks onto a snap angle, so the snap can be felt without looking.
 */
@Composable
private fun RotationReadout(hud: RotationHud?, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val snappedAt = hud?.takeIf { it.snapped }?.degrees?.roundToInt()
    LaunchedEffect(snappedAt) {
        if (snappedAt != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    if (hud == null) return
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(PhoColors.Raised.copy(alpha = 0.94f))
            .border(1.dp, if (hud.snapped) PhoColors.Accent else PhoColors.Divider, shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (hud.target == TwoFingerTarget.Layer) "Layer" else "View",
            style = PhoType.Caption,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${hud.degrees.roundToInt()}°",
            style = PhoType.Title.copy(color = if (hud.snapped) PhoColors.AccentHover else PhoColors.TextPrimary),
        )
        if (hud.snapped) {
            Spacer(Modifier.width(8.dp))
            Icon(Lucide.Magnet, "Snapped", tint = PhoColors.AccentHover, modifier = Modifier.size(16.dp))
        }
    }
}


private fun drawFrame(
    nc: android.graphics.Canvas,
    frame: PreviewFrame,
    docToScreen: Affine,
    matrix: Matrix,
    paint: Paint,
    filter: Boolean,
) {
    val bmp = frame.bitmap
    if (bmp.isRecycled) return
    val r = frame.docRect
    val local = Affine.scale(r.width / bmp.width, r.height / bmp.height)
        .then(Affine.translate(r.left, r.top))
        .then(docToScreen)
    matrix.setValues(local.toMatrixValues())
    paint.isFilterBitmap = filter
    nc.drawBitmap(bmp, matrix, paint)
}

private fun quad(m: Affine, l: Double, t: Double, r: Double, b: Double): Path {
    val pts = listOf(PointD(l, t), PointD(r, t), PointD(r, b), PointD(l, b)).map { m.map(it) }
    return Path().apply {
        moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
        for (i in 1..3) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
        close()
    }
}

/** 2x2-cell checkerboard tile (screen-space, constant size at every zoom). */
private fun checkerboard(cell: Int): ImageBitmap {
    val size = cell * 2
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val light = PhoColors.CheckerLight.toArgbInt()
    val dark = PhoColors.CheckerDark.toArgbInt()
    for (y in 0 until size) for (x in 0 until size) {
        bmp.setPixel(x, y, if ((x / cell + y / cell) % 2 == 0) light else dark)
    }
    return bmp.asImageBitmap()
}

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )
