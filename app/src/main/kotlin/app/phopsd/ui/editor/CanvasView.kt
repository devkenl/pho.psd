package app.phopsd.ui.editor

import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.phopsd.editor.ColorSlot
import app.phopsd.editor.EditorState
import app.phopsd.editor.RotationHud
import app.phopsd.editor.SelectionDraft
import app.phopsd.editor.Tool
import app.phopsd.editor.TwoFingerTarget
import app.phopsd.editor.isBrushLike
import app.phopsd.editor.isSelectionTool
import app.phopsd.engine.geometry.Affine
import app.phopsd.engine.geometry.PointD
import app.phopsd.engine.geometry.TransformHandles
import app.phopsd.engine.model.OutlineStep
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.SelectionOp
import app.phopsd.engine.model.SelectionOutline
import app.phopsd.engine.model.SelectionShape
import app.phopsd.platform.EditorViewModel
import app.phopsd.platform.PreviewFrame
import app.phopsd.ui.icons.Lucide
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoType
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** What a one-pointer gesture does, decided at pointer-down from the tool and input device. */
private enum class GestureMode { Paint, Pan, Move, Zoom, Sample, Select, PolygonTap, Crop, Transform, None }

/**
 * The document viewport. All coordinate conversion goes through the engine Viewport
 * (screen <-> document) and layer transforms (document <-> layer); nothing here has its own
 * zoom maths. Overlays (selection, crop, handles, brush outline) are drawn in screen space and
 * never touch pixels.
 *
 * Input:
 *  - one pointer: the active tool. Brushes paint with a stylus or mouse; fingers paint too
 *    until a stylus is first used, after which one finger pans (palm-safe pen mode).
 *  - two pointers: pan + pinch zoom (+ rotation after a deliberate twist), any tool. A finger
 *    stroke is cancelled by a second finger; a stylus stroke ignores fingers.
 *  - mouse wheel: zoom at the pointer; Alt with a brush samples colour.
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
    val current = rememberUpdatedState(state)
    val density = LocalDensity.current
    val dp = density.density
    val checker = remember(density) { checkerboard(with(density) { 8.dp.roundToPx() }) }
    val matrix = remember { Matrix() }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val antsPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    var hover by remember { mutableStateOf<Offset?>(null) }
    val doc = state.document
    val selectionPath = remember(doc?.selection?.outline, doc?.width, doc?.height) {
        val sel = doc?.selection ?: return@remember null
        outlinePath(sel.outline, doc.width, doc.height)
    }
    val animateAnts = selectionPath != null || state.selectionDraft != null
    val antsPhase: State<Float>? = if (animateAnts) {
        rememberInfiniteTransition(label = "ants").animateFloat(
            0f, 12f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Restart), label = "antsPhase",
        )
    } else null

    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(PhoColors.Pasteboard)
            .onSizeChanged { vm.setCanvasSizePx(it.width, it.height) }
            .semantics { contentDescription = "Document canvas" }
            .pointerInput(Unit) {
                // Wheel zoom, hover position (brush outline, polygon rubber band).
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: continue
                        when (e.type) {
                            PointerEventType.Scroll -> {
                                val dy = ch.scrollDelta.y
                                if (dy != 0f) {
                                    c.zoomAt(ch.position.x.toDouble(), ch.position.y.toDouble(), 1.15.pow(-dy.toDouble()))
                                    ch.consume()
                                }
                            }
                            PointerEventType.Exit -> hover = null
                            PointerEventType.Release -> if (ch.type == PointerType.Touch) hover = null
                            PointerEventType.Move, PointerEventType.Enter, PointerEventType.Press -> {
                                hover = ch.position
                                val s = current.value
                                if (!ch.pressed && s.tool == Tool.PolygonalLasso && (s.selectionDraft as? SelectionDraft.Lasso)?.polygonal == true) {
                                    s.viewport?.toDocument(ch.position.x.toDouble(), ch.position.y.toDouble())?.let { p ->
                                        c.tools.updateSelection(p.x, p.y)
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                var lastTapAt = 0L
                var lastTapPos = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val s = current.value
                    val tool = s.effectiveTool
                    val isStylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    val isMouse = down.type == PointerType.Mouse
                    val mods = currentEvent.keyboardModifiers
                    val shiftAtDown = mods.isShiftPressed
                    val altAtDown = mods.isAltPressed
                    val start = down.position
                    var last = start
                    var moved = false
                    var multi = false
                    val slop = viewConfiguration.touchSlop
                    fun toDoc(o: Offset): PointD? = c.state.value.viewport?.toDocument(o.x.toDouble(), o.y.toDouble())
                    fun zoom(): Double = c.state.value.viewport?.zoom ?: 1.0

                    var mode = when {
                        tool.isBrushLike && altAtDown -> GestureMode.Sample
                        tool.isBrushLike -> if (isStylus || isMouse || s.fingerPainting) GestureMode.Paint else GestureMode.Pan
                        tool == Tool.Eyedropper -> GestureMode.Sample
                        tool == Tool.PolygonalLasso -> GestureMode.PolygonTap
                        tool.isSelectionTool -> GestureMode.Select
                        tool == Tool.Crop -> GestureMode.Crop
                        tool == Tool.Transform -> GestureMode.Transform
                        tool == Tool.Move -> GestureMode.Move
                        tool == Tool.Hand -> GestureMode.Pan
                        tool == Tool.Zoom -> GestureMode.Zoom
                        else -> GestureMode.None
                    }
                    val sampleSlot = if (tool == Tool.Eyedropper && altAtDown) ColorSlot.Background else ColorSlot.Foreground
                    vm.setInteracting(mode != GestureMode.Paint)
                    var lastPressure = down.pressure.toDouble()
                    fun pressureOf(ch: PointerInputChange): Double = if (isStylus) ch.pressure.toDouble().coerceIn(0.0, 1.0) else 1.0

                    // Pointer-down action.
                    val startDoc = toDoc(start)
                    when (mode) {
                        GestureMode.Paint -> startDoc?.let {
                            lastPressure = pressureOf(down)
                            vm.strokeBegin(it.x, it.y, lastPressure, eraserTip = down.type == PointerType.Eraser, stylus = isStylus)
                        }
                        GestureMode.Sample -> startDoc?.let { c.tools.sampleColor(it.x, it.y, sampleSlot) }
                        GestureMode.Move -> if (!c.beginMove(start.x.toDouble(), start.y.toDouble())) mode = GestureMode.None
                        GestureMode.Select -> startDoc?.let { c.tools.beginSelection(it.x, it.y, shiftAtDown, altAtDown) }
                        GestureMode.Crop -> {
                            val ok = startDoc != null && c.tools.cropDragBegin(startDoc.x, startDoc.y, 24.0 * dp / zoom())
                            if (!ok) mode = GestureMode.Pan
                        }
                        GestureMode.Transform -> {
                            val h = startDoc?.let { c.tools.transformBegin(it.x, it.y, 22.0 * dp / zoom(), 44.0 * dp / zoom()) }
                            if (h == null) mode = GestureMode.Pan
                        }
                        else -> {}
                    }

                    var finished = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            // A stylus stroke ignores fingers (palm rest); otherwise 2 pointers = navigate.
                            val penStroke = mode == GestureMode.Paint && isStylus
                            if (pressed >= 2 && !penStroke) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (!multi) {
                                    multi = true
                                    when (mode) {
                                        GestureMode.Paint -> vm.strokeCancel()
                                        GestureMode.Move -> c.cancelMove()
                                        GestureMode.Select -> c.tools.cancelSelectionDraft()
                                        GestureMode.Crop -> c.tools.cropDragEnd()
                                        GestureMode.Transform -> c.tools.transformCancel()
                                        else -> {}
                                    }
                                    mode = GestureMode.None
                                    vm.setInteracting(true)
                                    val p = if (centroid != Offset.Unspecified) centroid else Offset.Zero
                                    c.beginPinch(p.x.toDouble(), p.y.toDouble())
                                }
                                if (centroid != Offset.Unspecified) {
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
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: continue
                            val pos = ch.position
                            if (!moved && (pos - start).getDistance() > slop) moved = true
                            val emods = event.keyboardModifiers
                            when (mode) {
                                GestureMode.Paint -> {
                                    val vp = c.state.value.viewport
                                    if (vp != null) {
                                        val hist = ch.historical
                                        val p1 = pressureOf(ch)
                                        val pts = DoubleArray((hist.size + 1) * 3)
                                        var k = 0
                                        for ((i, h) in hist.withIndex()) {
                                            val d = vp.toDocument(h.position.x.toDouble(), h.position.y.toDouble())
                                            pts[k++] = d.x; pts[k++] = d.y
                                            pts[k++] = lastPressure + (p1 - lastPressure) * (i + 1) / (hist.size + 1)
                                        }
                                        val d = vp.toDocument(pos.x.toDouble(), pos.y.toDouble())
                                        pts[k++] = d.x; pts[k++] = d.y; pts[k] = p1
                                        lastPressure = p1
                                        vm.strokeTo(pts)
                                    }
                                }
                                GestureMode.Pan -> {
                                    val d = pos - last
                                    c.panBy(d.x.toDouble(), d.y.toDouble())
                                }
                                GestureMode.Move -> c.updateMove(pos.x.toDouble(), pos.y.toDouble())
                                GestureMode.Sample -> toDoc(pos)?.let { c.tools.sampleColor(it.x, it.y, sampleSlot) }
                                GestureMode.Select -> toDoc(pos)?.let {
                                    c.tools.updateSelection(it.x, it.y, emods.isShiftPressed, emods.isAltPressed, minStep = 1.5 / zoom())
                                }
                                GestureMode.PolygonTap -> toDoc(pos)?.let { c.tools.updateSelection(it.x, it.y) }
                                GestureMode.Crop -> toDoc(pos)?.let { c.tools.cropDragUpdate(it.x, it.y) }
                                GestureMode.Transform -> toDoc(pos)?.let { c.tools.transformUpdate(it.x, it.y, emods.isShiftPressed, emods.isAltPressed) }
                                else -> {}
                            }
                            last = pos
                            ch.consume()
                        }
                        if (multi) {
                            c.endPinch()
                        } else {
                            val now = System.currentTimeMillis()
                            val doubleTap = !moved && now - lastTapAt < DOUBLE_TAP_MS && (last - lastTapPos).getDistance() < slop * 3
                            when (mode) {
                                GestureMode.Paint -> vm.strokeEnd()
                                GestureMode.Move -> c.endMove()
                                GestureMode.Zoom -> if (!moved) c.zoomToolTap(start.x.toDouble(), start.y.toDouble(), s.zoomToolZoomsOut)
                                GestureMode.Select -> c.tools.endSelection()
                                GestureMode.PolygonTap -> toDoc(last)?.let {
                                    if (doubleTap) c.tools.finishPolygon()
                                    else c.tools.polygonTap(it.x, it.y, 14.0 * dp / zoom(), shiftAtDown, altAtDown)
                                }
                                GestureMode.Crop -> {
                                    c.tools.cropDragEnd()
                                    if (doubleTap) c.tools.commitCrop()
                                }
                                GestureMode.Transform -> c.tools.transformEnd()
                                else -> {}
                            }
                            if (!moved) { lastTapAt = now; lastTapPos = last }
                        }
                        finished = true
                    } finally {
                        // Gesture cancelled (e.g. pointer input reset): never leave a half-applied edit.
                        if (!finished) {
                            when (mode) {
                                GestureMode.Paint -> vm.strokeCancel()
                                GestureMode.Move -> c.cancelMove()
                                GestureMode.Select -> c.tools.cancelSelectionDraft()
                                GestureMode.Crop -> c.tools.cropDragEnd()
                                GestureMode.Transform -> c.tools.transformCancel()
                                else -> {}
                            }
                            if (multi) c.cancelPinch()
                        }
                        vm.setInteracting(false)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val vp = state.viewport ?: return@Canvas
            val d = state.document ?: return@Canvas
            val m = vp.documentToScreen
            val docQuad = quad(m, 0.0, 0.0, d.width.toDouble(), d.height.toDouble())

            // Soft drop shadow edge, then transparency checkerboard inside the document.
            drawPath(docQuad, color = Color.Black.copy(alpha = 0.35f), style = Stroke(width = 6f))
            clipPath(docQuad) {
                drawRect(ShaderBrush(ImageShader(checker, TileMode.Repeated, TileMode.Repeated)))
            }
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                base?.let { drawFrame(nc, it, m, matrix, paint, filter = true) }
                detail?.let { if (it.document == d) drawFrame(nc, it, m, matrix, paint, filter = !it.nearest) }
            }
            drawPath(docQuad, color = PhoColors.Divider, style = Stroke(width = 1f))

            val tool = state.effectiveTool
            val layer = d.activeLayer as? RasterLayer

            // Active layer bounds (Move tool / layer pinch), drawn from the live document so it tracks instantly.
            if (tool == Tool.Move && layer != null && layer.visible) {
                val lm = layer.transform.layerToDocument(layer.width, layer.height).then(m)
                drawPath(quad(lm, 0.0, 0.0, layer.width.toDouble(), layer.height.toDouble()), color = PhoColors.Accent, style = Stroke(width = 1.5.dp.toPx()))
            }

            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                matrix.setValues(m.toMatrixValues())
                val phase = antsPhase?.value ?: 0f
                // Marching ants.
                selectionPath?.let { drawAnts(nc, it, matrix, antsPaint, phase, dp) }
                state.selectionDraft?.let { draft -> draftPath(draft)?.let { drawAnts(nc, it, matrix, antsPaint, phase, dp) } }
            }

            // Polygonal lasso vertices.
            (state.selectionDraft as? SelectionDraft.Lasso)?.takeIf { it.polygonal }?.let { poly ->
                for ((i, p) in poly.points.withIndex()) {
                    val sp = m.map(p)
                    drawCircle(if (i == 0) PhoColors.Accent else Color.White, radius = (if (i == 0) 5 else 3).dp.toPx(), center = Offset(sp.x.toFloat(), sp.y.toFloat()))
                }
            }

            // Crop: shade outside, frame, rule of thirds, handles.
            state.crop?.takeIf { tool == Tool.Crop }?.let { crop ->
                val r = crop.rect
                val box = quad(m, r.left, r.top, r.right, r.bottom)
                clipPath(box, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
                    drawRect(Color.Black.copy(alpha = 0.55f))
                }
                drawPath(box, Color.White, style = Stroke(width = 1.5.dp.toPx()))
                for (k in 1..2) {
                    val fx = r.left + r.width * k / 3
                    val fy = r.top + r.height * k / 3
                    line(this, m, fx, r.top, fx, r.bottom, Color.White.copy(alpha = 0.45f), 1f)
                    line(this, m, r.left, fy, r.right, fy, Color.White.copy(alpha = 0.45f), 1f)
                }
                for (h in TransformHandles.Handle.entries.filter { it.isScale }) {
                    val hx = if (h.fx < 0) r.left else if (h.fx > 0) r.right else (r.left + r.right) / 2
                    val hy = if (h.fy < 0) r.top else if (h.fy > 0) r.bottom else (r.top + r.bottom) / 2
                    handle(this, m.map(PointD(hx, hy)), h.isCorner)
                }
            }

            // Free transform box and handles.
            if (tool == Tool.Transform && layer != null) {
                val t = layer.transform
                val corners = TransformHandles.corners(t, layer.width, layer.height).map { m.map(it) }
                val box = Path().apply {
                    moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
                    for (i in 1..3) lineTo(corners[i].x.toFloat(), corners[i].y.toFloat())
                    close()
                }
                drawPath(box, PhoColors.Accent, style = Stroke(width = 1.5.dp.toPx()))
                for (h in TransformHandles.Handle.entries.filter { it.isScale }) {
                    handle(this, m.map(TransformHandles.handlePoint(t, layer.width, layer.height, h.fx, h.fy)), h.isCorner)
                }
                val cpt = m.map(PointD(t.centerX, t.centerY))
                drawCircle(PhoColors.Accent, radius = 3.dp.toPx(), center = Offset(cpt.x.toFloat(), cpt.y.toFloat()))
            }

            // Brush outline at the pointer (document-size circle on screen).
            if (tool.isBrushLike) {
                hover?.let { h ->
                    val r = (state.activeBrush.size / 2 * vp.zoom).toFloat()
                    if (r >= 3f) {
                        drawCircle(Color.Black.copy(alpha = 0.7f), radius = r + 1f, center = h, style = Stroke(width = 1f))
                        drawCircle(Color.White.copy(alpha = 0.9f), radius = r, center = h, style = Stroke(width = 1f))
                    } else {
                        val k = 6.dp.toPx()
                        drawLine(Color.White, h - Offset(k, 0f), h + Offset(k, 0f), strokeWidth = 1f)
                        drawLine(Color.White, h - Offset(0f, k), h + Offset(0f, k), strokeWidth = 1f)
                    }
                }
            }
        }
        RotationReadout(state.rotationHud, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
    }
}

private const val DOUBLE_TAP_MS = 320L

private fun line(scope: androidx.compose.ui.graphics.drawscope.DrawScope, m: Affine, x0: Double, y0: Double, x1: Double, y1: Double, color: Color, width: Float) {
    val a = m.map(PointD(x0, y0))
    val b = m.map(PointD(x1, y1))
    scope.drawLine(color, Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), strokeWidth = width)
}

/** A square (corner) or round (edge) handle with a dark outline, finger-visible. */
private fun handle(scope: androidx.compose.ui.graphics.drawscope.DrawScope, p: PointD, corner: Boolean) = with(scope) {
    val c = Offset(p.x.toFloat(), p.y.toFloat())
    val r = (if (corner) 6 else 5).dp.toPx()
    if (corner) {
        drawRect(Color.Black.copy(alpha = 0.6f), topLeft = c - Offset(r + 1, r + 1), size = androidx.compose.ui.geometry.Size(2 * r + 2, 2 * r + 2))
        drawRect(Color.White, topLeft = c - Offset(r, r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r))
    } else {
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = r + 1, center = c)
        drawCircle(Color.White, radius = r, center = c)
    }
}

/** Builds the selection outline (document space) by replaying its steps with path operations. */
private fun outlinePath(outline: SelectionOutline, w: Int, h: Int): android.graphics.Path {
    var p = android.graphics.Path()
    for (step in outline.steps) {
        when (step) {
            is OutlineStep.Shape -> {
                val sp = shapePath(step.shape)
                when (step.op) {
                    SelectionOp.Replace -> p = sp
                    SelectionOp.Add -> p.op(sp, android.graphics.Path.Op.UNION)
                    SelectionOp.Subtract -> p.op(sp, android.graphics.Path.Op.DIFFERENCE)
                    SelectionOp.Intersect -> p.op(sp, android.graphics.Path.Op.INTERSECT)
                }
            }
            OutlineStep.Invert -> {
                val canvas = android.graphics.Path().apply { addRect(0f, 0f, w.toFloat(), h.toFloat(), android.graphics.Path.Direction.CW) }
                canvas.op(p, android.graphics.Path.Op.DIFFERENCE)
                p = canvas
            }
        }
    }
    p.op(android.graphics.Path().apply { addRect(0f, 0f, w.toFloat(), h.toFloat(), android.graphics.Path.Direction.CW) }, android.graphics.Path.Op.INTERSECT)
    return p
}

private fun shapePath(shape: SelectionShape): android.graphics.Path = android.graphics.Path().apply {
    when (shape) {
        is SelectionShape.Rect -> addRect(shape.left.toFloat(), shape.top.toFloat(), shape.right.toFloat(), shape.bottom.toFloat(), android.graphics.Path.Direction.CW)
        is SelectionShape.Ellipse -> addOval(RectF(shape.left.toFloat(), shape.top.toFloat(), shape.right.toFloat(), shape.bottom.toFloat()), android.graphics.Path.Direction.CW)
        is SelectionShape.Polygon -> {
            val xy = shape.xy
            if (xy.size >= 4) {
                moveTo(xy[0].toFloat(), xy[1].toFloat())
                var i = 2
                while (i + 1 < xy.size) { lineTo(xy[i].toFloat(), xy[i + 1].toFloat()); i += 2 }
                close()
            }
        }
    }
}

/** The outline of a selection being drawn (document space). */
private fun draftPath(d: SelectionDraft): android.graphics.Path? = when (d) {
    is SelectionDraft.Box -> d.shape()?.let { shapePath(it) }
    is SelectionDraft.Lasso -> android.graphics.Path().apply {
        if (d.points.isEmpty()) return null
        moveTo(d.points[0].x.toFloat(), d.points[0].y.toFloat())
        for (p in d.points.drop(1)) lineTo(p.x.toFloat(), p.y.toFloat())
        d.cursor?.let { lineTo(it.x.toFloat(), it.y.toFloat()) }
        if (!d.polygonal) close()
    }
}

/** Two-tone dashed outline that stays visible on any image ("marching ants"). */
private fun drawAnts(nc: android.graphics.Canvas, docPath: android.graphics.Path, docToScreen: Matrix, p: Paint, phase: Float, dp: Float) {
    val screen = android.graphics.Path()
    docPath.transform(docToScreen, screen)
    p.strokeWidth = max(1f, dp)
    p.pathEffect = null
    p.color = android.graphics.Color.WHITE
    nc.drawPath(screen, p)
    p.color = android.graphics.Color.BLACK
    p.pathEffect = DashPathEffect(floatArrayOf(6f * dp, 6f * dp), phase * dp)
    nc.drawPath(screen, p)
    p.pathEffect = null
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

private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
