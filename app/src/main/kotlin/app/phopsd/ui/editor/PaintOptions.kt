package app.phopsd.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.phopsd.editor.ColorSlot
import app.phopsd.editor.CommandId
import app.phopsd.editor.EditorController
import app.phopsd.editor.EditorDialog
import app.phopsd.editor.EditorState
import app.phopsd.editor.MaskView
import app.phopsd.editor.NumericTarget
import app.phopsd.editor.PaintTarget
import app.phopsd.editor.SelectionDraft
import app.phopsd.editor.Tool
import app.phopsd.engine.geometry.CropBox
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.SelectionOp
import app.phopsd.engine.paint.BrushPreset
import app.phopsd.engine.paint.BrushSettings
import app.phopsd.ui.components.PhoIconButton
import app.phopsd.ui.components.SliderCell
import app.phopsd.ui.icons.Lucide
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoType
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Brush / Eraser / Mask brush options, laid out like the reference brush strip. */
@Composable
internal fun BrushOptions(state: EditorState, c: EditorController) {
    val b = state.activeBrush
    val t = c.tools
    BrushPresetPicker(state, c)
    OptionsSep()
    // Size on a log scale: equal travel for 1–10 px and 500–5000 px.
    val minL = ln(BrushSettings.MIN_SIZE)
    val maxL = ln(BrushSettings.MAX_SIZE)
    SliderCell(
        label = "Size",
        valueText = "${formatSize(b.size)} px",
        value = ln(b.size).toFloat(),
        range = minL.toFloat()..maxL.toFloat(),
        onChange = { v -> t.setBrushSettings(c.state.value.activeBrush.copy(size = exp(v.toDouble()).coerceIn(BrushSettings.MIN_SIZE, BrushSettings.MAX_SIZE))) },
        onChangeFinished = {},
        onRequestNumericEntry = {},
        modifier = Modifier.width(170.dp),
    )
    PercentSlider("Opacity", b.opacity, 0.01) { v -> t.setBrushSettings(c.state.value.activeBrush.copy(opacity = v)) }
    PercentSlider("Flow", b.flow, 0.01) { v -> t.setBrushSettings(c.state.value.activeBrush.copy(flow = v)) }
    HardnessKnob(b.hardness) { v -> t.setBrushSettings(c.state.value.activeBrush.copy(hardness = v)) }
    OptionsSep()
    PercentSlider("Smoothing", b.smoothing / 0.95, 0.0, width = 140) { v -> t.setBrushSettings(c.state.value.activeBrush.copy(smoothing = (v * 0.95).coerceIn(0.0, 0.95))) }
    OptionsSep()
    OptionLabel("Pressure")
    ToggleChip("Size", b.pressureSize) { t.setBrushSettings(b.copy(pressureSize = !b.pressureSize)) }
    ToggleChip("Opacity", b.pressureOpacity) { t.setBrushSettings(b.copy(pressureOpacity = !b.pressureOpacity)) }
    ToggleChip("Flow", b.pressureFlow) { t.setBrushSettings(b.copy(pressureFlow = !b.pressureFlow)) }
    OptionsSep()
    val layer = state.document?.activeLayer as? RasterLayer
    if (state.tool == Tool.Mask) {
        OptionLabel(if (layer?.mask == null) "Adds a mask on first stroke" else "Black hides · White reveals")
    } else if (layer?.mask != null) {
        OptionLabel("Paint on")
        for (target in PaintTarget.entries) {
            ToggleChip(target.label, (if (state.paintsMask) PaintTarget.Mask else PaintTarget.Pixels) == target) { t.setPaintTarget(target) }
        }
    }
    if (layer?.mask != null) {
        OptionLabel("Show mask")
        ToggleChip("Overlay", state.maskView == MaskView.Overlay) { t.setMaskView(if (state.maskView == MaskView.Overlay) null else MaskView.Overlay) }
        ToggleChip("Alone", state.maskView == MaskView.Alone) { t.setMaskView(if (state.maskView == MaskView.Alone) null else MaskView.Alone) }
    }
    OptionsSep()
    ToggleChip("Fingers paint", state.fingerPainting) { t.setFingerPainting(!state.fingerPainting) }
}

private fun formatSize(px: Double): String = if (px < 10) "%.1f".format(px) else px.roundToInt().toString()

@Composable
private fun PercentSlider(label: String, value: Double, min: Double, width: Int = 150, onChange: (Double) -> Unit) {
    SliderCell(
        label = label,
        valueText = "${(value * 100).roundToInt()}%",
        value = (value * 100).toFloat(),
        range = 0f..100f,
        onChange = { v -> onChange((v / 100.0).coerceIn(min, 1.0)) },
        onChangeFinished = {},
        onRequestNumericEntry = {},
        modifier = Modifier.width(width.dp),
    )
}

/** Stroke preview + preset name; tapping opens the preset list. */
@Composable
private fun BrushPresetPicker(state: EditorState, c: EditorController) {
    var open by remember { mutableStateOf(false) }
    val b = state.activeBrush
    val name = BrushPreset.entries.firstOrNull { it.settings == b }?.label ?: "Custom"
    Box {
        Row(
            Modifier
                .height(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PhoColors.Raised)
                .clickable(role = Role.DropdownList, onClickLabel = "Brush presets") { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StrokePreview(b, Modifier.width(64.dp).height(30.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(state.tool.label, style = PhoType.BodyMedium)
                Text(name, style = PhoType.Caption)
            }
            Spacer(Modifier.width(4.dp))
            Icon(Lucide.ChevronDown, null, tint = PhoColors.TextSecondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in BrushPreset.entries) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StrokePreview(p.settings, Modifier.width(56.dp).height(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(p.label, style = PhoType.Body.copy(color = if (p.settings == b) PhoColors.AccentHover else PhoColors.TextPrimary))
                        }
                    },
                    onClick = {
                        open = false
                        c.tools.applyPreset(p)
                    },
                )
            }
        }
    }
}

/** A small S-curve drawn with the brush's softness and opacity (a visual cue, not a render). */
@Composable
private fun StrokePreview(b: BrushSettings, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Brush preview" }) {
        val path = Path().apply {
            moveTo(size.width * 0.06f, size.height * 0.7f)
            cubicTo(size.width * 0.35f, size.height * 0.05f, size.width * 0.6f, size.height * 0.95f, size.width * 0.94f, size.height * 0.3f)
        }
        val w = size.height * 0.42f
        val alpha = (0.35f + 0.65f * b.opacity.toFloat())
        val layers = if (b.hardness >= 0.95) 1 else 4
        for (i in 0 until layers) {
            val f = 1f - i / layers.toFloat() * (1f - b.hardness.toFloat())
            drawPath(path, Color.White.copy(alpha = alpha / layers * (i + 1) / layers * 2f), style = Stroke(width = w * f, cap = StrokeCap.Round))
        }
    }
}

/** Round hardness control (reference: soft dot in a ring). Drag sideways to change; tap cycles. */
@Composable
private fun HardnessKnob(hardness: Double, onChange: (Double) -> Unit) {
    val current by rememberUpdatedState(hardness)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 10.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .border(1.dp, PhoColors.Divider, CircleShape)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dx -> onChange((current + dx / (120.dp.toPx())).coerceIn(0.0, 1.0)) }
                }
                .clickable(role = Role.Button, onClickLabel = "Change hardness") {
                    val steps = listOf(0.0, 0.5, 0.8, 1.0)
                    onChange(steps.firstOrNull { it > current + 1e-6 } ?: 0.0)
                }
                .semantics { stateDescription = "Hardness ${(hardness * 100).roundToInt()}%" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val r = size.minDimension / 2
                val inner = (hardness * 0.98).toFloat().coerceIn(0.01f, 0.98f)
                drawCircle(
                    Brush.radialGradient(
                        inner to Color.White, 1f to Color.White.copy(alpha = 0f),
                        center = Offset(r, r), radius = r,
                    ),
                    radius = r,
                )
            }
        }
        Text("Hardness ${(hardness * 100).roundToInt()}%", style = PhoType.Caption)
    }
}

/** Foreground/background swatches with swap and reset (also in the tool rail). */
@Composable
internal fun ColorSwatches(state: EditorState, c: EditorController, compact: Boolean = false) {
    val big = if (compact) 22.dp else 26.dp
    Box(Modifier.size(big * 1.6f).semantics { contentDescription = "Foreground and background colors" }) {
        Swatch(state.colors.background, big, Modifier.align(Alignment.BottomEnd), "Background color") {
            c.showDialog(EditorDialog.ColorPicker(ColorSlot.Background, state.colors.background))
        }
        Swatch(state.colors.foreground, big, Modifier.align(Alignment.TopStart), "Foreground color") {
            c.showDialog(EditorDialog.ColorPicker(ColorSlot.Foreground, state.colors.foreground))
        }
    }
    if (!compact) {
        PhoIconButton(Lucide.ArrowLeftRight, "Swap colors", { c.dispatch(CommandId.SwapColors) }, tooltip = "Swap colors (X)")
        PhoIconButton(Lucide.RefreshCw, "Default colors", { c.dispatch(CommandId.ResetColors) }, tooltip = "Black / white (D)")
    }
}

@Composable
internal fun Swatch(rgb: Int, size: androidx.compose.ui.unit.Dp, modifier: Modifier, label: String, onClick: () -> Unit) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF000000.toInt() or rgb))
            .border(1.5.dp, PhoColors.TextSecondary, RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClickLabel = "Edit $label", onClick = onClick)
            .semantics { contentDescription = "$label #%06X".format(rgb) },
    )
}

@Composable
internal fun EyedropperOptions(state: EditorState, c: EditorController) {
    ColorSwatches(state, c)
    OptionsSep()
    OptionLabel("Tap or drag to sample the visible image · Alt samples the background color")
}

@Composable
internal fun SelectionOptions(state: EditorState, c: EditorController) {
    OptionLabel("Mode")
    for (op in SelectionOp.entries) ToggleChip(op.label, state.selectionOp == op) { c.tools.setSelectionOp(op) }
    OptionsSep()
    TextAction("All") { c.dispatch(CommandId.SelectAll) }
    TextAction("Deselect", enabled = state.hasSelection) { c.dispatch(CommandId.Deselect) }
    TextAction("Inverse", enabled = state.hasSelection) { c.dispatch(CommandId.InverseSelection) }
    OptionsSep()
    TextAction("Mask from selection", enabled = state.hasSelection && c.availability(CommandId.AddMask).isEnabled) { c.dispatch(CommandId.AddMask) }
    val poly = state.selectionDraft as? SelectionDraft.Lasso
    if (state.tool == Tool.PolygonalLasso) {
        OptionsSep()
        if (poly != null && poly.polygonal) {
            TextAction("Close (${poly.points.size} pts)", enabled = poly.points.size >= 3) { c.tools.finishPolygon() }
            TextAction("Cancel") { c.tools.cancelSelectionDraft() }
        } else {
            OptionLabel("Tap to add points · double-tap, Enter or tap the first point to close")
        }
    } else {
        OptionsSep()
        OptionLabel("Shift adds · Alt subtracts")
    }
}

@Composable
internal fun CropOptions(state: EditorState, c: EditorController) {
    OptionLabel("Ratio")
    for (a in CropBox.Aspect.entries) ToggleChip(a.label, state.cropAspect == a) { c.tools.setCropAspect(a) }
    OptionsSep()
    state.crop?.let { crop ->
        val px = CropBox.toPixels(crop.rect)
        Column(Modifier.padding(horizontal = 6.dp)) {
            Text("Size", style = PhoType.Caption)
            Text("${px.width} × ${px.height} px", style = PhoType.Value)
        }
    }
    OptionsSep()
    PhoIconButton(Lucide.X, "Reset crop", { c.tools.cancelCrop() }, tooltip = "Reset (Esc)")
    PhoIconButton(Lucide.Check, "Apply crop", { c.tools.commitCrop() }, tooltip = "Apply crop (Enter or double-tap)")
    OptionsSep()
    OptionLabel("Non-destructive: cropped pixels stay in the layers")
}

@Composable
internal fun TransformOptions(state: EditorState, c: EditorController) {
    val layer = state.document?.activeLayer as? RasterLayer
    if (layer == null) {
        OptionLabel("Select a layer to transform")
        return
    }
    ToggleChip("Keep proportions", state.keepProportions) { c.tools.setKeepProportions(!state.keepProportions) }
    PhoIconButton(Lucide.TrianglesCenterlineDashedHorizontal, "Flip horizontal", { c.dispatch(CommandId.FlipLayerHorizontal) }, enabled = !layer.locked, tooltip = "Flip horizontal")
    PhoIconButton(Lucide.TrianglesCenterlineDashedVertical, "Flip vertical", { c.dispatch(CommandId.FlipLayerVertical) }, enabled = !layer.locked, tooltip = "Flip vertical")
    OptionsSep()
    val t = layer.transform
    val enabled = !layer.locked
    ValueChip("W", "${(kotlin.math.abs(t.scaleX) * 100).roundToInt()}%", enabled) {
        c.showDialog(EditorDialog.NumericEntry(NumericTarget.LayerScale(layer.id), kotlin.math.abs(t.scaleX) * 100))
    }
    ValueChip("H", "${(kotlin.math.abs(t.scaleY) * 100).roundToInt()}%", enabled) {
        c.showDialog(EditorDialog.NumericEntry(NumericTarget.LayerScale(layer.id), kotlin.math.abs(t.scaleY) * 100))
    }
    ValueChip("Angle", "${t.rotationDegrees.roundToInt()}°", enabled) {
        c.showDialog(EditorDialog.NumericEntry(NumericTarget.LayerRotation(layer.id), t.rotationDegrees))
    }
    PhoIconButton(Lucide.RotateCcw, "Reset transform", { c.dispatch(CommandId.ResetLayerTransform) },
        enabled = c.availability(CommandId.ResetLayerTransform).isEnabled, tooltip = "Reset scale and rotation")
    SnapOptions(state, c)
    OptionsSep()
    OptionLabel("Drag corners/edges to scale, outside a corner to rotate · Alt scales from the centre")
}
