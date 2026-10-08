package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn in code (the project has no icon library). Every icon is the same SVG path
 * as in ludo-mate-dialogs.html, on a 24 x 24 grid with a 2 wide round stroke.
 *
 * Chevron, Book and Sliders are not in the HTML: they belong to the Game settings menu.
 */
enum class LudoIcon {
    Close, Flag, Power, Gear, Exit, Pen, Eye, Redo, Bars, Chevron, Book, Sliders,
    Star, Sound, Mute, Vibrate, Users, Crown, Dice, Next
}

/**
 * The path text of each icon. Arc flags are written with spaces ("0 1 0") so the path reader
 * never has to guess where one number ends.
 */
private fun pathText(icon: LudoIcon): String = when (icon) {
    LudoIcon.Close -> "M6 6l12 12M18 6L6 18"
    LudoIcon.Flag -> "M5 21V4m0 1h11l-2 4 2 4H5"
    LudoIcon.Power -> "M12 3v8M6.5 6.5a7 7 0 1 0 11 0"
    LudoIcon.Gear ->
        "M12 15a3 3 0 1 0 0 -6a3 3 0 0 0 0 6zM12 2v3M12 19v3M2 12h3M19 12h3" +
            "M5 5l2 2M17 17l2 2M5 19l2 -2M17 7l2 -2"
    LudoIcon.Exit -> "M10 4H5v16h5M16 8l4 4 -4 4M9 12h11"
    LudoIcon.Pen -> "M4 20l1 -4L16 5l3 3L8 19zM14 7l3 3"
    LudoIcon.Eye ->
        "M2 12s4 -7 10 -7s10 7 10 7s-4 7 -10 7S2 12 2 12z" +
            "M12 9a3 3 0 1 0 0 6a3 3 0 0 0 0 -6"
    LudoIcon.Redo -> "M4 12a8 8 0 1 0 3 -6.2M4 4v4h4"
    LudoIcon.Bars -> "M5 20V10M12 20V4M19 20v-7"
    LudoIcon.Star -> "M12 3l2.8 5.8 6.2 0.9 -4.5 4.4 1 6.2 -5.5 -3 -5.5 3 1 -6.2L3 9.7l6.2 -0.9z"
    LudoIcon.Sound ->
        "M4 9v6h4l5 4V5L8 9zM16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"
    LudoIcon.Mute -> "M4 9v6h4l5 4V5L8 9zM17 9l5 6M22 9l-5 6"
    LudoIcon.Vibrate ->
        "M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1H8a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1zM11 18h2"
    LudoIcon.Users ->
        "M9 11a3 3 0 1 0 0 -6a3 3 0 0 0 0 6M3 19c0 -3 3 -5 6 -5s6 2 6 5" +
            "M17 11a3 3 0 0 0 0 -6M18 14c2 0.5 3 2.5 3 5"
    LudoIcon.Crown -> "M3 8l4 4 5 -7 5 7 4 -4 -2 11H5z"
    LudoIcon.Dice ->
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2z" +
            "M8 8h0.01M16 8h0.01M12 12h0.01M8 16h0.01M16 16h0.01"
    LudoIcon.Next -> "M5 12h14M13 6l6 6 -6 6"
    // Not in the HTML (Game settings menu only)
    LudoIcon.Chevron -> "M9 6l6 6 -6 6"
    LudoIcon.Book -> "M5 3H19V21H5zM9 3V21M12 8H16M12 12H16"
    LudoIcon.Sliders ->
        "M4 7h3M11 7h9M4 12h9M17 12h3M4 17h3M11 17h9" +
            "M11 7a2 2 0 1 0 -4 0a2 2 0 1 0 4 0zM17 12a2 2 0 1 0 -4 0a2 2 0 1 0 4 0z" +
            "M11 17a2 2 0 1 0 -4 0a2 2 0 1 0 4 0z"
}

private fun buildIconPath(icon: LudoIcon): Path = PathParser().parsePathString(pathText(icon)).toPath()

/** Draws [icon] in [tint]. Icons are decorative: put the contentDescription on the button around them. */
@Composable
fun LudoIconView(
    icon: LudoIcon,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    tint: Color = Color.White
) {
    val path = remember(icon) { buildIconPath(icon) }
    Canvas(modifier.size(iconSize)) {
        val s = this.size.minDimension / 24f
        scale(s, pivot = Offset.Zero) {
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
