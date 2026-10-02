package com.westly.ludo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.westly.ludo.R
import com.westly.ludo.game.PlayerNames
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Ludo Mate home, Settings (visual only) and Game Mode screens.

@Composable
private fun Dp.sp(): TextUnit = with(LocalDensity.current) { this@sp.toSp() }

private fun menuText(size: TextUnit, color: Color = Color.White) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = FontWeight.ExtraBold,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 3f), 5f)
)

@Composable
private fun MenuBackground(image: Int, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.WoodLight, Palette.WoodDark)))
    ) {
        // The picture fills the whole screen (also behind the system bars); the menu sits on top.
        Image(
            painter = painterResource(image),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(bottom = FooterSpace)) { content() }
    }
}

/** Big glossy rounded button. [onClick] null = shown but inactive. */
@Composable
private fun GlossButton(
    text: String,
    swatch: Swatch,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "btnScale")
    Box(
        modifier
            .width(width)
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (dimmed) 0.55f else 1f)
            .background(Brush.verticalGradient(listOf(swatch.light, swatch.base, swatch.dark)), shape)
            .border(height * 0.04f, swatch.dark.shade(0.7f), shape)
            .drawBehind {
                drawRoundRect(
                    Color.White.copy(alpha = 0.22f),
                    Offset(size.width * 0.05f, size.height * 0.07f),
                    Size(size.width * 0.90f, size.height * 0.40f),
                    CornerRadius(size.height * 0.3f)
                )
            }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = onClick != null
            ) { onClick?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text,
                style = menuText((height * (if (subtitle == null) 0.42f else 0.34f)).sp()),
                maxLines = 1,
                softWrap = false
            )
            if (subtitle != null) {
                BasicText(
                    subtitle,
                    style = menuText((height * 0.2f).sp(), Color.White.copy(alpha = 0.9f)),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** Google "G" mark. Visual only: it has no click handler and signs nobody in. */
@Composable
private fun GoogleGIcon(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        drawCircle(Color.Black.copy(alpha = 0.35f), s / 2f, c + Offset(0f, s * 0.03f))
        drawCircle(Color.White, s / 2f, c)
        val sw = s * 0.12f
        val r = s * 0.30f
        val tl = Offset(c.x - r, c.y - r)
        val box = Size(r * 2f, r * 2f)
        val red = Color(0xFFEA4335)
        val yellow = Color(0xFFFBBC05)
        val green = Color(0xFF34A853)
        val blue = Color(0xFF4285F4)
        drawArc(red, 215f, 100f, false, tl, box, style = Stroke(sw))
        drawArc(yellow, 135f, 80f, false, tl, box, style = Stroke(sw))
        drawArc(green, 45f, 90f, false, tl, box, style = Stroke(sw))
        drawArc(blue, 0f, 45f, false, tl, box, style = Stroke(sw))
        drawLine(blue, Offset(c.x, c.y), Offset(c.x + r + sw / 2f, c.y), strokeWidth = sw)
    }
}

/** "Ludo Mate" title with four pawns above it. */
@Composable
private fun LudoMateLogo(u: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(u * 56f).height(u * 17f)) {
            val colors = listOf(Palette.Green, Palette.Yellow, Palette.Blue, Palette.Red)
            val r = size.height * 0.2f
            val step = size.width / 4f
            for (i in 0 until 4) {
                drawPawn(Offset(step * (i + 0.5f), size.height * 0.5f), r, colors[i])
            }
        }
        Spacer(Modifier.height(u * 1.5f))
        val outlinePx = with(LocalDensity.current) { (u * 1.3f).toPx() }
        Box(contentAlignment = Alignment.Center) {
            // Dark brown outline behind the gold letters, so the title reads on any background.
            BasicText(
                "Ludo Mate",
                style = TextStyle(
                    color = Color(0xFF3F2916),
                    fontSize = (u * 14.5f).sp(),
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    drawStyle = Stroke(width = outlinePx, join = StrokeJoin.Round)
                ),
                maxLines = 1,
                softWrap = false
            )
            BasicText(
                "Ludo Mate",
                style = TextStyle(
                    brush = Brush.verticalGradient(listOf(Color(0xFFFFF3C4), Color(0xFFFFB92E))),
                    fontSize = (u * 14.5f).sp(),
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 6f), 10f)
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun HomeScreen(onSettings: () -> Unit, onGame: () -> Unit) {
    MenuBackground(R.drawable.bg_home) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 185f)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(u * 3f)) {
                    GoogleGIcon(u * 11f)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LudoMateLogo(u)
                }
                GlossButton("Game", Palette.Green, u * 72f, u * 17f, onClick = onGame)
                Spacer(Modifier.height(u * 4f))
                GlossButton("Settings", Palette.Orange, u * 72f, u * 17f, onClick = onSettings)
                Spacer(Modifier.height(u * 10f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings: visual only. The rows do nothing in this phase.
// ---------------------------------------------------------------------------

private fun DrawScope.gearIcon(c: Offset, r: Float) {
    drawCircle(Color.White, r * 0.5f, c, style = Stroke(r * 0.22f))
    for (i in 0 until 8) {
        val a = (i * 45f) * (PI.toFloat() / 180f)
        drawLine(
            Color.White,
            Offset(c.x + cos(a) * r * 0.55f, c.y + sin(a) * r * 0.55f),
            Offset(c.x + cos(a) * r * 0.8f, c.y + sin(a) * r * 0.8f),
            strokeWidth = r * 0.2f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.rulesIcon(c: Offset, r: Float) {
    drawRoundRect(Color.White, Offset(c.x - r * 0.5f, c.y - r * 0.65f), Size(r, r * 1.3f), CornerRadius(r * 0.15f))
    for (i in -1..1) {
        drawLine(
            Color(0xFF555555),
            Offset(c.x - r * 0.28f, c.y + i * r * 0.32f),
            Offset(c.x + r * 0.28f, c.y + i * r * 0.32f),
            strokeWidth = r * 0.1f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.configIcon(c: Offset, r: Float) {
    val knobs = listOf(-0.25f, 0.2f, -0.05f)
    for (i in -1..1) {
        val y = c.y + i * r * 0.42f
        drawLine(Color.White, Offset(c.x - r * 0.65f, y), Offset(c.x + r * 0.65f, y), strokeWidth = r * 0.12f, cap = StrokeCap.Round)
        drawCircle(Color.White, r * 0.15f, Offset(c.x + knobs[i + 1] * r * 1.3f, y))
    }
}

private fun DrawScope.nameIcon(c: Offset, r: Float) {
    drawCircle(Color.White, r * 0.26f, Offset(c.x, c.y - r * 0.25f))
    drawArc(
        Color.White, 180f, 180f, true,
        Offset(c.x - r * 0.5f, c.y + r * 0.05f), Size(r, r * 0.9f)
    )
}

@Composable
private fun SettingsRow(
    label: String,
    swatch: Swatch,
    u: Dp,
    onClick: (() -> Unit)? = null,
    icon: DrawScope.(Offset, Float) -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .width(u * 78f)
            .height(u * 16f)
            .background(Brush.verticalGradient(listOf(Palette.PillLight, Palette.PillDark)), shape)
            .border(1.dp, Palette.PillEdge, shape)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
    ) {
        Canvas(Modifier.align(Alignment.CenterStart).padding(start = u * 2f).size(u * 12f)) {
            val r = size.minDimension / 2f * 0.94f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawGlossyDisc(c, r, swatch)
            icon(c, r)
        }
        BasicText(
            label,
            style = menuText((u * 6.4f).sp()).copy(textAlign = TextAlign.Start),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = u * 17f)
        )
    }
}

@Composable
fun SettingsScreen(names: PlayerNames, onClose: () -> Unit) {
    var namesOpen by remember { mutableStateOf(false) }
    // Soft entrance: fade and grow a little.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(320)) }

    Box(Modifier.fillMaxSize()) {
    MenuBackground(R.drawable.bg_settings) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 160f)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = enter.value
                        val sc = 0.94f + 0.06f * enter.value
                        scaleX = sc
                        scaleY = sc
                    }
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                        GlossButton(
                            "Settings", Palette.Orange, u * 56f, u * 14f,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        ExitButton(
                            u * 11.8f,
                            Modifier.align(Alignment.CenterEnd).clickable { onClose() }
                        )
                    }
                    Spacer(Modifier.height(u * 8f))
                    SettingsRow("Settings", Palette.Blue, u) { c, r -> gearIcon(c, r) }
                    Spacer(Modifier.height(u * 4f))
                    SettingsRow("Rules", Palette.Green, u) { c, r -> rulesIcon(c, r) }
                    Spacer(Modifier.height(u * 4f))
                    SettingsRow("Configuration", Palette.Yellow, u) { c, r -> configIcon(c, r) }
                    Spacer(Modifier.height(u * 4f))
                    SettingsRow("Change Names", Palette.Red, u, onClick = { namesOpen = true }) { c, r -> nameIcon(c, r) }
                }
            }
        }
    }
    if (namesOpen) ChangeNamesDialog(names, onClose = { namesOpen = false })
    }
}

// ---------------------------------------------------------------------------
// Game mode selection
// ---------------------------------------------------------------------------

@Composable
fun GameModeScreen(onBack: () -> Unit, onYouAndComputer: () -> Unit, onTournament: () -> Unit) {
    MenuBackground(R.drawable.bg_modes) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 170f)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                    GlossButton(
                        "Game Mode", Palette.Green, u * 56f, u * 14f,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.CenterEnd).clickable { onBack() }
                    )
                }
                Spacer(Modifier.height(u * 8f))
                GlossButton("You & Computer", Palette.Blue, u * 78f, u * 18f, onClick = onYouAndComputer)
                Spacer(Modifier.height(u * 4f))
                GlossButton(
                    "Tournament", Palette.Orange, u * 78f, u * 18f,
                    subtitle = "4 players", onClick = onTournament
                )
                Spacer(Modifier.height(u * 4f))
                GlossButton("Family", Palette.Green, u * 78f, u * 18f, subtitle = "Coming soon", dimmed = true)
                Spacer(Modifier.height(u * 4f))
                GlossButton("Connect and Play", Palette.Red, u * 78f, u * 18f, subtitle = "Coming soon", dimmed = true)
            }
        }
    }
}
