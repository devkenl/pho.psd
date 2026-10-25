package app.phopsd.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.phopsd.editor.CommandId
import app.phopsd.editor.EditorController
import app.phopsd.editor.EditorDialog
import app.phopsd.editor.EditorState
import app.phopsd.editor.MenuEntry
import app.phopsd.editor.Menus
import app.phopsd.editor.QuickCategory
import app.phopsd.ui.components.CommandMenuItem
import app.phopsd.ui.components.HDivider
import app.phopsd.ui.components.MenuSectionHeader
import app.phopsd.ui.components.PhoIconButton
import app.phopsd.ui.components.Tooltipped
import app.phopsd.ui.components.VDivider
import app.phopsd.ui.icons.Lucide
import app.phopsd.ui.icons.PhoMark
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoDimens
import app.phopsd.ui.theme.PhoType
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ header

@Composable
fun EditorHeader(state: EditorState, c: EditorController) {
    val doc = state.document ?: return
    var docMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(PhoDimens.HeaderHeight)
            .background(PhoColors.Header)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoIconButton(Lucide.ChevronLeft, "Back to projects", { c.dispatch(CommandId.CloseDocument) })
        Spacer(Modifier.width(6.dp))
        Tooltipped("pho.psd") { PhoMark(30.dp) }
        Spacer(Modifier.width(12.dp))
        Box {
            Column(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClickLabel = "Document options") { docMenu = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        doc.name,
                        style = PhoType.Title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Lucide.ChevronDown, null, tint = PhoColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.dirty) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(PhoColors.Warning))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(saveStateText(state.dirty, state.lastEditAtMillis, now), style = PhoType.Caption)
                }
            }
            DropdownMenu(expanded = docMenu, onDismissRequest = { docMenu = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Rename…", style = PhoType.Body) },
                    onClick = { docMenu = false; c.showDialog(EditorDialog.RenameDocument(doc.name)) },
                )
                CommandMenuItem(CommandId.DocumentInfo, c.availability(CommandId.DocumentInfo), null) {
                    docMenu = false; c.dispatch(CommandId.DocumentInfo)
                }
                CommandMenuItem(CommandId.Save, c.availability(CommandId.Save), null) {
                    docMenu = false; c.dispatch(CommandId.Save)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val undo = c.availability(CommandId.Undo)
        val redo = c.availability(CommandId.Redo)
        PhoIconButton(
            Lucide.Undo2, "Undo", { c.dispatch(CommandId.Undo) },
            enabled = undo.isEnabled,
            tooltip = state.history?.undoLabel?.let { "Undo $it (Ctrl+Z)" } ?: "Nothing to undo",
        )
        PhoIconButton(
            Lucide.Redo2, "Redo", { c.dispatch(CommandId.Redo) },
            enabled = redo.isEnabled,
            tooltip = state.history?.redoLabel?.let { "Redo $it (Shift+Ctrl+Z)" } ?: "Nothing to redo",
        )
        Spacer(Modifier.width(4.dp))
        PhoIconButton(
            Lucide.Save, "Save", { c.dispatch(CommandId.Save) },
            enabled = false,
            tooltip = "Save — ${c.availability(CommandId.Save).explanation}",
        )
        Spacer(Modifier.width(10.dp))
        ExportButton { c.dispatch(CommandId.Export) }
        Spacer(Modifier.width(6.dp))
        PhoIconButton(Lucide.Settings, "Preferences", { c.dispatch(CommandId.Preferences) }, enabled = false,
            tooltip = "Preferences — ${c.availability(CommandId.Preferences).explanation}")
        Box {
            PhoIconButton(Lucide.EllipsisVertical, "More", { moreMenu = true })
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                for (id in listOf(CommandId.FeatureStatus, CommandId.Shortcuts, CommandId.Diagnostics, CommandId.About)) {
                    CommandMenuItem(id, c.availability(id), null) { moreMenu = false; c.dispatch(id) }
                }
            }
        }
    }
}

private fun saveStateText(dirty: Boolean, lastEdit: Long?, now: Long): String {
    if (!dirty || lastEdit == null) return "Not saved · no changes"
    val minutes = ((now - lastEdit) / 60_000).coerceAtLeast(0)
    val ago = when (minutes) {
        0L -> "just now"
        1L -> "1 min ago"
        else -> "$minutes min ago"
    }
    return "Unsaved changes · edited $ago"
}

/** Export is the primary action in the reference; shown honestly as not yet available. */
@Composable
private fun ExportButton(onClick: () -> Unit) {
    Tooltipped("Export — PNG/JPEG export arrives with project persistence (M3)") {
        Row(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, PhoColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .background(PhoColors.Accent.copy(alpha = 0.12f))
                .clickable(role = Role.Button, onClickLabel = "Export (not available yet)", onClick = onClick)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Upload, null, tint = PhoColors.TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Export", style = PhoType.BodyMedium.copy(color = PhoColors.TextSecondary))
        }
    }
}

// ------------------------------------------------------------------ menu bar

@Composable
fun MenuBar(state: EditorState, c: EditorController) {
    var open by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(PhoDimens.MenuBarHeight)
            .background(PhoColors.Menu)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (menu in Menus.bar) {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            // Desktop behaviour: while one menu is open, hovering another title switches to it.
            LaunchedEffect(hovered) {
                if (hovered && open != null && open != menu.title) open = menu.title
            }
            Box {
                Text(
                    menu.title,
                    style = PhoType.Body.copy(color = if (open == menu.title) PhoColors.TextPrimary else PhoColors.TextSecondary),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (open == menu.title) PhoColors.Raised else Color.Transparent)
                        .hoverable(interaction)
                        .clickable(role = Role.Button) { open = if (open == menu.title) null else menu.title }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                DropdownMenu(expanded = open == menu.title, onDismissRequest = { if (open == menu.title) open = null }) {
                    for (entry in menu.entries) {
                        when (entry) {
                            is MenuEntry.Item -> CommandMenuItem(entry.id, c.availability(entry.id), c.isChecked(entry.id)) {
                                open = null
                                c.dispatch(entry.id)
                            }
                            is MenuEntry.Header -> MenuSectionHeader(entry.title)
                            MenuEntry.Separator -> HDivider(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ quick strip

private fun QuickCategory.icon(): ImageVector = when (this) {
    QuickCategory.Edit -> Lucide.SlidersHorizontal
    QuickCategory.Adjust -> Lucide.Sun
    QuickCategory.Fx -> Lucide.Sparkles
    QuickCategory.Filters -> Lucide.Blend
    QuickCategory.Layer -> Lucide.Layers
    QuickCategory.Select -> Lucide.SquareDashed
}

/**
 * Quick workspace categories from the reference. Each opens a compact command popover
 * routed through the same command system as the menus (not a second, divergent UI).
 */
@Composable
fun QuickStrip(
    state: EditorState,
    c: EditorController,
    openCategory: QuickCategory?,
    onOpenCategory: (QuickCategory?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PhoDimens.QuickStripHeight + 12.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (cat in QuickCategory.entries) {
            val selected = openCategory == cat
            val enabledCount = cat.commands.count { c.availability(it).isEnabled }
            val shape = RoundedCornerShape(8.dp)
            Box {
                Tooltipped(if (enabledCount == 0) "${cat.label}: nothing available in this build yet" else null) {
                    Row(
                        Modifier
                            .widthIn(min = 116.dp)
                            .fillMaxHeight()
                            .clip(shape)
                            .background(if (selected) PhoColors.AccentSoft else PhoColors.Rail)
                            .border(1.dp, if (selected) PhoColors.Accent else PhoColors.Divider, shape)
                            .clickable(role = Role.Button) { onOpenCategory(if (selected) null else cat) }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val tint = if (enabledCount > 0) PhoColors.TextPrimary else PhoColors.TextSecondary
                        Icon(cat.icon(), null, tint = tint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(cat.label, style = PhoType.Body.copy(color = tint))
                    }
                }
                DropdownMenu(expanded = selected, onDismissRequest = { onOpenCategory(null) }) {
                    MenuSectionHeader(cat.label)
                    for (id in cat.commands) {
                        CommandMenuItem(id, c.availability(id), null) {
                            onOpenCategory(null)
                            c.dispatch(id)
                        }
                    }
                }
            }
        }
    }
}

/** Transient status message floating over the canvas. */
@Composable
fun NoticePill(state: EditorState, c: EditorController, modifier: Modifier = Modifier) {
    val n = state.notice ?: return
    val accent = when (n.kind) {
        app.phopsd.editor.NoticeKind.Info -> PhoColors.Accent
        app.phopsd.editor.NoticeKind.Warning -> PhoColors.Warning
        app.phopsd.editor.NoticeKind.Error -> PhoColors.Danger
    }
    Row(
        modifier
            .widthIn(max = 640.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PhoColors.Raised)
            .border(1.dp, PhoColors.Divider, RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Dismiss") { c.dismissNotice(n.id) }
            .padding(start = 0.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(34.dp).background(accent))
        Spacer(Modifier.width(12.dp))
        Text(n.text, style = PhoType.Body, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
fun StatusBar(state: EditorState, c: EditorController) {
    val doc = state.document ?: return
    val vp = state.viewport
    Row(
        Modifier
            .fillMaxWidth()
            .height(PhoDimens.StatusBarHeight)
            .background(PhoColors.Menu)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val zoomText = vp?.let { formatZoom(it.zoom) } ?: "—"
        Text(
            zoomText,
            style = PhoType.Caption.copy(color = PhoColors.TextPrimary),
            modifier = Modifier.clickable(onClickLabel = "Set zoom") {
                vp?.let { c.showDialog(EditorDialog.NumericEntry(app.phopsd.editor.NumericTarget.ViewZoom, it.zoom * 100)) }
            },
        )
        StatusSep()
        Text("${doc.width} × ${doc.height} px · ${doc.dpi.toInt()} ppi", style = PhoType.Caption)
        StatusSep()
        Text(doc.colorProfile.label, style = PhoType.Caption)
        StatusSep()
        Text("${doc.layers.size} layer${if (doc.layers.size == 1) "" else "s"}", style = PhoType.Caption)
        if (vp != null && vp.rotationDegrees != 0.0) {
            StatusSep()
            Text("View rotated ${vp.rotationDegrees.toInt()}°", style = PhoType.Caption)
        }
        Spacer(Modifier.weight(1f))
        Text("Tool: ${state.effectiveTool.label}", style = PhoType.Caption)
        StatusSep()
        Text(
            if (state.dirty) "Unsaved · project saving arrives in M3" else "Not saved",
            style = PhoType.Caption.copy(color = if (state.dirty) PhoColors.Warning else PhoColors.TextSecondary),
        )
    }
}

@Composable
private fun StatusSep() {
    VDivider(Modifier.height(12.dp))
}

fun formatZoom(zoom: Double): String {
    val pct = zoom * 100
    return when {
        pct >= 100 -> "${pct.toInt()}%"
        pct >= 10 -> String.format(java.util.Locale.US, "%.1f%%", pct)
        else -> String.format(java.util.Locale.US, "%.2f%%", pct)
    }
}
