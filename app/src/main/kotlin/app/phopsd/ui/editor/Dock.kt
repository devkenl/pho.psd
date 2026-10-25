package app.phopsd.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.phopsd.editor.CommandId
import app.phopsd.editor.DockTab
import app.phopsd.editor.EditorController
import app.phopsd.editor.EditorDialog
import app.phopsd.editor.EditorState
import app.phopsd.editor.NumericTarget
import app.phopsd.editor.Roadmap
import app.phopsd.editor.ThumbSize
import app.phopsd.engine.model.BlendMode
import app.phopsd.engine.model.Layer
import app.phopsd.engine.model.RasterId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.platform.ChannelThumbs
import app.phopsd.ui.components.HDivider
import app.phopsd.ui.components.MenuSectionHeader
import app.phopsd.ui.components.MilestoneBadge
import app.phopsd.ui.components.PhoIconButton
import app.phopsd.ui.components.PhoSlider
import app.phopsd.ui.components.Tooltipped
import app.phopsd.ui.icons.Lucide
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoType
import kotlin.math.roundToInt

/** Right dock: Layers | Channels | Paths tabs, with History below. */
@Composable
fun Dock(
    state: EditorState,
    c: EditorController,
    thumbs: Map<RasterId, ImageBitmap>,
    channels: ChannelThumbs?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        DockTabs(state, c)
        HDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.dockTab) {
                DockTab.Layers -> LayersPanel(state, c, thumbs)
                DockTab.Channels -> ChannelsPanel(channels)
                DockTab.Paths -> PathsPanel()
            }
        }
        if (state.panels.historyVisible) {
            HDivider()
            HistoryPanel(state, c)
        }
    }
}

@Composable
private fun DockTabs(state: EditorState, c: EditorController) {
    var settings by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in DockTab.entries) {
            val selected = state.dockTab == tab
            Column(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Tab) { c.setDockTab(tab) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tab.label,
                    style = (if (selected) PhoType.Title else PhoType.Body).copy(
                        color = if (selected) PhoColors.TextPrimary else PhoColors.TextSecondary,
                    ),
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .height(3.dp)
                        .width(if (selected) 44.dp else 0.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(PhoColors.Accent),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            PhoIconButton(Lucide.Settings, "Panel options", { settings = true })
            DropdownMenu(expanded = settings, onDismissRequest = { settings = false }) {
                MenuSectionHeader("Thumbnail size")
                for (size in ThumbSize.entries) {
                    DropdownMenuItem(
                        text = { Text(size.name, style = PhoType.Body) },
                        leadingIcon = {
                            Icon(
                                Lucide.Check, null,
                                tint = if (state.thumbSize == size) PhoColors.AccentHover else Color.Transparent,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { settings = false; c.setThumbSize(size) },
                    )
                }
                HDivider(Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text(if (state.panels.historyVisible) "Hide History" else "Show History", style = PhoType.Body) },
                    onClick = { settings = false; c.setHistoryVisible(!state.panels.historyVisible) },
                )
                DropdownMenuItem(
                    text = { Text("Close dock", style = PhoType.Body) },
                    onClick = { settings = false; c.setDockVisible(false) },
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PhoColors.Raised),
            contentAlignment = Alignment.Center,
        ) {
            PhoIconButton(Lucide.Plus, "New layer", { c.dispatch(CommandId.NewLayer) }, tooltip = "New layer (Shift+Ctrl+N)")
        }
    }
}

// ------------------------------------------------------------------ layers

@Composable
private fun LayersPanel(state: EditorState, c: EditorController, thumbs: Map<RasterId, ImageBitmap>) {
    val doc = state.document ?: return
    val active = doc.activeLayer
    Column(Modifier.fillMaxSize()) {
        // Blend mode + opacity for the active layer.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlendModeSelector(active, c, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text("Opacity", style = PhoType.Label)
            Spacer(Modifier.width(8.dp))
            val pct = ((active?.opacity ?: 1f) * 100).roundToInt()
            Box(
                Modifier
                    .width(64.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, PhoColors.Divider, RoundedCornerShape(6.dp))
                    .clickable(enabled = active != null, onClickLabel = "Type opacity") {
                        active?.let { c.showDialog(EditorDialog.NumericEntry(NumericTarget.LayerOpacity(it.id), it.opacity * 100.0)) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("$pct%", style = PhoType.Value)
            }
        }
        PhoSlider(
            value = (active?.opacity ?: 1f) * 100f,
            range = 0f..100f,
            onChange = { v -> active?.let { c.setOpacity(it.id, v / 100f) } },
            onChangeFinished = { c.endContinuousEdit() },
            enabled = active != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tooltipped("Fill opacity only differs from opacity once layer effects exist (${Roadmap.EFFECTS.milestone})") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fill", style = PhoType.Caption.copy(color = PhoColors.TextDisabled))
                    Spacer(Modifier.width(6.dp))
                    MilestoneBadge(Roadmap.EFFECTS.milestone)
                }
            }
            Spacer(Modifier.weight(1f))
            active?.let {
                Text(
                    if (it.locked) "Locked" else "Unlocked",
                    style = PhoType.Caption.copy(color = if (it.locked) PhoColors.Warning else PhoColors.TextSecondary),
                )
            }
        }
        HDivider()

        LayerList(state, c, thumbs, Modifier.weight(1f))

        HDivider()
        Row(
            Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DockAction(Lucide.LayersPlus, "New layer", CommandId.NewLayer, c)
            DockAction(Lucide.ImagePlus, "Place image as layer", CommandId.PlaceImage, c)
            DockAction(Lucide.Copy, "Duplicate layer", CommandId.DuplicateLayer, c)
            DockAction(Lucide.SquareDot, "Add layer mask", CommandId.AddMask, c)
            DockAction(Lucide.Folder, "New group", CommandId.NewGroup, c)
            DockAction(Lucide.Sun, "New adjustment layer", CommandId.NewAdjustmentLayer, c)
            DockAction(Lucide.Trash, "Delete layer", CommandId.DeleteLayer, c)
        }
    }
}

@Composable
private fun DockAction(icon: ImageVector, label: String, id: CommandId, c: EditorController) {
    val a = c.availability(id)
    PhoIconButton(
        icon, label, { c.dispatch(id) },
        enabled = a.isEnabled,
        tooltip = a.explanation?.let { "$label — $it" } ?: listOfNotNull(label, id.shortcut).joinToString("  "),
        size = 34.dp,
        iconSize = 18.dp,
    )
}

@Composable
private fun BlendModeSelector(active: Layer?, c: EditorController, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, PhoColors.Divider, RoundedCornerShape(6.dp))
                .clickable(enabled = active != null, role = Role.DropdownList, onClickLabel = "Blend mode") { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(active?.blendMode?.displayName ?: "—", style = PhoType.Body, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Lucide.ChevronDown, null, tint = PhoColors.TextSecondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var lastGroup = 0
            for (mode in BlendMode.entries) {
                if (mode.group != lastGroup) {
                    HDivider(Modifier.padding(vertical = 3.dp))
                    lastGroup = mode.group
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            mode.displayName,
                            style = PhoType.Body.copy(color = if (active?.blendMode == mode) PhoColors.AccentHover else PhoColors.TextPrimary),
                        )
                    },
                    onClick = {
                        open = false
                        active?.let { c.setBlendMode(it.id, mode) }
                    },
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
}

/**
 * Virtualized layer list, top-most layer first. Rows reorder by dragging the grip handle
 * (immediate) or by long-press-dragging anywhere on the row (finger, stylus or mouse).
 */
@Composable
private fun LayerList(state: EditorState, c: EditorController, thumbs: Map<RasterId, ImageBitmap>, modifier: Modifier) {
    val doc = state.document ?: return
    val visual = doc.layers.asReversed()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val rowHeight = (state.thumbSize.dp + 16).dp
    val rowPx = with(density) { rowHeight.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val layersNow by rememberUpdatedState(visual)

    fun finishDrag() {
        val id = draggingId ?: return
        val from = layersNow.indexOfFirst { it.id.value == id }
        if (from >= 0) {
            val toVisual = (from + (dragDy / rowPx).roundToInt()).coerceIn(0, layersNow.size - 1)
            if (toVisual != from) {
                val docIndex = layersNow.size - 1 - toVisual
                c.moveLayerTo(layersNow[from].id, docIndex)
            }
        }
        draggingId = null
        dragDy = 0f
    }

    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        itemsIndexed(visual, key = { _, l -> l.id.value }) { index, layer ->
            val dragging = draggingId == layer.id.value
            val target = if (draggingId != null) {
                val from = visual.indexOfFirst { it.id.value == draggingId }
                (from + (dragDy / rowPx).roundToInt()).coerceIn(0, visual.size - 1)
            } else -1
            val dragModifier = Modifier.pointerInput(layer.id, rowPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { draggingId = layer.id.value; dragDy = 0f; c.selectLayer(layer.id) },
                    onDragEnd = { finishDrag() },
                    onDragCancel = { draggingId = null; dragDy = 0f },
                ) { change, amount -> change.consume(); dragDy += amount.y }
            }
            val gripModifier = Modifier.pointerInput(layer.id, rowPx) {
                detectDragGestures(
                    onDragStart = { draggingId = layer.id.value; dragDy = 0f; c.selectLayer(layer.id) },
                    onDragEnd = { finishDrag() },
                    onDragCancel = { draggingId = null; dragDy = 0f },
                ) { change, amount -> change.consume(); dragDy += amount.y }
            }
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .offset { IntOffset(0, if (dragging) dragDy.roundToInt() else 0) }
                    .graphicsLayer { if (dragging) { shadowElevation = 12f; alpha = 0.95f } },
            ) {
                LayerRow(
                    layer = layer,
                    thumb = (layer as? RasterLayer)?.let { thumbs[it.rasterId] },
                    selected = layer.id == doc.activeLayerId,
                    thumbSize = state.thumbSize,
                    rowHeight = rowHeight,
                    c = c,
                    rowDrag = dragModifier,
                    gripDrag = gripModifier,
                )
                if (target == index && !dragging) {
                    val above = target < visual.indexOfFirst { it.id.value == draggingId }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(if (above) Alignment.TopCenter else Alignment.BottomCenter)
                            .background(PhoColors.AccentHover),
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    thumb: ImageBitmap?,
    selected: Boolean,
    thumbSize: ThumbSize,
    rowHeight: androidx.compose.ui.unit.Dp,
    c: EditorController,
    rowDrag: Modifier,
    gripDrag: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(shape)
            .background(if (selected) PhoColors.AccentSoft else PhoColors.Dock)
            .border(1.dp, if (selected) PhoColors.Accent else Color.Transparent, shape)
            .then(rowDrag)
            .pointerInput(layer.id, layer.name) {
                detectTapGestures(
                    onTap = { c.selectLayer(layer.id) },
                    onDoubleTap = { c.showDialog(EditorDialog.RenameLayer(layer.id, layer.name)) },
                )
            }
            .semantics {
                this.selected = selected
                contentDescription = "${layer.name}, ${layer.typeLabel}${if (!layer.visible) ", hidden" else ""}${if (layer.locked) ", locked" else ""}"
            }
            .padding(end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoIconButton(
            if (layer.visible) Lucide.Eye else Lucide.EyeOff,
            if (layer.visible) "Hide layer" else "Show layer",
            { c.toggleVisibility(layer.id) },
            size = 36.dp,
            iconSize = 18.dp,
        )
        LayerThumb(thumb, thumbSize, dimmed = !layer.visible)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                layer.name,
                style = PhoType.Body.copy(color = if (layer.visible) PhoColors.TextPrimary else PhoColors.TextSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                append(if (layer is RasterLayer) "Layer" else layer.typeLabel)
                if (layer.blendMode != BlendMode.Normal) append(" · ").append(layer.blendMode.displayName)
                if (layer.opacity < 1f) append(" · ").append((layer.opacity * 100).roundToInt()).append('%')
            }
            Text(sub, style = PhoType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (layer.locked) {
            PhoIconButton(Lucide.Lock, "Unlock layer", { c.toggleLock(layer.id) }, size = 32.dp, iconSize = 16.dp)
        }
        Box(gripDrag.size(28.dp, 36.dp), contentAlignment = Alignment.Center) {
            Icon(Lucide.GripVertical, "Drag to reorder", tint = PhoColors.TextDisabled, modifier = Modifier.size(16.dp))
        }
        Box {
            PhoIconButton(Lucide.EllipsisVertical, "Layer options", { c.selectLayer(layer.id); menu = true }, size = 32.dp, iconSize = 18.dp)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (id in listOf(
                    CommandId.RenameLayer, CommandId.DuplicateLayer, CommandId.ToggleLayerLock,
                    CommandId.BringForward, CommandId.SendBackward, CommandId.ResetLayerTransform,
                    CommandId.AddMask, CommandId.DeleteLayer,
                )) {
                    app.phopsd.ui.components.CommandMenuItem(id, c.availability(id), null) {
                        menu = false
                        c.dispatch(id)
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerThumb(image: ImageBitmap?, size: ThumbSize, dimmed: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(size.dp.dp)
            .clip(shape)
            .background(PhoColors.CheckerDark)
            .border(1.dp, PhoColors.Divider, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = if (dimmed) 0.45f else 1f,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ------------------------------------------------------------------ channels / paths

@Composable
private fun ChannelsPanel(channels: ChannelThumbs?) {
    Column(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
        val rows = listOf(
            Triple("RGB", "Ctrl+2", channels?.rgb),
            Triple("Red", "Ctrl+3", channels?.red),
            Triple("Green", "Ctrl+4", channels?.green),
            Triple("Blue", "Ctrl+5", channels?.blue),
        )
        for ((name, _, img) in rows) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Eye, null, tint = PhoColors.TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                LayerThumb(img, ThumbSize.Medium, dimmed = false)
                Spacer(Modifier.width(12.dp))
                Text(name, style = PhoType.Body)
            }
        }
        HDivider(Modifier.padding(vertical = 6.dp))
        Text(
            "Thumbnails show the live composite. Viewing a single channel, alpha channels and saved " +
                "selections arrive with the selection engine (M4); CMYK/LAB with colour management (M8).",
            style = PhoType.Caption,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

@Composable
private fun PathsPanel() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.PenTool, null, tint = PhoColors.TextDisabled, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text("No paths", style = PhoType.BodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Bézier paths, path selections and stroke/fill path arrive after the raster and selection " +
                "engines are stable (${Roadmap.PATHS.milestone}).",
            style = PhoType.Caption,
        )
    }
}

// ------------------------------------------------------------------ history

@Composable
private fun HistoryPanel(state: EditorState, c: EditorController) {
    val h = state.history ?: return
    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = PhoType.BodyMedium)
            Spacer(Modifier.weight(1f))
            Text("${h.index + 1} / ${h.entries.size}", style = PhoType.Caption)
        }
        val list = rememberLazyListState()
        LazyColumn(Modifier.fillMaxWidth(), state = list) {
            itemsIndexed(h.entries) { i, entry ->
                val current = i == h.index
                val undone = i > h.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(if (current) PhoColors.AccentSoft else Color.Transparent)
                        .clickable(onClickLabel = "Go to this state") { c.jumpToHistory(i) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (i == 0) Lucide.FileImage else Lucide.Clock, null,
                        tint = if (undone) PhoColors.TextDisabled else PhoColors.TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        entry.label,
                        style = PhoType.Label.copy(color = if (undone) PhoColors.TextDisabled else PhoColors.TextPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        androidx.compose.runtime.LaunchedEffect(h.index, h.entries.size) {
            if (h.entries.isNotEmpty()) list.animateScrollToItem(h.index)
        }
    }
}
