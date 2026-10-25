package app.phopsd.ui.editor

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.phopsd.BuildConfigInfo
import app.phopsd.editor.EditorController
import app.phopsd.editor.EditorDialog
import app.phopsd.editor.EditorState
import app.phopsd.editor.FeatureStatusList
import app.phopsd.editor.NumericTarget
import app.phopsd.editor.PendingAction
import app.phopsd.editor.ShortcutList
import app.phopsd.editor.Status
import app.phopsd.engine.model.BackgroundContents
import app.phopsd.engine.model.DocumentPresets
import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.engine.model.RasterLayer
import app.phopsd.platform.EditorViewModel
import app.phopsd.platform.ImageImporter
import app.phopsd.ui.components.HDivider
import app.phopsd.ui.components.InfoLine
import app.phopsd.ui.components.LabeledRow
import app.phopsd.ui.components.PhoButton
import app.phopsd.ui.components.PhoDialog
import app.phopsd.ui.components.PhoTextField
import app.phopsd.ui.icons.PhoWordmark
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoType
import java.util.Locale
import kotlin.math.roundToInt

/** Renders whichever dialog the editor state requests. */
@Composable
fun EditorDialogs(state: EditorState, vm: EditorViewModel) {
    val c = vm.controller
    when (val d = state.dialog) {
        null -> {}
        EditorDialog.NewDocument -> NewDocumentDialog(c)
        is EditorDialog.RenameLayer -> TextEntryDialog("Rename Layer", d.current, onDismiss = c::dismissDialog) { c.renameLayer(d.id, it) }
        is EditorDialog.RenameDocument -> TextEntryDialog("Rename Document", d.current, onDismiss = c::dismissDialog) { c.renameDocument(it) }
        is EditorDialog.NumericEntry -> NumericEntryDialog(d.target, d.current, c)
        is EditorDialog.ConfirmDiscard -> ConfirmDiscardDialog(state, d.action, c)
        EditorDialog.DocumentInfo -> DocumentInfoDialog(state, c)
        EditorDialog.About -> AboutDialog(c)
        EditorDialog.Shortcuts -> ShortcutsDialog(c)
        EditorDialog.FeatureStatus -> FeatureStatusDialog(c)
        EditorDialog.Diagnostics -> DiagnosticsDialog(state, c)
    }
}

@Composable
private fun NewDocumentDialog(c: EditorController) {
    var name by remember { mutableStateOf("Untitled") }
    var width by remember { mutableStateOf("3000") }
    var height by remember { mutableStateOf("2000") }
    var ppi by remember { mutableStateOf("300") }
    var background by remember { mutableStateOf(BackgroundContents.White) }
    val spec = NewDocumentSpec(
        name = name,
        width = width.trim().toIntOrNull() ?: 0,
        height = height.trim().toIntOrNull() ?: 0,
        dpi = ppi.trim().toDoubleOrNull() ?: 0.0,
        background = background,
    )
    val error = spec.validate()

    PhoDialog(
        title = "New Document",
        onDismiss = c::dismissDialog,
        width = 560.dp,
        actions = {
            PhoButton("Cancel", c::dismissDialog)
            PhoButton("Create", { if (error == null) c.createDocument(spec) }, primary = true, enabled = error == null)
        },
    ) {
        LabeledRow("Name") { PhoTextField(name, { name = it }, Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(6.dp))
        Text("Presets", style = PhoType.Label)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DocumentPresets.all.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in row) {
                        val selected = width == p.width.toString() && height == p.height.toString()
                        Chip(p.label, selected, Modifier.weight(1f)) {
                            width = p.width.toString(); height = p.height.toString(); ppi = p.dpi.roundToInt().toString()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LabeledRow("Width") { PhoTextField(width, { width = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), numeric = true, suffix = "px") }
        LabeledRow("Height") { PhoTextField(height, { height = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), numeric = true, suffix = "px") }
        LabeledRow("Orientation") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val w = width.toIntOrNull() ?: 0
                val h = height.toIntOrNull() ?: 0
                Chip("Landscape", w >= h) { if (w < h) { width = h.toString(); height = w.toString() } }
                Chip("Portrait", h > w) { if (w > h) { width = h.toString(); height = w.toString() } }
            }
        }
        LabeledRow("Resolution") { PhoTextField(ppi, { ppi = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth(), numeric = true, suffix = "ppi") }
        LabeledRow("Background") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in BackgroundContents.entries) Chip(b.label, background == b) { background = b }
            }
        }
        LabeledRow("Colour mode") {
            Text("RGB · 8 bits/channel · sRGB IEC61966-2.1  (16-bit and other profiles: M8)", style = PhoType.Label)
        }
        Spacer(Modifier.height(6.dp))
        val mp = spec.width.toLong() * spec.height / 1_000_000.0
        Text(
            error ?: String.format(Locale.US, "%.1f megapixels. Solid backgrounds use no pixel memory until painted.", mp),
            style = PhoType.Caption.copy(color = if (error != null) PhoColors.Danger else PhoColors.TextSecondary),
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) PhoColors.AccentSoft else PhoColors.Dock)
            .border(1.dp, if (selected) PhoColors.Accent else PhoColors.Divider, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = PhoType.Label.copy(color = PhoColors.TextPrimary), maxLines = 1)
    }
}

@Composable
private fun TextEntryDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Boolean) {
    var text by remember(initial) { mutableStateOf(initial) }
    PhoDialog(
        title = title,
        onDismiss = onDismiss,
        width = 420.dp,
        actions = {
            PhoButton("Cancel", onDismiss)
            PhoButton("Rename", { onConfirm(text) }, primary = true, enabled = text.isNotBlank())
        },
    ) {
        PhoTextField(text, { text = it }, Modifier.fillMaxWidth(), onDone = { onConfirm(text) })
    }
}

@Composable
private fun NumericEntryDialog(target: NumericTarget, current: Double, c: EditorController) {
    var text by remember(target) { mutableStateOf(formatNumber(current)) }
    val value = text.replace(',', '.').toDoubleOrNull()
    val valid = value != null && value in target.min..target.max
    PhoDialog(
        title = target.title,
        onDismiss = c::dismissDialog,
        width = 360.dp,
        actions = {
            PhoButton("Cancel", c::dismissDialog)
            PhoButton("Apply", { if (valid) c.applyNumeric(target, value!!) }, primary = true, enabled = valid)
        },
    ) {
        PhoTextField(
            text, { text = it }, Modifier.fillMaxWidth(), numeric = true, suffix = target.unit,
            onDone = { if (valid) c.applyNumeric(target, value!!) },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Range ${formatNumber(target.min)} – ${formatNumber(target.max)} ${target.unit}",
            style = PhoType.Caption.copy(color = if (valid || text.isBlank()) PhoColors.TextSecondary else PhoColors.Danger),
        )
    }
}

private fun formatNumber(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format(Locale.US, "%.2f", v)

@Composable
private fun ConfirmDiscardDialog(state: EditorState, action: PendingAction, c: EditorController) {
    val name = state.document?.name ?: "this document"
    val verb = when (action) {
        PendingAction.Close -> "Close"
        is PendingAction.Pick -> "Open another image"
        PendingAction.NewDocument -> "Create a new document"
    }
    PhoDialog(
        title = "Discard “$name”?",
        onDismiss = c::dismissDialog,
        actions = {
            PhoButton("Keep editing", c::dismissDialog)
            PhoButton("Discard and continue", { c.confirmDiscard(action) }, primary = true)
        },
    ) {
        Text(
            "$verb will discard “$name”. This build cannot save projects yet (native .phopsd saving arrives " +
                "in Milestone 3), so the layers and history of this document will be lost. Your original image " +
                "files are never modified.",
            style = PhoType.Body,
        )
    }
}

@Composable
private fun DocumentInfoDialog(state: EditorState, c: EditorController) {
    val doc = state.document ?: return
    val stats = c.stats()
    PhoDialog(
        title = "Document Info",
        onDismiss = c::dismissDialog,
        width = 560.dp,
        actions = { PhoButton("Close", c::dismissDialog, primary = true) },
    ) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            InfoLine("Name", doc.name)
            InfoLine("Pixel size", "${doc.width} × ${doc.height} px (${String.format(Locale.US, "%.1f", doc.width.toLong() * doc.height / 1e6)} MP)")
            InfoLine("Resolution", "${doc.dpi.roundToInt()} ppi · print ${String.format(Locale.US, "%.1f × %.1f cm", doc.width / doc.dpi * 2.54, doc.height / doc.dpi * 2.54)}")
            InfoLine("Colour", doc.colorProfile.label)
            InfoLine("Layers", doc.layers.size.toString())
            stats?.let {
                InfoLine("Pixel memory", formatBytes(it.rasterBytes) + " in ${it.rasterCount} rasters (incl. previews/undo)")
                InfoLine("History", "${it.historyEntries} states")
            }
            InfoLine("Saved", "No — project saving arrives in M3")
            val sources = doc.layers.mapNotNull { l -> (l as? RasterLayer)?.source?.let { l.name to it } }
            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HDivider()
                Spacer(Modifier.height(8.dp))
                Text("Imported sources", style = PhoType.BodyMedium)
                for ((layerName, s) in sources) {
                    Spacer(Modifier.height(6.dp))
                    Text("$layerName — ${s.displayName}", style = PhoType.Body)
                    Text(
                        buildString {
                            append("${s.originalWidth} × ${s.originalHeight} px")
                            s.mimeType?.let { append(" · ").append(it) }
                            s.sourceColorSpace?.let { append(" · source colour space: ").append(it).append(" → converted to sRGB") }
                            if (s.downsampled) append(" · downsampled on import (memory budget)")
                        },
                        style = PhoType.Caption.copy(color = if (s.downsampled) PhoColors.Warning else PhoColors.TextSecondary),
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(c: EditorController) {
    PhoDialog(title = "About", onDismiss = c::dismissDialog, actions = { PhoButton("Close", c::dismissDialog, primary = true) }) {
        PhoWordmark(markSize = 34.dp, large = true)
        Spacer(Modifier.height(12.dp))
        InfoLine("Version", BuildConfigInfo.VERSION_NAME)
        InfoLine("Scope", BuildConfigInfo.MILESTONE)
        Spacer(Modifier.height(8.dp))
        Text(
            "A professional, offline image editor for Android tablets. pho.psd is an independent project and is " +
                "not affiliated with Adobe. Interface icons: Lucide (ISC licence; Feather-derived icons under MIT).",
            style = PhoType.Label,
        )
    }
}

@Composable
private fun ShortcutsDialog(c: EditorController) {
    PhoDialog(title = "Keyboard Shortcuts", onDismiss = c::dismissDialog, width = 560.dp, actions = { PhoButton("Close", c::dismissDialog, primary = true) }) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            for ((keys, action) in ShortcutList.entries) InfoLine(keys, action)
            Spacer(Modifier.height(8.dp))
            Text("Menu items list further shortcuts; those marked with a milestone badge are not available yet.", style = PhoType.Caption)
        }
    }
}

@Composable
private fun FeatureStatusDialog(c: EditorController) {
    PhoDialog(title = "Feature Status", onDismiss = c::dismissDialog, width = 640.dp, actions = { PhoButton("Close", c::dismissDialog, primary = true) }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            for (e in FeatureStatusList.entries) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    val color = when (e.status) {
                        Status.Done -> PhoColors.Success
                        Status.Partial -> PhoColors.Warning
                        Status.Planned -> PhoColors.TextDisabled
                    }
                    Box(Modifier.padding(top = 5.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.area, style = PhoType.Body)
                        if (e.note.isNotEmpty()) Text(e.note, style = PhoType.Caption)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(e.status.label, style = PhoType.Caption.copy(color = color))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsDialog(state: EditorState, c: EditorController) {
    val rt = Runtime.getRuntime()
    val stats = c.stats()
    PhoDialog(title = "Diagnostics", onDismiss = c::dismissDialog, width = 560.dp, actions = { PhoButton("Close", c::dismissDialog, primary = true) }) {
        InfoLine("App", "pho.psd ${BuildConfigInfo.VERSION_NAME}")
        InfoLine("Device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        InfoLine("CPU cores", rt.availableProcessors().toString())
        InfoLine("Java heap", "${formatBytes(rt.totalMemory() - rt.freeMemory())} used of ${formatBytes(rt.maxMemory())} max")
        InfoLine("Import budget", formatBytes(ImageImporter.heapBudgetBytes()) + " free for new rasters")
        stats?.let {
            InfoLine("Document pixels", formatBytes(it.rasterBytes) + " in ${it.rasterCount} rasters")
            InfoLine("Layers / history", "${it.layerCount} / ${it.historyEntries}")
        }
        state.viewport?.let { InfoLine("Viewport", "${formatZoom(it.zoom)} · ${it.rotationDegrees.roundToInt()}° · ${it.viewWidth.roundToInt()}×${it.viewHeight.roundToInt()} px") }
        InfoLine("Renderer", "CPU reference compositor (premultiplied float), parallel row bands")
        Spacer(Modifier.height(6.dp))
        Text("No performance figures are claimed until measured on target devices (Milestone 7).", style = PhoType.Caption)
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
    b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}
