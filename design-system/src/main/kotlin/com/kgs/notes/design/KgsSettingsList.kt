package com.kgs.notes.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Where a row sits inside a settings group; the group reads as one container with tight inner seams. */
enum class KgsListPosition { ONLY, FIRST, MIDDLE, LAST }

fun kgsListPosition(index: Int, count: Int): KgsListPosition = when {
    count <= 1 -> KgsListPosition.ONLY
    index == 0 -> KgsListPosition.FIRST
    index == count - 1 -> KgsListPosition.LAST
    else -> KgsListPosition.MIDDLE
}

/** Spacing between rows of one group; groups themselves are separated by their headers. */
val KgsSettingsRowGap: Dp = 2.dp

private val OuterCorner = 20.dp
private val InnerCorner = 4.dp
private val PressedCorner = 12.dp

@Composable
private fun animatedCorners(position: KgsListPosition): KgsCornerRadii {
    val top = position == KgsListPosition.ONLY || position == KgsListPosition.FIRST
    val bottom = position == KgsListPosition.ONLY || position == KgsListPosition.LAST
    // Corners follow the row's position, so rows joining or leaving a group reshape their neighbours smoothly.
    val spec = LocalKgsMotion.current.defaultSpatialSpec<Dp>()
    val topRadius by animateDpAsState(if (top) OuterCorner else InnerCorner, spec, label = "Settings row top corners")
    val bottomRadius by animateDpAsState(if (bottom) OuterCorner else InnerCorner, spec, label = "Settings row bottom corners")
    return KgsCornerRadii(topRadius, topRadius, bottomRadius, bottomRadius)
}

/** A quiet label above a settings group. */
@Composable
fun KgsSettingsHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Supporting text below a settings group. */
@Composable
fun KgsSettingsFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/** The container shared by every row of a settings group. */
@Composable
fun KgsSettingsContainer(
    position: KgsListPosition,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val corners = animatedCorners(position)
    val color = MaterialTheme.colorScheme.surface
    if (onClick == null) {
        val shape = RoundedCornerShape(corners.topStart, corners.topEnd, corners.bottomEnd, corners.bottomStart)
        Box(modifier.fillMaxWidth().background(color, shape), content = content)
    } else {
        KgsExpressiveSurface(
            onClick = onClick,
            enabled = enabled,
            color = color,
            restingCorners = corners,
            pressedCorners = KgsCornerRadii(PressedCorner),
            contentAlignment = Alignment.CenterStart,
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    }
}

/** A leading icon well for navigation rows. */
@Composable
fun KgsSettingsIcon(tint: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.size(36.dp).background(tint.copy(alpha = .13f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** One settings row: title, optional summary, and optional leading and trailing slots. */
@Composable
fun KgsSettingsItem(
    title: String,
    position: KgsListPosition,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    KgsSettingsContainer(position, modifier, onClick) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (summary != null) {
                    Text(summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
}

/** A single-choice row inside a group of mutually exclusive options. */
@Composable
fun KgsSettingsRadioItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    position: KgsListPosition,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    KgsSettingsItem(
        title = title,
        summary = summary,
        position = position,
        onClick = onClick,
        modifier = modifier.semantics { role = Role.RadioButton; this.selected = selected },
        trailing = { KgsRadioIndicator(selected) },
    )
}

/** A binary setting; the whole row toggles, and the switch mirrors the state. */
@Composable
fun KgsSettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: KgsListPosition,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    KgsSettingsItem(
        title = title,
        summary = summary,
        position = position,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    )
}

@Composable
private fun KgsRadioIndicator(selected: Boolean) {
    val motion = LocalKgsMotion.current
    val dot by animateFloatAsState(if (selected) 1f else 0f, motion.fastSpatialSpec(), label = "Radio dot")
    val ring by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        motion.defaultEffectsSpec(), label = "Radio ring",
    )
    Box(Modifier.size(20.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).graphicsLayer { scaleX = dot; scaleY = dot }
            .background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}

/**
 * Side-by-side visual choices for settings whose options are easiest to compare by sight.
 * Previews are static; only the selection outline and label weight change.
 */
@Composable
fun <T> KgsSettingsTiles(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    position: KgsListPosition,
    modifier: Modifier = Modifier,
    tileModifier: (T) -> Modifier = { Modifier },
    preview: @Composable (T) -> Unit,
) {
    KgsSettingsContainer(position, modifier) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                SettingsTile(
                    label = label(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f).then(tileModifier(option)),
                ) { preview(option) }
            }
        }
    }
}

@Composable
private fun SettingsTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    preview: @Composable () -> Unit,
) {
    val outline by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        LocalKgsMotion.current.defaultEffectsSpec(), label = "Tile outline",
    )
    KgsExpressiveSurface(
        onClick = onClick,
        restingCorners = KgsCornerRadii(14.dp),
        pressedCorners = KgsCornerRadii(10.dp),
        modifier = modifier.semantics { role = Role.RadioButton; this.selected = selected },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(76.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .border(2.dp, outline, RoundedCornerShape(14.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) { preview() }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
        }
    }
}

/**
 * A compact choice between a few short options. The indicator slides to the chosen option,
 * so the motion shows where the selection went.
 */
@Composable
fun <T> KgsSettingsSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    position: KgsListPosition,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    segmentModifier: (T) -> Modifier = { Modifier },
) {
    KgsSettingsContainer(position, modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (summary != null) {
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SegmentedTrack(options, selected, onSelect, label, segmentModifier)
        }
    }
}

@Composable
private fun <T> SegmentedTrack(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    segmentModifier: (T) -> Modifier,
) {
    val motion = LocalKgsMotion.current
    val index = options.indexOf(selected).coerceAtLeast(0)
    val indicator by animateFloatAsState(index.toFloat(), motion.defaultSpatialSpec(), label = "Segment indicator")
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        Box(
            Modifier.width(segment).fillMaxHeight()
                .offset { IntOffset((segment.toPx() * indicator).roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { option ->
                val isSelected = option == selected
                val textColor by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    motion.defaultEffectsSpec(), label = "Segment label",
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                        .then(segmentModifier(option))
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
