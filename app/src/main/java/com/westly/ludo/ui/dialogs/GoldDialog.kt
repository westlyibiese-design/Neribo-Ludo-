package com.westly.ludo.ui.dialogs

import android.os.Build
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.launch

/** --ease: cubic-bezier(.2,.8,.2,1) */
private val EntryEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** The CSS default "ease", used by the scrim fade. */
private val FadeEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * The window every Ludo Mate dialog sits in. Full-screen dark scrim (fades in over 250ms, with a
 * 12px blur of the game behind on Android 12 and newer).
 *
 * The content comes in one of two ways, like the HTML:
 *  - normal dialog ([rise] = false): fades, scales 0.94 -> 1 and slides up 18dp in 320ms
 *  - full-screen page ([rise] = true): fades and slides up 24dp in 400ms, no scale
 * Both are skipped when animations are off.
 *
 * [onBack] runs when the Back button is pressed. Pass null to block Back (full-screen results).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DialogWindow(
    onBack: (() -> Unit)?,
    rise: Boolean = false,
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

        // backdrop-filter: blur(12px) on the layer. Only phones with Android 12+ (and blur turned
        // on in the system) show it; everywhere else the scrim alone is used.
        val blurPx = (12f * LocalDensity.current.density).toInt()
        LaunchedEffect(window) {
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val params = window.attributes
                params.blurBehindRadius = blurPx
                window.attributes = params
            }
        }

        BackHandler(enabled = true) { onBack?.invoke() }

        val fade = remember { Animatable(if (motion) 0f else 1f) }
        val progress = remember { Animatable(if (motion) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (motion) {
                launch { fade.animateTo(1f, tween(250, easing = FadeEasing)) }
                progress.animateTo(1f, tween(if (rise) 400 else 320, easing = EntryEasing))
            }
        }
        val slidePx = with(LocalDensity.current) { (if (rise) 24.dp else 18.dp).toPx() }

        CompositionLocalProvider(
            LocalLudoFonts provides fonts,
            LocalMotionEnabled provides motion
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fade.value }
                        .background(GoldTheme.Scrim)
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = progress.value
                            alpha = p
                            if (!rise) {
                                scaleX = 0.94f + 0.06f * p
                                scaleY = 0.94f + 0.06f * p
                            }
                            translationY = (1f - p) * slidePx
                        },
                    content = content
                )
            }
        }
    }
}

/** The shape of the gold frame (30dp corners) shared by the dialogs and the OUT stamp card. */
internal val GoldFrameShape = RoundedCornerShape(30.dp)

/**
 * The gold frame: navy gradient, 2dp gold border, soft gold glow and a thin white highlight along
 * the top edge. Used by [GoldDialog] and by the OUT stamp card on the round-result page.
 */
internal fun Modifier.goldFrame(): Modifier = this
    .shadow(
        elevation = 28.dp,
        shape = GoldFrameShape,
        clip = false,
        ambientColor = GoldTheme.Gold.copy(alpha = 0.55f),
        spotColor = Color.Black.copy(alpha = 0.8f)
    )
    .background(Brush.verticalGradient(listOf(GoldTheme.Navy1, GoldTheme.Navy2)), GoldFrameShape)
    .border(2.dp, GoldTheme.Gold, GoldFrameShape)
    .drawBehind {
        // thin white 12% highlight along the top edge
        drawLine(
            color = Color(0x1FFFFFFF),
            start = Offset(30.dp.toPx(), 2.5.dp.toPx()),
            end = Offset(this.size.width - 30.dp.toPx(), 2.5.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
    }

/**
 * The standard gold-framed dialog: icon badge on the top edge, gold title, optional lead text,
 * a red close button, and [content] below. Content scrolls on small screens or large fonts.
 *
 * [onClose] runs for the X button and the Back button.
 * Give [badgeIcon] for an icon badge, or [badgeLetter] for a letter badge.
 * [leadContent] replaces [lead] when the line under the title is more than plain text.
 * The lead line is hidden on screens 640dp tall or less, like the HTML does.
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
    leadContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    DialogWindow(onBack = onClose) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            val minHeight = if (maxHeight > 32.dp) maxHeight - 32.dp else 0.dp
            // 4dp here + 12dp inside GoldCard = the 16dp side margin of the HTML layer
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = minHeight),
                    contentAlignment = Alignment.Center
                ) {
                    GoldCard(
                        title = title,
                        lead = lead,
                        leadContent = leadContent,
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
    leadContent: (@Composable () -> Unit)?,
    onClose: () -> Unit,
    showClose: Boolean,
    badgeIcon: LudoIcon?,
    badgeLetter: String?,
    badgeTone: ChunkyTone,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val onePx = LocalDensity.current.density
    val shortScreen = LocalConfiguration.current.screenHeightDp <= 640
    val hasLead = !shortScreen && (leadContent != null || lead != null)
    // Outer box is 12dp wider each side and BadgeRoom taller than the frame, so the badge and the
    // close button sit inside its bounds and can be tapped.
    Box(modifier.widthIn(max = 444.dp).fillMaxWidth()) {
        Column(
            Modifier
                .padding(start = 12.dp, end = 12.dp, top = BadgeRoom)
                .fillMaxWidth()
                .goldFrame()
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
                    shadow = Shadow(Color(0x77000000), Offset(0f, 2f * onePx), 0f),
                    lineHeight = 29.7.sp,
                    letterSpacing = (-0.27).sp
                )
            )
            if (hasLead) {
                Spacer(Modifier.height(8.dp))
                if (leadContent != null) {
                    leadContent()
                } else if (lead != null) {
                    BasicText(
                        lead,
                        modifier = Modifier.widthIn(max = 326.dp),
                        style = bodyStyle(16.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            content()
        }

        IconBadge(
            icon = badgeIcon,
            letter = badgeLetter,
            tone = badgeTone,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 5.dp)
        )

        if (showClose) {
            CloseX(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 26.dp)
            )
        }
    }
}

/** 64dp badge: gradient, 3dp gold ring, 3dp dark outer ring. Centred on the frame's top edge. */
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

/**
 * Red round X. The visible circle is 40dp, the tap area 48dp. Its circle sits 10dp above and
 * 6dp outside the frame's top-right corner, like the HTML.
 */
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
