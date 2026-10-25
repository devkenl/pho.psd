package app.phopsd.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.phopsd.BuildConfigInfo
import app.phopsd.editor.Availability
import app.phopsd.editor.CommandId
import app.phopsd.editor.EditorController
import app.phopsd.editor.EditorState
import app.phopsd.platform.AutosaveMeta
import app.phopsd.platform.EditorViewModel
import app.phopsd.ui.components.PhoButton
import app.phopsd.ui.editor.RecentRow
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.phopsd.ui.components.MilestoneBadge
import app.phopsd.ui.editor.NoticePill
import app.phopsd.ui.components.VDivider
import app.phopsd.ui.icons.Lucide
import app.phopsd.ui.icons.PhoWordmark
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoType

/**
 * Start screen: a restrained, desktop-like launcher with crash recovery and the real list
 * of projects saved or opened in this app.
 */
@Composable
fun HomeScreen(state: EditorState, vm: EditorViewModel) {
    val c = vm.controller
    val recents by vm.recents.items.collectAsStateWithLifecycle()
    val recovery by vm.recovery.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(380.dp)
                    .fillMaxHeight()
                    .background(PhoColors.Header)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 36.dp, vertical = 40.dp),
            ) {
                PhoWordmark(markSize = 40.dp, large = true)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Easy to learn. Difficult to master.\nNever artificially limited.",
                    style = PhoType.Label,
                )
                Spacer(Modifier.height(36.dp))
                HomeAction(Lucide.FilePlus, "New Project", "Blank canvas from presets or custom size", c, CommandId.NewDocument, primary = true)
                HomeAction(Lucide.FolderOpen, "Open Image", "JPEG, PNG or WebP from any storage provider", c, CommandId.OpenImage)
                HomeAction(Lucide.Image, "Open from Photos", "Use the system photo picker", c, CommandId.OpenFromPhotos)
                HomeAction(Lucide.Folder, "Open Project", ".phopsd projects", c, CommandId.OpenProject)
                Spacer(Modifier.height(48.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Version ${BuildConfigInfo.VERSION_NAME}", style = PhoType.Caption)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "Feature status",
                        style = PhoType.Caption.copy(color = PhoColors.AccentHover),
                        modifier = Modifier.clickable(role = Role.Button) { c.dispatch(CommandId.FeatureStatus) },
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "About",
                        style = PhoType.Caption.copy(color = PhoColors.AccentHover),
                        modifier = Modifier.clickable(role = Role.Button) { c.dispatch(CommandId.About) },
                    )
                }
            }
            VDivider()
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 40.dp, vertical = 40.dp),
            ) {
                recovery?.let { RecoveryCard(it, onRecover = vm::recoverAutosave, onDiscard = vm::discardAutosave) }
                Text("Recent projects", style = PhoType.Title)
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, PhoColors.Divider, RoundedCornerShape(12.dp)),
                    contentAlignment = if (recents.isEmpty()) Alignment.Center else Alignment.TopStart,
                ) {
                    if (recents.isEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
                            Icon(Lucide.Layers, null, tint = PhoColors.TextDisabled, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("No projects yet", style = PhoType.BodyMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Projects you save (File ▸ Save, .phopsd) or open in pho.psd appear here with " +
                                    "thumbnails, dimensions and dates.",
                                style = PhoType.Label,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                            for (r in recents) {
                                RecentRow(r, onOpen = { vm.openRecent(r.uri) }, onRemove = { vm.removeRecent(r.uri) })
                            }
                        }
                    }
                }
            }
        }
        // Notices (e.g. import errors, "arrives in M3" explanations) must be visible here too.
        NoticePill(state, c, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
        state.busy?.let { BusyOverlay(it) }
    }
}

@Composable
private fun RecoveryCard(meta: AutosaveMeta, onRecover: () -> Unit, onDiscard: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
            .clip(shape)
            .background(PhoColors.Raised)
            .border(1.dp, PhoColors.Warning.copy(alpha = 0.6f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.CircleAlert, null, tint = PhoColors.Warning, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Unsaved work from your last session", style = PhoType.BodyMedium)
            Text(
                "“${meta.documentName}” · ${meta.width} × ${meta.height} px · autosaved " +
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(java.util.Date(meta.savedAtMillis)),
                style = PhoType.Caption,
            )
        }
        Spacer(Modifier.width(12.dp))
        PhoButton("Discard", onDiscard)
        Spacer(Modifier.width(8.dp))
        PhoButton("Recover", onRecover, primary = true)
    }
}

@Composable
private fun HomeAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    c: EditorController,
    id: CommandId,
    primary: Boolean = false,
) {
    val availability = c.availability(id)
    val enabled = availability.isEnabled
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(shape)
            .background(if (primary) PhoColors.Accent else PhoColors.Raised)
            .clickable(role = Role.Button) { c.dispatch(id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (enabled) PhoColors.TextPrimary else PhoColors.TextDisabled
        Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = PhoType.BodyMedium.copy(color = fg))
            Text(
                subtitle,
                style = PhoType.Caption.copy(color = if (primary) PhoColors.TextPrimary.copy(alpha = 0.8f) else PhoColors.TextSecondary),
            )
        }
        if (availability is Availability.Planned) MilestoneBadge(availability.milestone)
    }
}

@Composable
fun BusyOverlay(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(PhoColors.AppBackground.copy(alpha = 0.6f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(PhoColors.Raised)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = PhoColors.Accent)
            Text(message, style = PhoType.Body)
        }
    }
}
