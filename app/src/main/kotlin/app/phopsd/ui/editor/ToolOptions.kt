package app.phopsd.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.phopsd.editor.CommandId
import app.phopsd.editor.EditorController
import app.phopsd.editor.EditorDialog
import app.phopsd.editor.EditorState
import app.phopsd.editor.NumericTarget
import app.phopsd.editor.Tool
import app.phopsd.editor.TwoFingerTarget
import app.phopsd.editor.isBrushLike
import app.phopsd.engine.geometry.AngleSnap
import app.phopsd.engine.geometry.Viewport
import app.phopsd.engine.model.RasterLayer
import app.phopsd.ui.components.PhoIconButton
import app.phopsd.ui.components.SliderCell
import app.phopsd.ui.components.VDivider
import app.phopsd.ui.icons.Lucide
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoType
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Floating contextual tool bar near the bottom of the canvas (reference: brush strip).
 * Its contents depend on the active tool; values update the document immediately and
 * slider drags collapse into a single undo step.
 */
@Composable
fun ToolOptionsBar(state: EditorState, c: EditorController, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(64.dp)
            .clip(shape)
            .background(PhoColors.Header.copy(alpha = 0.97f))
            .border(1.dp, PhoColors.Divider, shape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tool = state.effectiveTool
        // Brush tools show their own stroke preview + preset chip (reference brush strip).
        if (!tool.isBrushLike) {
            ToolChip(tool)
            OptionsSep()
        }
        when (tool) {
            Tool.Move -> MoveOptions(state, c)
            Tool.Hand -> ViewOptions(state, c)
            Tool.Zoom -> {
                ToggleChip("Zoom in", !state.zoomToolZoomsOut) { c.setZoomToolZoomsOut(false) }
                ToggleChip("Zoom out", state.zoomToolZoomsOut) { c.setZoomToolZoomsOut(true) }
                OptionsSep()
                ViewOptions(state, c)
            }
            Tool.Brush, Tool.Eraser, Tool.Mask -> BrushOptions(state, c)
            Tool.Eyedropper -> EyedropperOptions(state, c)
            Tool.RectMarquee, Tool.EllipticalMarquee, Tool.Lasso, Tool.PolygonalLasso -> SelectionOptions(state, c)
            Tool.Crop -> CropOptions(state, c)
            Tool.Transform -> TransformOptions(state, c)
            else -> Text(
                "${tool.label}: ${tool.availability.explanation ?: ""}",
                style = PhoType.Label,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        OptionsSep()
        PhoIconButton(Lucide.X, "Hide tool options", { c.setToolOptionsVisible(false) }, tooltip = "Hide tool options (Window ▸ Tool Options)")
    }
}

@Composable
private fun ToolChip(tool: Tool) {
    Row(
        Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PhoColors.Raised)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tool.icon(), null, tint = PhoColors.TextPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(tool.label, style = PhoType.BodyMedium)
    }
}

@Composable
private fun MoveOptions(state: EditorState, c: EditorController) {
    val doc = state.document ?: return
    val layer = doc.activeLayer as? RasterLayer
    ToggleChip("Auto-select layer", state.autoSelectLayer) { c.setAutoSelectLayer(!state.autoSelectLayer) }
    OptionsSep()
    OptionLabel("Two fingers")
    for (t in TwoFingerTarget.entries) {
        ToggleChip(t.label, state.twoFingerTarget == t) { c.setTwoFingerTarget(t) }
    }
    SnapOptions(state, c)
    OptionsSep()
    if (layer == null) {
        Text("Select a layer to move", style = PhoType.Label, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    val enabled = !layer.locked
    val t = layer.transform
    ValueChip("X", "${t.centerX.roundToInt()} px", enabled) {
        c.showDialog(EditorDialog.NumericEntry(NumericTarget.LayerX(layer.id), t.centerX))
    }
    ValueChip("Y", "${t.centerY.roundToInt()} px", enabled) {
        c.showDialog(EditorDialog.NumericEntry(NumericTarget.LayerY(layer.id), t.centerY))
    }
    OptionsSep()
    val scalePct = t.scaleX * 100
    SliderCell(
        label = "Scale",
        valueText = "${scalePct.roundToInt()}%",
        value = scalePct.toFloat().coerceIn(1f, 400f),
        range = 1f..400f,
        onChange = { v -> c.setLayerTransform(layer.id, t.copy(scaleX = v / 100.0, scaleY = v / 100.0), "scale") },
        onChangeFinished = { c.endContinuousEdit() },
        onRequestNumericEntry = { c.showDialog(EditorDialog.NumericEntry(NumericTarget.LayerScale(layer.id), scalePct)) },
        modifier = Modifier.width(170.dp),
        enabled = enabled,
    )
    SliderCell(
        label = "Rotation",
        valueText = "${t.rotationDegrees.roundToInt()}°",
        value = t.rotationDegrees.toFloat(),
        range = -180f..180f,
        onChange = { v -> c.setLayerTransform(layer.id, t.copy(rotationDegrees = v.toDouble()), "rotation") },
        onChangeFinished = { c.endContinuousEdit() },
        onRequestNumericEntry = { c.showDialog(EditorDialog.NumericEntry(NumericTarget.LayerRotation(layer.id), t.rotationDegrees)) },
        modifier = Modifier.width(170.dp),
        enabled = enabled,
    )
    SliderCell(
        label = "Opacity",
        valueText = "${(layer.opacity * 100).roundToInt()}%",
        value = layer.opacity * 100f,
        range = 0f..100f,
        onChange = { v -> c.setOpacity(layer.id, v / 100f) },
        onChangeFinished = { c.endContinuousEdit() },
        onRequestNumericEntry = { c.showDialog(EditorDialog.NumericEntry(NumericTarget.LayerOpacity(layer.id), layer.opacity * 100.0)) },
        modifier = Modifier.width(170.dp),
    )
    OptionsSep()
    PhoIconButton(
        Lucide.RotateCcw, "Reset transform", { c.dispatch(CommandId.ResetLayerTransform) },
        enabled = c.availability(CommandId.ResetLayerTransform).isEnabled,
        tooltip = "Reset scale and rotation",
    )
    if (layer.locked) {
        Spacer(Modifier.width(8.dp))
        Text("Layer is locked", style = PhoType.Caption.copy(color = PhoColors.Warning))
    }
}

/** Zoom slider works on a log scale so every zoom range gets equal travel. */
@Composable
private fun ViewOptions(state: EditorState, c: EditorController) {
    val vp = state.viewport ?: return
    val minL = ln(Viewport.MIN_ZOOM)
    val maxL = ln(Viewport.MAX_ZOOM)
    SliderCell(
        label = "Zoom",
        valueText = formatZoom(vp.zoom),
        value = ln(vp.zoom).toFloat(),
        range = minL.toFloat()..maxL.toFloat(),
        onChange = { v -> c.zoomAt(vp.viewWidth / 2, vp.viewHeight / 2, Math.E.pow(v.toDouble()) / c.state.value.viewport!!.zoom) },
        onChangeFinished = {},
        onRequestNumericEntry = { c.showDialog(EditorDialog.NumericEntry(NumericTarget.ViewZoom, vp.zoom * 100)) },
        modifier = Modifier.width(200.dp),
    )
    TextAction("Fit") { c.dispatch(CommandId.FitOnScreen) }
    TextAction("100%") { c.dispatch(CommandId.ActualPixels) }
    OptionsSep()
    PhoIconButton(Lucide.RotateCcw, "Rotate view −15°", { c.dispatch(CommandId.RotateViewLeft) })
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
        Text("View", style = PhoType.Caption)
        Text("${vp.rotationDegrees.roundToInt()}°", style = PhoType.Value)
    }
    PhoIconButton(Lucide.RotateCw, "Rotate view +15°", { c.dispatch(CommandId.RotateViewRight) })
    TextAction("Reset", enabled = vp.rotationDegrees != 0.0) { c.dispatch(CommandId.ResetViewRotation) }
    SnapOptions(state, c)
}

/** Magnetic snapping for two-finger rotation (view, and layer when the Move tool transforms it). */
@Composable
internal fun SnapOptions(state: EditorState, c: EditorController) {
    OptionsSep()
    OptionLabel("Rotation snap")
    val snap = state.rotationSnap
    ToggleChip("Off", !snap.enabled) { c.setRotationSnapStep(null) }
    for (step in AngleSnap.STEPS) {
        ToggleChip("${step.roundToInt()}°", snap.enabled && snap.stepDegrees == step) { c.setRotationSnapStep(step) }
    }
}

@Composable
internal fun OptionLabel(text: String) {
    Text(text, style = PhoType.Caption, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
}

@Composable
internal fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .padding(horizontal = 4.dp)
            .height(32.dp)
            .clip(shape)
            .background(if (on) PhoColors.AccentSoft else PhoColors.Raised)
            .border(1.dp, if (on) PhoColors.Accent else PhoColors.Divider, shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { stateDescription = if (on) "On" else "Off" }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) {
            Icon(Lucide.Check, null, tint = PhoColors.AccentHover, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = PhoType.Label.copy(color = PhoColors.TextPrimary))
    }
}

@Composable
internal fun ValueChip(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Edit $label", onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = PhoType.Caption)
        Text(value, style = PhoType.Value.copy(color = if (enabled) PhoColors.TextPrimary else PhoColors.TextDisabled))
    }
}

@Composable
internal fun TextAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = PhoType.BodyMedium.copy(color = if (enabled) PhoColors.TextPrimary else PhoColors.TextDisabled))
    }
}

@Composable
internal fun OptionsSep() {
    VDivider(Modifier.padding(horizontal = 8.dp, vertical = 14.dp).fillMaxHeight())
}
