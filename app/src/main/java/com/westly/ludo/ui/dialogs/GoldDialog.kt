package com.westly.ludo.ui.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

private val EntryEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * The window every Ludo Mate dialog sits in: full-screen dark scrim (fades in), and the content
 * fades, scales 0.94 -> 1 and slides up 18dp in 320ms. Skipped when animations are off.
 *
 * [onBack] runs when the Back button is pressed. Pass null to block Back (full-screen results).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DialogWindow(
    onBack: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val fonts = remember(context) { loadLudoFonts(context) }
    val motion = remember(context) { animationsEnabled(context) }

    Dialog(
        onDismissRequest = { onBack?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onBack != null,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // The window's own dimming is switched off: the scrim below is the exact colour.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }

        BackHandler(enabled = true) { onBack?.invoke() }

        val progress = remember { Animatable(if (motion) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (motion) progress.animateTo(1f, tween(320, easing = EntryEasing))
        }
        val slidePx = with(LocalDensity.current) { 18.dp.toPx() }

        CompositionLocalProvider(
            LocalLudoFonts provides fonts,
            LocalMotionEnabled provides motion
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = (progress.value * 1.28f).coerceAtMost(1f) }
                        .background(GoldTheme.Scrim)
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = progress.value
                            alpha = p
                            scaleX = 0.94f + 0.06f * p
                            scaleY = 0.94f + 0.06f * p
                            translationY = (1f - p) * slidePx
                        },
                    content = content
                )
            }
        }
    }
}

/**
 * The standard gold-framed dialog: icon badge on the top edge, gold title, optional lead text,
 * a red close button, and [content] below. Content scrolls on small screens or large fonts.
 *
 * [onClose] runs for the X button and the Back button.
 * Give [badgeIcon] for an icon badge, or [badgeLetter] for a letter badge.
 */
@Composable
fun GoldDialog(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    badgeIcon: LudoIcon? = null,
    badgeLetter: String? = null,
    badgeTone: ChunkyTone = ChunkyTones.Red,
    lead: String? = null,
    showClose: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    DialogWindow(onBack = onClose) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            val minHeight = if (maxHeight > 32.dp) maxHeight - 32.dp else 0.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = minHeight),
                    contentAlignment = Alignment.Center
                ) {
                    GoldCard(
                        title = title,
                        lead = lead,
                        onClose = onClose,
                        showClose = showClose,
                        badgeIcon = badgeIcon,
                        badgeLetter = badgeLetter,
                        badgeTone = badgeTone,
                        modifier = modifier,
                        content = content
                    )
                }
            }
        }
    }
}

// How far the badge sticks out above the frame, plus a little room for its outer ring.
private val BadgeRoom = 40.dp

@Composable
private fun GoldCard(
    title: String,
    lead: String?,
    onClose: () -> Unit,
    showClose: Boolean,
    badgeIcon: LudoIcon?,
    badgeLetter: String?,
    badgeTone: ChunkyTone,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(30.dp)
    val onePx = LocalDensity.current.density
    // Outer box is 12dp wider each side and BadgeRoom taller than the frame, so the badge and the
    // close button sit inside its bounds and can be tapped.
    Box(modifier.widthIn(max = 444.dp).fillMaxWidth()) {
        Column(
            Modifier
                .padding(start = 12.dp, end = 12.dp, top = BadgeRoom)
                .fillMaxWidth()
                .shadow(
                    elevation = 28.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = GoldTheme.Gold.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.8f)
                )
                .background(Brush.verticalGradient(listOf(GoldTheme.Navy1, GoldTheme.Navy2)), shape)
                .border(2.dp, GoldTheme.Gold, shape)
                .drawBehind {
                    // thin white 12% highlight along the top edge
                    drawLine(
                        color = Color(0x1FFFFFFF),
                        start = Offset(30.dp.toPx(), 2.5.dp.toPx()),
                        end = Offset(this.size.width - 30.dp.toPx(), 2.5.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .padding(start = 22.dp, end = 22.dp, top = 52.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                title,
                modifier = Modifier.semantics { heading() },
                style = displayStyle(
                    size = 27.sp,
                    color = GoldTheme.Gold,
                    align = TextAlign.Center,
                    shadow = Shadow(Color(0x70000000), Offset(0f, 2f * onePx), 0f),
                    lineHeight = 30.sp,
                    letterSpacing = (-0.27).sp
                )
            )
            if (lead != null) {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    lead,
                    modifier = Modifier.widthIn(max = 300.dp),
                    style = bodyStyle(16.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center, 23.sp)
                )
            }
            Spacer(Modifier.height(18.dp))
            content()
        }

        IconBadge(
            icon = badgeIcon,
            letter = badgeLetter,
            tone = badgeTone,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 3.dp)
        )

        if (showClose) {
            CloseX(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).offset(y = 24.dp))
        }
    }
}

/** 64dp badge: gradient, 3dp gold ring, 3dp dark outer ring. */
@Composable
private fun IconBadge(icon: LudoIcon?, letter: String?, tone: ChunkyTone, modifier: Modifier) {
    Box(
        modifier
            .size(70.dp)
            .shadow(
                elevation = 10.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color.Black.copy(alpha = 0.7f)
            )
            .background(GoldTheme.Navy2, CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(tone.top, tone.bottom)))
            .border(3.dp, GoldTheme.Gold, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (letter != null) {
            BasicText(
                letter.trim().take(1).uppercase(),
                style = displayStyle(32.sp, tone.content, TextAlign.Center),
                maxLines = 1
            )
        } else if (icon != null) {
            LudoIconView(icon, iconSize = 30.dp, tint = tone.content)
        }
    }
}

/** Red round X. The visible circle is 40dp, the tap area 48dp. */
@Composable
private fun CloseX(onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(48.dp)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = "Close",
                onClick = onClick
            )
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(40.dp)
                .drawBehind {
                    drawCircle(
                        color = Color(0xFF9B201B),
                        radius = this.size.minDimension / 2f,
                        center = Offset(this.size.width / 2f, this.size.height / 2f + 3.dp.toPx())
                    )
                }
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFFF06B60), Color(0xFFD6362F))))
                .border(2.dp, Color(0xFFFFD0CB), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            LudoIconView(LudoIcon.Close, iconSize = 20.dp, tint = Color.White)
        }
    }
}
