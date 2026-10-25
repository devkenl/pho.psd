package app.phopsd.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phopsd.editor.Availability
import app.phopsd.editor.EditorController
import app.phopsd.editor.EditorState
import app.phopsd.editor.QuickCategory
import app.phopsd.editor.Roadmap
import app.phopsd.editor.Tool
import app.phopsd.editor.ToolGroup
import app.phopsd.ui.components.MenuSectionHeader
import app.phopsd.ui.components.MilestoneBadge
import app.phopsd.ui.components.TooltipSide
import app.phopsd.ui.components.Tooltipped
import app.phopsd.ui.icons.Lucide
import app.phopsd.editor.CommandId
import app.phopsd.ui.components.PhoIconButton
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoDimens
import app.phopsd.ui.theme.PhoType

fun ToolGroup.icon(): ImageVector = when (this) {
    ToolGroup.Select -> Lucide.SquareDashed
    ToolGroup.Move -> Lucide.Move
    ToolGroup.Brush -> Lucide.Paintbrush
    ToolGroup.Healing -> Lucide.Bandage
    ToolGroup.Clone -> Lucide.Stamp
    ToolGroup.Erase -> Lucide.Eraser
    ToolGroup.Crop -> Lucide.Crop
    ToolGroup.Transform -> Lucide.Scaling
    ToolGroup.Gradient -> Lucide.PaintBucket
    ToolGroup.Text -> Lucide.Type
    ToolGroup.Adjust -> Lucide.SlidersHorizontal
    ToolGroup.Mask -> Lucide.SquareDot
    ToolGroup.Hand -> Lucide.Hand
    ToolGroup.Zoom -> Lucide.ZoomIn
    ToolGroup.More -> Lucide.Ellipsis
}

fun Tool.icon(): ImageVector = when (this) {
    Tool.RectMarquee -> Lucide.SquareDashed
    Tool.EllipticalMarquee -> Lucide.CircleDashed
    Tool.Lasso, Tool.PolygonalLasso, Tool.MagneticLasso -> Lucide.Lasso
    Tool.ObjectSelection -> Lucide.SquareMousePointer
    Tool.QuickSelection, Tool.MagicWand -> Lucide.WandSparkles
    Tool.Pen -> Lucide.PenTool
    Tool.Eyedropper -> Lucide.Pipette
    else -> ToolGroup.of(this).icon()
}

/**
 * Compact vertical tool rail. One button per tool family; long-press or secondary click
 * reveals the variants. Unbuilt tools stay visible (dimmed) and explain their milestone.
 */
@Composable
fun ToolRail(state: EditorState, c: EditorController, onOpenCategory: (QuickCategory?) -> Unit) {
    Column(
        Modifier
            .width(PhoDimens.RailWidth)
            .fillMaxHeight()
            .background(PhoColors.Rail)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (group in ToolGroup.entries) {
            RailButton(group, state, c, onOpenCategory)
        }
        Spacer(Modifier.height(10.dp))
        ColorSwatches(state, c, compact = true)
        Row {
            PhoIconButton(Lucide.ArrowLeftRight, "Swap colors", { c.dispatch(CommandId.SwapColors) }, tooltip = "Swap colors (X)")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailButton(group: ToolGroup, state: EditorState, c: EditorController, onOpenCategory: (QuickCategory?) -> Unit) {
    var variantsOpen by remember { mutableStateOf(false) }
    val active = state.effectiveTool in group.tools
    val groupAvailability: Availability = when (group) {
        ToolGroup.Adjust -> Roadmap.ADJUSTMENTS
        ToolGroup.More -> Availability.Enabled
        else -> group.tools.firstOrNull { it.availability.isEnabled }?.let { Availability.Enabled }
            ?: group.primary?.availability ?: Availability.Enabled
    }
    val enabled = groupAvailability.isEnabled
    val shortcut = group.primary?.shortcut
    val tooltip = buildString {
        append(group.primary?.label ?: group.label)
        if (shortcut != null) append(" ($shortcut)")
        groupAvailability.explanation?.let { append(" — ").append(it) }
        if (group.tools.size > 1) append("\nLong-press for ${group.tools.size} variants")
    }
    val openVariants by rememberUpdatedState { variantsOpen = true }
    val shape = RoundedCornerShape(8.dp)

    Box {
        Tooltipped(tooltip, side = TooltipSide.Right) {
            Column(
                Modifier
                    .size(width = 60.dp, height = 58.dp)
                    .clip(shape)
                    .background(if (active) PhoColors.AccentSoft else Color.Transparent)
                    .border(1.dp, if (active) PhoColors.Accent else Color.Transparent, shape)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = {
                            when (group) {
                                ToolGroup.Adjust -> onOpenCategory(QuickCategory.Adjust)
                                ToolGroup.More -> variantsOpen = true
                                else -> {
                                    val current = group.tools.firstOrNull { it == state.tool }
                                    c.selectTool(current ?: group.primary ?: return@combinedClickable)
                                }
                            }
                        },
                        onLongClick = { if (group.tools.size > 1 || group == ToolGroup.More) variantsOpen = true },
                    )
                    .pointerInput(group) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                    e.changes.forEach { it.consume() }
                                    openVariants()
                                }
                            }
                        }
                    }
                    .semantics {
                        contentDescription = tooltip
                        selected = active
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val tint = when {
                    active -> PhoColors.AccentHover
                    enabled -> PhoColors.TextPrimary
                    else -> PhoColors.TextDisabled
                }
                Icon(
                    imageVector = if (active) state.effectiveTool.icon() else group.icon(),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(group.label, style = PhoType.Caption.copy(fontSize = 11.sp, color = if (enabled || active) PhoColors.TextPrimary else PhoColors.TextDisabled))
            }
        }
        DropdownMenu(expanded = variantsOpen, onDismissRequest = { variantsOpen = false }) {
            MenuSectionHeader(group.label)
            for (tool in group.tools) {
                ToolVariantItem(tool, selected = state.tool == tool) {
                    variantsOpen = false
                    c.selectTool(tool)
                }
            }
            if (group == ToolGroup.More) {
                MenuSectionHeader("Toolbar")
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Customize toolbar favorites…", style = PhoType.Body.copy(color = PhoColors.TextDisabled))
                            Spacer(Modifier.width(16.dp))
                            MilestoneBadge(Roadmap.WORKSPACE.milestone)
                        }
                    },
                    onClick = {
                        variantsOpen = false
                        c.notice("Toolbar favorites: ${Roadmap.WORKSPACE.explanation}")
                    },
                )
            }
        }
    }
}

@Composable
private fun ToolVariantItem(tool: Tool, selected: Boolean, onClick: () -> Unit) {
    val enabled = tool.availability.isEnabled
    androidx.compose.material3.DropdownMenuItem(
        text = {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tool.label,
                    style = PhoType.Body.copy(color = if (enabled) PhoColors.TextPrimary else PhoColors.TextDisabled),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                val a = tool.availability
                if (a is Availability.Planned) MilestoneBadge(a.milestone)
                else tool.shortcut?.let { Text(it, style = PhoType.Caption) }
            }
        },
        leadingIcon = {
            Icon(
                tool.icon(), null,
                tint = when {
                    selected -> PhoColors.AccentHover
                    enabled -> PhoColors.TextPrimary
                    else -> PhoColors.TextDisabled
                },
                modifier = Modifier.size(18.dp),
            )
        },
        onClick = onClick,
    )
}
