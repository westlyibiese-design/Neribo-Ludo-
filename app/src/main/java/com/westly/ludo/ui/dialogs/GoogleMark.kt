package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val GRed = Color(0xFFEA4335)
private val GYellow = Color(0xFFFBBC05)
private val GGreen = Color(0xFF34A853)
private val GBlue = Color(0xFF4285F4)

/**
 * The HTML's ".g": a bold Arial-style "G" (24sp) filled with a four-colour conic gradient, red at
 * the top, yellow on the right, green at the bottom and blue on the left (CSS: from -45deg).
 * A sweep gradient starts at 3 o'clock, so each colour band is 90 degrees wide and turned by 90.
 * Decorative only.
 */
@Composable
fun GoogleMark(modifier: Modifier = Modifier) {
    val sweep = Brush.sweepGradient(
        0.000f to GYellow,
        0.125f to GYellow,
        0.125f to GGreen,
        0.375f to GGreen,
        0.375f to GBlue,
        0.625f to GBlue,
        0.625f to GRed,
        0.875f to GRed,
        0.875f to GYellow,
        1.000f to GYellow
    )
    BasicText(
        "G",
        modifier = modifier.clearAndSetSemantics { },
        style = TextStyle(
            brush = sweep,
            fontSize = 24.sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.ExtraBold
        ),
        maxLines = 1
    )
}
