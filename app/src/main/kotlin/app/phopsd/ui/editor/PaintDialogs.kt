package app.phopsd.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.phopsd.editor.ColorSlot
import app.phopsd.editor.EditorController
import app.phopsd.editor.EditorState
import app.phopsd.editor.FillContent
import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.ui.components.LabeledRow
import app.phopsd.ui.components.PhoButton
import app.phopsd.ui.components.PhoDialog
import app.phopsd.ui.components.PhoTextField
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoType
import kotlin.math.roundToInt

/** Image ▸ Image Size: proportional, non-destructive (layers rescale through their transforms). */
@Composable
internal fun ImageSizeDialog(width: Int, height: Int, c: EditorController) {
    var w by remember { mutableStateOf(width.toString()) }
    var h by remember { mutableStateOf(height.toString()) }
    var pct by remember { mutableStateOf("100") }
    val ratio = height.toDouble() / width
    val wv = w.toIntOrNull()
    val hv = h.toIntOrNull()
    val valid = wv != null && hv != null && wv in 1..NewDocumentSpec.MAX_EDGE && hv in 1..NewDocumentSpec.MAX_EDGE
    PhoDialog(
        title = "Image Size",
        onDismiss = c::dismissDialog,
        width = 440.dp,
        actions = {
            PhoButton("Cancel", c::dismissDialog)
            PhoButton("Apply", { if (valid) c.tools.imageSize(wv!!, hv!!) }, primary = true, enabled = valid)
        },
    ) {
        LabeledRow("Width") {
            PhoTextField(w, { v ->
                w = v.filter(Char::isDigit).take(5)
                w.toIntOrNull()?.let { h = (it * ratio).roundToInt().coerceAtLeast(1).toString(); pct = (it * 100.0 / width).roundToInt().toString() }
            }, Modifier.fillMaxWidth(), numeric = true, suffix = "px")
        }
        LabeledRow("Height") {
            PhoTextField(h, { v ->
                h = v.filter(Char::isDigit).take(5)
                h.toIntOrNull()?.let { w = (it / ratio).roundToInt().coerceAtLeast(1).toString(); pct = (it * 100.0 / height).roundToInt().toString() }
            }, Modifier.fillMaxWidth(), numeric = true, suffix = "px")
        }
        LabeledRow("Scale") {
            PhoTextField(pct, { v ->
                pct = v.filter(Char::isDigit).take(4)
                pct.toIntOrNull()?.takeIf { it > 0 }?.let {
                    w = (width * it / 100.0).roundToInt().coerceAtLeast(1).toString()
                    h = (height * it / 100.0).roundToInt().coerceAtLeast(1).toString()
                }
            }, Modifier.fillMaxWidth(), numeric = true, suffix = "%")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Proportions are kept. Layers are rescaled through their transforms, so original pixels are preserved " +
                "and resampled only when displayed or exported. Currently $width × $height px.",
            style = PhoType.Caption,
        )
    }
}

/** Image ▸ Canvas Size with a 3×3 anchor. Shrinking hides pixels; it never deletes them. */
@Composable
internal fun CanvasSizeDialog(width: Int, height: Int, c: EditorController) {
    var w by remember { mutableStateOf(width.toString()) }
    var h by remember { mutableStateOf(height.toString()) }
    var anchor by remember { mutableStateOf(4) }
    val wv = w.toIntOrNull()
    val hv = h.toIntOrNull()
    val valid = wv != null && hv != null && wv in 1..NewDocumentSpec.MAX_EDGE && hv in 1..NewDocumentSpec.MAX_EDGE
    PhoDialog(
        title = "Canvas Size",
        onDismiss = c::dismissDialog,
        width = 460.dp,
        actions = {
            PhoButton("Cancel", c::dismissDialog)
            PhoButton("Apply", { if (valid) c.tools.canvasSize(wv!!, hv!!, (anchor % 3) / 2.0, (anchor / 3) / 2.0) }, primary = true, enabled = valid)
        },
    ) {
        LabeledRow("Width") { PhoTextField(w, { w = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), numeric = true, suffix = "px") }
        LabeledRow("Height") { PhoTextField(h, { h = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), numeric = true, suffix = "px") }
        LabeledRow("Anchor") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (col in 0 until 3) {
                        val i = row * 3 + col
                        val shape = RoundedCornerShape(4.dp)
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(shape)
                                .background(if (anchor == i) PhoColors.Accent else PhoColors.Dock)
                                .border(1.dp, PhoColors.Divider, shape)
                                .clickable(role = Role.RadioButton, onClickLabel = "Anchor ${anchorName(i)}") { anchor = i },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Layers keep all their pixels; anything outside the new canvas is hidden, not deleted.", style = PhoType.Caption)
    }
}

private fun anchorName(i: Int): String =
    listOf("top", "middle", "bottom")[i / 3] + " " + listOf("left", "centre", "right")[i % 3]

@Composable
internal fun FillDialog(hasSelection: Boolean, state: EditorState, c: EditorController) {
    var content by remember { mutableStateOf(FillContent.Foreground) }
    PhoDialog(
        title = "Fill",
        onDismiss = c::dismissDialog,
        width = 460.dp,
        actions = {
            PhoButton("Cancel", c::dismissDialog)
            PhoButton("Fill", { c.tools.fill(content) }, primary = true)
        },
    ) {
        Text("Contents", style = PhoType.Label)
        Spacer(Modifier.height(6.dp))
        for (rowItems in FillContent.entries.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                for (f in rowItems) Chip(f.label, content == f) { content = f }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF000000.toInt() or content.rgb(state.colors))).border(1.dp, PhoColors.Divider, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(10.dp))
            Text(
                (if (hasSelection) "Fills the selection" else "Fills the whole layer") +
                    if (state.paintsMask) " of the layer mask (as gray)." else ".",
                style = PhoType.Caption,
            )
        }
    }
}

@Composable
internal fun ConfirmFlattenDialog(hidden: Int, c: EditorController) {
    PhoDialog(
        title = "Flatten image?",
        onDismiss = c::dismissDialog,
        width = 500.dp,
        actions = {
            PhoButton("Cancel", c::dismissDialog)
            PhoButton("Flatten", { c.tools.flatten() }, primary = true)
        },
    ) {
        Text(
            "All layers are merged into a single layer; masks, blend modes and opacity are baked in, and pixels outside " +
                "the canvas are dropped." + (if (hidden > 0) " $hidden hidden layer${if (hidden == 1) " is" else "s are"} discarded." else "") +
                " You can undo this.",
            style = PhoType.Body,
        )
    }
}

/** HSV colour picker: saturation/value square, hue strip, hex entry; shows old vs new. */
@Composable
internal fun ColorPickerDialog(slot: ColorSlot, current: Int, c: EditorController) {
    val start = remember(current) { rgbToHsv(current) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val rgb = hsvToRgb(hue, sat, value)
    var hex by remember { mutableStateOf("%06X".format(current)) }
    fun setFromHsv(h: Float, s: Float, v: Float) {
        hue = h; sat = s; value = v
        hex = "%06X".format(hsvToRgb(h, s, v))
    }
    fun apply() {
        c.tools.setColor(slot, rgb)
        c.dismissDialog()
    }
    PhoDialog(
        title = if (slot == ColorSlot.Foreground) "Foreground Color" else "Background Color",
        onDismiss = c::dismissDialog,
        width = 460.dp,
        actions = {
            PhoButton("Cancel", c::dismissDialog)
            PhoButton("OK", { apply() }, primary = true)
        },
    ) {
        Row {
            // Saturation (x) / value (y) square.
            Canvas(
                Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .semantics { contentDescription = "Saturation and brightness" }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun pick(o: Offset) = setFromHsv(hue, (o.x / size.width).coerceIn(0f, 1f), 1f - (o.y / size.height).coerceIn(0f, 1f))
                            pick(down.position)
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                pick(ch.position)
                                ch.consume()
                            }
                        }
                    },
            ) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color(0xFF000000.toInt() or hsvToRgb(hue, 1f, 1f)))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val p = Offset(sat * size.width, (1f - value) * size.height)
                drawCircle(Color.Black, radius = 8.dp.toPx(), center = p, style = Stroke(width = 3f))
                drawCircle(Color.White, radius = 7.dp.toPx(), center = p, style = Stroke(width = 2f))
            }
            Spacer(Modifier.width(12.dp))
            // Hue strip.
            Canvas(
                Modifier
                    .width(28.dp)
                    .height(240.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .semantics { contentDescription = "Hue" }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun pick(o: Offset) = setFromHsv((o.y / size.height).coerceIn(0f, 1f) * 360f, sat, value)
                            pick(down.position)
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                pick(ch.position)
                                ch.consume()
                            }
                        }
                    },
            ) {
                drawRect(Brush.verticalGradient((0..6).map { Color(0xFF000000.toInt() or hsvToRgb(it * 60f, 1f, 1f)) }))
                val y = hue / 360f * size.height
                drawRect(Color.White, topLeft = Offset(0f, y - 2f), size = androidx.compose.ui.geometry.Size(size.width, 4f), style = Stroke(width = 2f))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("New", style = PhoType.Caption)
                Box(Modifier.size(64.dp, 36.dp).background(Color(0xFF000000.toInt() or rgb)))
                Box(Modifier.size(64.dp, 36.dp).background(Color(0xFF000000.toInt() or current)).clickable(onClickLabel = "Restore") {
                    val o = rgbToHsv(current); setFromHsv(o[0], o[1], o[2])
                })
                Text("Current", style = PhoType.Caption)
                Spacer(Modifier.height(12.dp))
                Text("#", style = PhoType.Label)
                PhoTextField(hex, { v ->
                    hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    if (hex.length == 6) {
                        val o = rgbToHsv(hex.toInt(16)); hue = o[0]; sat = o[1]; value = o[2]
                    }
                }, Modifier.width(110.dp), onDone = { apply() })
                Spacer(Modifier.height(8.dp))
                Text("R ${(rgb shr 16) and 0xFF}  G ${(rgb shr 8) and 0xFF}  B ${rgb and 0xFF}", style = PhoType.Caption)
            }
        }
    }
}

private fun rgbToHsv(rgb: Int): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(0xFF000000.toInt() or rgb, hsv)
    hsv[0] = hsv[0].coerceIn(0f, 360f)
    return hsv
}

private fun hsvToRgb(h: Float, s: Float, v: Float): Int =
    android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 359.99f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))) and 0xFFFFFF
