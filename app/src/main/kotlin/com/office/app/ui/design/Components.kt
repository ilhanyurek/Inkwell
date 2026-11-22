package com.office.app.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.office.app.R
import com.office.app.ui.theme.LocalAppearance

/** Corner radius of card groups. */
val CardRadius = 20.dp

/** The colour cards are drawn in. */
@Composable
fun cardColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

@Composable
fun cardBorder(): BorderStroke? = LocalAppearance.current.cardBorder?.let { BorderStroke(1.dp, it) }

/** Plain back chevron, as in the page title bars. */
@Composable
fun BackChevron(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBackIos,
            contentDescription = stringResource(R.string.action_back),
            modifier = Modifier.size(20.dp).padding(start = 4.dp),
        )
    }
}

/** A big bold page title with an optional back chevron and trailing actions, on the page colour. */
@Composable
fun PageTitleBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(start = if (onBack == null) 24.dp else 6.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) BackChevron(onBack)
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = if (onBack == null) 0.dp else 2.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A screen with a big title bar on the light grey page. */
@Composable
fun PageScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    topBar: (@Composable () -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = topBar ?: { PageTitleBar(title, onBack, actions = actions) },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/** Small uppercase grey label above a card group. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val locale = LocalConfiguration.current.locales[0]
    Row(
        modifier.fillMaxWidth().padding(start = 32.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(locale),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** A white rounded card holding rows; put [CardDivider] between rows. */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        color = cardColor(),
        border = cardBorder(),
    ) {
        Column(content = content)
    }
}

/** Thin divider between rows of a card, inset past the leading icon tile. */
@Composable
fun CardDivider(inset: Dp = 66.dp) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * Draws one row of a card that is split across lazy list items: the first row rounds the top corners,
 * the last one the bottom. Rows other than the last get a divider under them.
 */
@Composable
fun Modifier.cardSegment(index: Int, count: Int, dividerInset: Dp = 66.dp): Modifier {
    val first = index == 0
    val last = index == count - 1
    val r = CardRadius
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    val color = cardColor()
    val border = LocalAppearance.current.cardBorder
    val divider = MaterialTheme.colorScheme.outlineVariant
    return this
        .padding(horizontal = 16.dp)
        .clip(shape)
        .background(color)
        .drawBehind {
            if (!last) {
                val y = size.height - 1.dp.toPx()
                drawRect(divider, topLeft = androidx.compose.ui.geometry.Offset(dividerInset.toPx(), y), size = androidx.compose.ui.geometry.Size(size.width - dividerInset.toPx(), 1.dp.toPx()))
            }
            if (border != null) {
                // Stretch the outline past the cut edges so only the card's outer edge shows.
                val sw = 1.dp.toPx()
                val top = if (first) sw / 2 else -sw * 2
                val bottom = if (last) size.height - sw / 2 else size.height + sw * 2
                val cr = CornerRadius(r.toPx())
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = sw / 2, top = top, right = size.width - sw / 2, bottom = bottom,
                            topLeftCornerRadius = if (first) cr else CornerRadius.Zero,
                            topRightCornerRadius = if (first) cr else CornerRadius.Zero,
                            bottomLeftCornerRadius = if (last) cr else CornerRadius.Zero,
                            bottomRightCornerRadius = if (last) cr else CornerRadius.Zero,
                        ),
                    )
                }
                drawPath(path, border, style = Stroke(sw))
            }
        }
}

/** A rounded square behind an icon; filled with the accent when [selected]. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color? = null,
    container: Color? = null,
    size: Dp = 36.dp,
) {
    val bg = container ?: if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = tint ?: if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.size(size).background(bg, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.56f))
    }
}

/** One row of a card: optional leading tile, title, optional subtitle and trailing content. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    titleColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 2,
) {
    val click = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(enabled = enabled, onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .then(click)
            .defaultMinSize(minHeight = 56.dp)
            .padding(start = 16.dp, end = if (trailing != null) 8.dp else 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = (titleColor ?: MaterialTheme.colorScheme.onSurface).copy(alpha = if (enabled) 1f else 0.38f),
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun appSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
)

/** A row with a switch; the whole row toggles it. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        titleMaxLines = 2,
        subtitleMaxLines = 4,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        leading = icon?.let { { IconTile(it) } },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = appSwitchColors()) },
    )
}

/** A row that is one choice of several; the chosen one shows a check mark. */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        leading = icon?.let { { IconTile(it, selected = selected) } },
        trailing = {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

/** A pill-shaped segmented control; the chosen segment is filled with the accent. */
@Composable
fun SegmentedPills(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTags: List<String?> = emptyList(),
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = cardColor(),
        border = cardBorder(),
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { i, label ->
                val isSel = i == selected
                val bg by animateColorAsState(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent, label = "seg")
                val fg by animateColorAsState(
                    if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "segText",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(bg)
                        .selectable(selected = isSel, role = Role.Tab, onClick = { onSelect(i) })
                        .then(testTags.getOrNull(i)?.let { Modifier.testTag(it) } ?: Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** An outlined pill chip; filled with the accent when selected. */
@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "chip")
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        shape = CircleShape,
        color = bg,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

/** The main call to action: a filled accent pill. */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(start = if (icon != null) 18.dp else 24.dp, end = 24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A rounded card floating above the bottom edge, used for editor tool bars. Has the same shape as a
 * [Surface] content slot so it can stand in for one.
 */
@Composable
fun FloatingBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val black = LocalAppearance.current.black
    Surface(
        modifier = modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder(),
        shadowElevation = if (black) 0.dp else 3.dp,
        content = content,
    )
}
