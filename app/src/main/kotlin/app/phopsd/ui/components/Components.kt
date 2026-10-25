package app.phopsd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.phopsd.editor.Availability
import app.phopsd.editor.CommandId
import app.phopsd.ui.icons.Lucide
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoType
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ tooltips

enum class TooltipSide { Below, Above, Right }

private class TooltipPosition(private val side: TooltipSide, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x: Int
        val y: Int
        when (side) {
            TooltipSide.Below -> {
                x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                y = anchorBounds.bottom + gap
            }
            TooltipSide.Above -> {
                x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                y = anchorBounds.top - gap - popupContentSize.height
            }
            TooltipSide.Right -> {
                x = anchorBounds.right + gap
                y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
            }
        }
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

/**
 * Shows [text] after a short hover (mouse or stylus hover). Touch users get the same
 * information from long-press menus and notices, so tooltips are never the only source.
 */
@Composable
fun Tooltipped(
    text: String?,
    modifier: Modifier = Modifier,
    side: TooltipSide = TooltipSide.Below,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, text) {
        if (hovered && text != null) {
            delay(450)
            show = true
        } else {
            show = false
        }
    }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    Box(modifier.hoverable(interaction)) {
        content()
        if (show && text != null) {
            Popup(
                popupPositionProvider = remember(side, gap) { TooltipPosition(side, gap) },
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xF0263447))
                        .border(1.dp, PhoColors.Divider, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text, style = PhoType.Caption.copy(color = PhoColors.TextPrimary))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ buttons

/**
 * Compact icon button. [enabled] only changes appearance: a "disabled" button still
 * receives clicks so the caller can explain why it is unavailable.
 */
@Composable
fun PhoIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tooltip: String? = label,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    tooltipSide: TooltipSide = TooltipSide.Below,
) {
    Tooltipped(tooltip, modifier, tooltipSide) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) PhoColors.AccentSoft else Color.Transparent)
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = label
                    if (!enabled) stateDescription = "Unavailable"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    !enabled -> PhoColors.TextDisabled
                    selected -> PhoColors.AccentHover
                    else -> PhoColors.TextPrimary
                },
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun PhoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tooltip: String? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = when {
        primary && enabled -> PhoColors.Accent
        primary -> PhoColors.Raised
        else -> Color.Transparent
    }
    Tooltipped(tooltip, modifier) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, if (primary && enabled) PhoColors.Accent else PhoColors.Divider, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { if (!enabled) stateDescription = "Unavailable" }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val color = if (enabled) PhoColors.TextPrimary else PhoColors.TextDisabled
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = PhoType.BodyMedium.copy(color = color), maxLines = 1)
        }
    }
}

@Composable
fun MilestoneBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(PhoColors.Dock)
            .border(1.dp, PhoColors.Divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = PhoType.Badge)
    }
}

@Composable
fun VDivider(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).fillMaxHeight().background(PhoColors.Divider))
}

@Composable
fun HDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(PhoColors.Divider))
}

// ------------------------------------------------------------------ menus

/** A command row inside a DropdownMenu, with shortcut or milestone badge. */
@Composable
fun CommandMenuItem(
    id: CommandId,
    availability: Availability,
    checked: Boolean?,
    onClick: () -> Unit,
) {
    val enabled = availability.isEnabled
    val leading: (@Composable () -> Unit)? = if (checked == null) null else {
        {
            Icon(
                Lucide.Check, null,
                tint = if (checked) PhoColors.AccentHover else Color.Transparent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    DropdownMenuItem(
        text = {
            Row(Modifier.widthIn(min = 230.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    id.label,
                    style = PhoType.Body.copy(color = if (enabled) PhoColors.TextPrimary else PhoColors.TextDisabled),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                when (availability) {
                    is Availability.Planned -> MilestoneBadge(availability.milestone)
                    else -> id.shortcut?.let { Text(it, style = PhoType.Caption) }
                }
            }
        },
        onClick = onClick,
        leadingIcon = leading,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier
            .height(34.dp)
            .semantics { availability.explanation?.let { stateDescription = it } },
    )
}

@Composable
fun MenuSectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = PhoType.Badge.copy(color = PhoColors.TextSecondary),
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

// ------------------------------------------------------------------ slider

/** Compact slider matching the reference's thin track and white thumb. */
@Composable
fun PhoSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onChangeFinished: () -> Unit = {},
    enabled: Boolean = true,
) {
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    var widthPx by remember { mutableIntStateOf(1) }
    val span = range.endInclusive - range.start
    fun valueAt(x: Float): Float = range.start + (x / widthPx.coerceAtLeast(1)).coerceIn(0f, 1f) * span
    val fraction = if (span <= 0f) 0f else ((value - range.start) / span).coerceIn(0f, 1f)

    Box(
        modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { change(valueAt(it.x)); finished() })
            }
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { change(valueAt(it.x)) },
                    onDragEnd = { finished() },
                    onDragCancel = { finished() },
                ) { pointer, _ ->
                    pointer.consume()
                    change(valueAt(pointer.position.x))
                }
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                if (enabled) {
                    setProgress { v ->
                        change(v.coerceIn(range.start, range.endInclusive)); finished(); true
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackH = 3.dp.toPx()
            val r = 7.dp.toPx()
            val left = r
            val right = size.width - r
            val cy = size.height / 2
            val x = left + (right - left) * fraction
            drawRoundRect(
                color = PhoColors.Divider,
                topLeft = Offset(left, cy - trackH / 2),
                size = Size(right - left, trackH),
                cornerRadius = CornerRadius(trackH / 2),
            )
            drawRoundRect(
                color = if (enabled) PhoColors.Accent else PhoColors.TextDisabled,
                topLeft = Offset(left, cy - trackH / 2),
                size = Size(x - left, trackH),
                cornerRadius = CornerRadius(trackH / 2),
            )
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = r + 1.dp.toPx(), center = Offset(x, cy + 1.dp.toPx()))
            drawCircle(if (enabled) Color.White else PhoColors.TextSecondary, radius = r, center = Offset(x, cy))
        }
    }
}

/**
 * Label + value + slider, as in the reference's bottom brush strip. Double-tapping the
 * value requests precise numeric entry.
 */
@Composable
fun SliderCell(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    onRequestNumericEntry: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val request by rememberUpdatedState(onRequestNumericEntry)
    Column(modifier.padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PhoType.Label)
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                style = PhoType.Value.copy(color = if (enabled) PhoColors.TextPrimary else PhoColors.TextDisabled),
                modifier = Modifier
                    .pointerInput(enabled) {
                        if (enabled) detectTapGestures(onDoubleTap = { request() })
                    }
                    .semantics { contentDescription = "$label $valueText, double-tap to type a value" },
            )
        }
        PhoSlider(value, range, onChange, Modifier.fillMaxWidth(), onChangeFinished, enabled)
    }
}

// ------------------------------------------------------------------ text field & dialogs

@Composable
fun PhoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    suffix: String? = null,
    onDone: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = PhoType.Body,
        cursorBrush = SolidColor(PhoColors.Accent),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(shape)
                    .background(PhoColors.Dock)
                    .border(1.dp, PhoColors.Divider, shape)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { inner() }
                if (suffix != null) Text(suffix, style = PhoType.Caption, modifier = Modifier.padding(start = 6.dp))
            }
        },
    )
}

@Composable
fun PhoDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 460.dp,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .width(width)
                .clip(shape)
                .background(PhoColors.Raised)
                .border(1.dp, PhoColors.Divider, shape)
                .padding(20.dp),
        ) {
            Text(title, style = PhoType.Title)
            Spacer(Modifier.height(14.dp))
            content()
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), content = actions)
        }
    }
}

@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PhoType.Label, modifier = Modifier.width(120.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** Key/value line for info dialogs. */
@Composable
fun InfoLine(key: String, value: String, valueStyle: TextStyle = PhoType.Body) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, style = PhoType.Label, modifier = Modifier.width(150.dp))
        Text(value, style = valueStyle, modifier = Modifier.weight(1f))
    }
}
