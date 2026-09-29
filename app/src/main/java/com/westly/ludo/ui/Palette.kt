package com.westly.ludo.ui

import androidx.compose.ui.graphics.Color

class Swatch(val light: Color, val base: Color, val dark: Color)

object Palette {
    val Green = Swatch(Color(0xFF55C173), Color(0xFF2E9B4E), Color(0xFF1B6A35))
    val Yellow = Swatch(Color(0xFFFFD85E), Color(0xFFF1B41B), Color(0xFFB58106))
    val Red = Swatch(Color(0xFFF06B60), Color(0xFFD6362F), Color(0xFF9B201B))
    val Blue = Swatch(Color(0xFF619EF2), Color(0xFF2D74D5), Color(0xFF1B4E9A))

    val Cream = Color(0xFFF5F2E9)
    val CreamLight = Color(0xFFFCFAF4)
    val GridLine = Color(0xFFCDC7B6)
    val Arrow = Color(0xD916222C)

    val FrameLight = Color(0xFF21704A)
    val FrameDark = Color(0xFF0D3A26)

    val CenterLight = Color(0xFF2B6B94)
    val CenterDark = Color(0xFF123F5E)

    val DieLight = Color(0xFFEE5B50)
    val DieDark = Color(0xFFBD2720)
    val DieEdge = Color(0xFF7A1410)
}

internal fun Color.shade(factor: Float): Color =
    Color(red * factor, green * factor, blue * factor, alpha)
