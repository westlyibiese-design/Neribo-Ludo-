package com.westly.ludo.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Space every screen leaves free at the bottom for the footer line. */
val FooterSpace = 22.dp

@Composable
fun NeriboFooter(modifier: Modifier = Modifier) {
    BasicText(
        "© NERIBO GROUP 2026",
        modifier = modifier,
        style = TextStyle(
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 4f)
        ),
        maxLines = 1,
        softWrap = false
    )
}
