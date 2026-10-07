package com.westly.ludo.ui.dialogs

import androidx.compose.ui.graphics.Color
import com.westly.ludo.ui.Palette

/** Colours that only the gold-and-navy dialogs use. Everything else comes from Palette.kt. */
object GoldTheme {
    val Bg = Color(0xFF060D1E)
    val Navy1 = Color(0xFF16305C)
    val Navy2 = Color(0xFF0A1832)
    val Gold = Color(0xFFF0C35A)
    val Cream = Palette.Cream
    val Muted = Color(0xFFA3BDB5)

    /** rgb(3,8,22) at 72% */
    val Scrim = Color(0xB8030816)

    /** cream at 13%: dividers and ghost borders */
    val Line = Color(0x21F5F2E9)

    /** gold at 28%: panel and field borders */
    val GoldLine = Color(0x47F0C35A)

    /** gold at 45%: option chip border */
    val GoldChip = Color(0x73F0C35A)

    /** gold at 35%: two-way toggle border */
    val GoldSeg = Color(0x59F0C35A)

    /** black at 24% (panels, fields) and 30% (chips) */
    val PanelFill = Color(0x3D000000)
    val ChipFill = Color(0x4D000000)

    /** focus colour of a text field and its soft ring (yellow at 18%) */
    val Focus = Color(0xFFFFD85E)
    val FocusRing = Color(0x2EFFD85E)

    /** pale red used for error text and struck-through rounds */
    val ErrorText = Color(0xFFFFB4AD)

    val TagBad = Color(0xFFD6362F)
    val TagGood = Color(0xFF2E9B4E)
    val TagNeutral = Color(0x1AFFFFFF)
}

/**
 * One 3D button colour set. [top] and [bottom] make the vertical gradient, [edge] is the solid
 * strip under the button, [content] is the text and icon colour.
 */
class ChunkyTone(
    val top: Color,
    val bottom: Color,
    val edge: Color,
    val content: Color = Color.White,
    val innerBorder: Color? = null,
    val textShadow: Boolean = true
)

object ChunkyTones {
    val Green = ChunkyTone(Palette.Green.light, Palette.Green.base, Palette.Green.dark)
    val Red = ChunkyTone(Palette.Red.light, Palette.Red.base, Palette.Red.dark)
    val Gold = ChunkyTone(
        Color(0xFFFFD870), Color(0xFFE0A424), Color(0xFF8F5F0B),
        content = Color(0xFF3A2600), textShadow = false
    )
    val Navy = ChunkyTone(
        Color(0xFF2F5AA3), Color(0xFF1A3C78), Color(0xFF0C2150),
        innerBorder = Color(0x73F0C35A)
    )
    val Orange = ChunkyTone(Color(0xFFFF8A3D), Color(0xFFE2561A), Color(0xFF9C3508))
    val Blue = ChunkyTone(Palette.Blue.light, Palette.Blue.base, Palette.Blue.dark)
    val Yellow = ChunkyTone(
        Palette.Yellow.light, Palette.Yellow.base, Palette.Yellow.dark,
        content = Color(0xFF3D2A00), textShadow = false
    )
    val White = ChunkyTone(
        Color.White, Color.White, Color(0xFFB9BCC4),
        content = Color(0xFF1F2A37), textShadow = false
    )
}

/** A seat colour: [main] and [dark] (the dark one is the avatar edge). */
class SeatColor(val name: String, val main: Color, val dark: Color)

object SeatColors {
    /** Seat 0 = Red, 1 = Green, 2 = Yellow, 3 = Blue (same order as the game). */
    val all = listOf(
        SeatColor("Red", Color(0xFFF06B60), Color(0xFFB02A24)),
        SeatColor("Green", Color(0xFF55C173), Color(0xFF1B6A35)),
        SeatColor("Yellow", Color(0xFFFFD85E), Color(0xFFB58106)),
        SeatColor("Blue", Color(0xFF619EF2), Color(0xFF1B4E9A))
    )

    /** Grey, used for a player who is out. */
    val Grey = SeatColor("Grey", Color(0xFF8AA39C), Color(0xFF4B625C))

    fun of(seat: Int): SeatColor = all.getOrElse(seat) { Grey }

    fun name(seat: Int): String = all.getOrNull(seat)?.name ?: "Player"
}
