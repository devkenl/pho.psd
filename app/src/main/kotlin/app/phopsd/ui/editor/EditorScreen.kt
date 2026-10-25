package app.phopsd.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.phopsd.editor.EditorState
import app.phopsd.editor.QuickCategory
import app.phopsd.platform.EditorViewModel
import app.phopsd.ui.components.HDivider
import app.phopsd.ui.components.VDivider
import app.phopsd.ui.home.BusyOverlay
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoDimens

/**
 * Editor workspace, following docs/UI_DESIGN.md and design-reference.png:
 *
 * ```
 * header ─────────────────────────────────────────────
 * menu bar (File Edit Image Layer Select Filter View Window Help)
 * rail │ quick strip (Edit Adjust FX Filters Layer Select) │ dock
 *      │ canvas                                            │ (Layers|Channels|Paths,
 *      │   floating contextual tool options                │  History)
 * status bar ─────────────────────────────────────────
 * ```
 */
@Composable
fun EditorScreen(state: EditorState, vm: EditorViewModel) {
    val c = vm.controller
    val base by vm.basePreview.collectAsStateWithLifecycle()
    val detail by vm.detailPreview.collectAsStateWithLifecycle()
    val thumbs by vm.layerThumbnails.collectAsStateWithLifecycle()
    val channels by vm.channelThumbnails.collectAsStateWithLifecycle()
    var openCategory by remember { mutableStateOf<QuickCategory?>(null) }

    Column(Modifier.fillMaxSize()) {
        EditorHeader(state, c)
        HDivider()
        MenuBar(state, c)
        HDivider()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Dock width adapts to the window; on narrow windows it starts hidden.
            val dockWidth = (maxWidth * 0.24f).coerceIn(PhoDimens.DockMinWidth, PhoDimens.DockMaxWidth)
            val roomForDock = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize()) {
                ToolRail(state, c, onOpenCategory = { openCategory = it })
                VDivider()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    QuickStrip(state, c, openCategory, onOpenCategory = { openCategory = it })
                    Box(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                        CanvasView(state, base, detail, vm, Modifier.fillMaxSize())
                        if (state.panels.toolOptionsVisible) {
                            ToolOptionsBar(
                                state, c,
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 10.dp, start = 10.dp, end = 10.dp),
                            )
                        }
                        NoticePill(state, c, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                    }
                }
                if (state.panels.dockVisible && roomForDock) {
                    VDivider()
                    Dock(
                        state, c, thumbs, channels,
                        Modifier.width(dockWidth).fillMaxHeight().background(PhoColors.Dock),
                    )
                }
            }
        }
        if (state.panels.statusBarVisible) {
            HDivider()
            StatusBar(state, c)
        }
    }
    state.busy?.let { BusyOverlay(it) }
}
