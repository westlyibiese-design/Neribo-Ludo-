package com.westly.ludo.ui.dialogs

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Text styles
// ---------------------------------------------------------------------------

/** Bricolage Grotesque ExtraBold (titles, buttons). Falls back to the system sans. */
@Composable
internal fun displayStyle(
    size: TextUnit,
    color: Color,
    align: TextAlign = TextAlign.Start,
    shadow: Shadow? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified
): TextStyle = TextStyle(
    color = color,
    fontSize = size,
    fontFamily = LocalLudoFonts.current.display,
    fontWeight = FontWeight.ExtraBold,
    textAlign = align,
    shadow = shadow,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing
)

/** Figtree (body text). [weight] is Medium, SemiBold or Bold. */
@Composable
internal fun bodyStyle(
    size: TextUnit,
    weight: FontWeight,
    color: Color,
    align: TextAlign = TextAlign.Start,
    lineHeight: TextUnit = TextUnit.Unspecified
): TextStyle = TextStyle(
    color = color,
    fontSize = size,
    fontFamily = LocalLudoFonts.current.body,
    fontWeight = weight,
    textAlign = align,
    lineHeight = lineHeight
)

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/**
 * The 3D button: gradient face, solid edge underneath. Pressing moves the face down 4dp and the
 * edge shrinks from 5dp to 1dp. Disabled = 40% opacity and no taps.
 */
@Composable
fun ChunkyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ChunkyTone = ChunkyTones.Green,
    icon: LudoIcon? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val spec: AnimationSpec<Float> = if (LocalMotionEnabled.current) tween(110) else snap()
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, spec, label = "chunkyPress")
    val shape = RoundedCornerShape(18.dp)
    val onePx = LocalDensity.current.density
    val innerBorder = tone.innerBorder
    val label = displayStyle(
        size = 17.sp,
        color = tone.content,
        align = TextAlign.Center,
        shadow = if (tone.textShadow) Shadow(Color(0x33000000), Offset(0f, onePx), 0f) else null
    )
    Box(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .graphicsLayer { translationY = 4.dp.toPx() * press }
            .drawBehind {
                val edge = 5.dp.toPx() * (1f - press) + 1.dp.toPx() * press
                drawRoundRect(
                    color = tone.edge,
                    topLeft = Offset(0f, edge),
                    size = size,
                    cornerRadius = CornerRadius(18.dp.toPx())
                )
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(tone.top, tone.bottom)))
            .then(if (innerBorder != null) Modifier.border(1.5.dp, innerBorder, shape) else Modifier)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
            } else if (icon != null) {
                LudoIconView(icon, iconSize = 21.dp, tint = tone.content)
            }
            BasicText(text, style = label, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** Flat button: white 6% fill and a thin line border. Pressing shrinks it slightly. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: LudoIcon? = null,
    enabled: Boolean = true
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val spec: AnimationSpec<Float> = if (LocalMotionEnabled.current) tween(110) else snap()
    val scaleNow by animateFloatAsState(if (pressed && enabled) 0.98f else 1f, spec, label = "ghostPress")
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .graphicsLayer {
                scaleX = scaleNow
                scaleY = scaleNow
            }
            .clip(shape)
            .background(Color(0x0FFFFFFF))
            .border(1.5.dp, GoldTheme.Line, shape)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) LudoIconView(icon, iconSize = 21.dp, tint = Color.White)
            BasicText(
                text,
                style = displayStyle(17.sp, Color.White, TextAlign.Center),
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

/** A column of buttons with the 12dp gap and 20dp top margin used under the dialog content. */
@Composable
fun DialogButtons(
    modifier: Modifier = Modifier,
    topGap: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.fillMaxWidth().padding(top = topGap),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

// ---------------------------------------------------------------------------
// Info panel
// ---------------------------------------------------------------------------

/** Dark rounded panel that holds [InfoRow]s (or any content). */
@Composable
fun InfoPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(GoldTheme.PanelFill, shape)
            .border(1.dp, GoldTheme.GoldLine, shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        content = content
    )
}

/**
 * One row in an [InfoPanel]: [leading] (or a muted [icon]), bold [title], small [subtitle], and an
 * optional [tag] on the right. Set [topDivider] on every row except the first.
 */
@Composable
fun InfoRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: LudoIcon? = null,
    leading: (@Composable () -> Unit)? = null,
    tag: (@Composable () -> Unit)? = null,
    dimmed: Boolean = false,
    topDivider: Boolean = false
) {
    Column(modifier.fillMaxWidth().alpha(if (dimmed) 0.45f else 1f)) {
        if (topDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(GoldTheme.Line))
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leading != null) {
                leading()
            } else if (icon != null) {
                LudoIconView(icon, iconSize = 22.dp, tint = GoldTheme.Muted)
            }
            Column(Modifier.weight(1f)) {
                BasicText(title, style = bodyStyle(16.sp, FontWeight.Bold, GoldTheme.Cream))
                if (subtitle != null) {
                    BasicText(
                        subtitle,
                        style = bodyStyle(14.sp, FontWeight.Medium, GoldTheme.Muted, lineHeight = 19.sp)
                    )
                }
            }
            if (tag != null) tag()
        }
    }
}

enum class TagKind { Bad, Good, Neutral }

/** Small rounded label: "Out" (bad), "+1" (good), "1st" (good). */
@Composable
fun StatusTag(text: String, kind: TagKind = TagKind.Neutral, modifier: Modifier = Modifier) {
    val fill = when (kind) {
        TagKind.Bad -> GoldTheme.TagBad
        TagKind.Good -> GoldTheme.TagGood
        TagKind.Neutral -> GoldTheme.TagNeutral
    }
    BasicText(
        text,
        modifier = modifier
            .background(fill, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = displayStyle(14.sp, Color.White),
        maxLines = 1
    )
}

// ---------------------------------------------------------------------------
// Choices
// ---------------------------------------------------------------------------

private val ChipGreen = Brush.verticalGradient(listOf(Color(0xFF55C173), Color(0xFF2E9B4E)))

/** Pill choice. Selected = green with a 3dp dark-green edge. Use several in a row for "one of". */
@Composable
fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: LudoIcon? = null
) {
    val shape = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .drawBehind {
                if (selected) {
                    drawRoundRect(
                        color = Color(0xFF1B6A35),
                        topLeft = Offset(0f, 3.dp.toPx()),
                        size = size,
                        cornerRadius = CornerRadius(size.height / 2f)
                    )
                }
            }
            .clip(shape)
            .then(
                if (selected) Modifier.background(ChipGreen)
                else Modifier.background(GoldTheme.ChipFill)
            )
            .border(1.5.dp, if (selected) Color(0xFF9BE3B0) else GoldTheme.GoldChip, shape)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .heightIn(min = 46.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) LudoIconView(icon, iconSize = 20.dp, tint = Color.White)
            BasicText(
                label,
                style = bodyStyle(15.sp, FontWeight.Bold, if (selected) Color.White else GoldTheme.Cream)
            )
        }
    }
}

class ToggleOption(val label: String, val icon: LudoIcon? = null)

/** Two (or more) side-by-side options in one rounded box, for example Sound On / Off. */
@Composable
fun SegmentedToggle(
    options: List<ToggleOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .border(1.5.dp, GoldTheme.GoldSeg, shape)
            .clip(shape)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(1f)
                    .then(
                        if (selected) Modifier.background(ChipGreen)
                        else Modifier.background(GoldTheme.ChipFill)
                    )
                    .selectable(
                        selected = selected,
                        interactionSource = source,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) }
                    )
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (option.icon != null) LudoIconView(option.icon, iconSize = 21.dp, tint = Color.White)
                    BasicText(
                        option.label,
                        style = bodyStyle(17.sp, FontWeight.Bold, if (selected) Color.White else GoldTheme.Cream)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Avatars and round icons
// ---------------------------------------------------------------------------

/** Coloured circle with the first letter of [name]. [seat] 0..3 = Red, Green, Yellow, Blue; anything else = grey. */
@Composable
fun SeatAvatar(
    seat: Int,
    name: String,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 40.dp
) {
    val colors = SeatColors.of(seat)
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val px = with(LocalDensity.current) { avatarSize.toPx() }
    val letterSize = with(LocalDensity.current) { (avatarSize * 0.46f).toSp() }
    val brush = Brush.radialGradient(
        colors = listOf(colors.main, colors.dark),
        center = Offset(px * 0.32f, px * 0.28f),
        radius = px * 0.99f
    )
    Box(
        modifier
            .size(avatarSize)
            .drawBehind {
                drawCircle(
                    color = colors.dark,
                    radius = this.size.minDimension / 2f,
                    center = Offset(this.size.width / 2f, this.size.height / 2f + 2.dp.toPx())
                )
            }
            .clip(CircleShape)
            .background(brush)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            letter,
            style = displayStyle(
                letterSize,
                Color.White,
                TextAlign.Center,
                shadow = Shadow(Color(0x33000000), Offset(0f, 2f), 0f)
            ),
            maxLines = 1
        )
    }
}

/** Round gradient badge with an icon inside (used for the rows of the settings menu). */
@Composable
fun RoundIconBadge(
    icon: LudoIcon,
    tone: ChunkyTone,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 40.dp
) {
    Box(
        modifier
            .size(badgeSize)
            .drawBehind {
                drawCircle(
                    color = tone.edge,
                    radius = this.size.minDimension / 2f,
                    center = Offset(this.size.width / 2f, this.size.height / 2f + 2.dp.toPx())
                )
            }
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(tone.top, tone.bottom)))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        LudoIconView(icon, iconSize = badgeSize * 0.55f, tint = tone.content)
    }
}
