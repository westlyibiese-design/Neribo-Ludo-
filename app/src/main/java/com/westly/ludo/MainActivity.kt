package com.westly.ludo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.westly.ludo.ui.CounterOrb
import com.westly.ludo.ui.ExitButton
import com.westly.ludo.ui.LudoBoard
import com.westly.ludo.ui.MenuButton
import com.westly.ludo.ui.Palette
import com.westly.ludo.ui.PlayerBadge
import com.westly.ludo.ui.PointingHand
import com.westly.ludo.ui.TurnPill

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LudoScreen() }
    }
}

/**
 * Phase 2: the static game screen around the board.
 * Nothing here reacts to touch yet. Every size is a multiple of `u`, which is 1%
 * of the screen width, so the layout scales with the device.
 */
@Composable
fun LudoScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.WoodLight, Palette.WoodDark)))
            .systemBarsPadding()
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Whole layout is about 156u tall; shrink if the screen is short.
            val u = minOf(maxWidth / 100f, maxHeight / 158f)

            Column(
                modifier = Modifier.width(u * 100f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top: menu button, pointing hand, exit button, then player names + scores
                Box(Modifier.fillMaxWidth().height(u * 29f)) {
                    MenuButton(u * 11.8f, Modifier.align(Alignment.TopStart).padding(start = u * 1f, top = u * 1f))
                    ExitButton(u * 11.8f, Modifier.align(Alignment.TopEnd).padding(end = u * 1f, top = u * 1f))
                    PointingHand(u * 10f, Modifier.align(Alignment.TopCenter))
                    PlayerBadge("Player 2", 0, u, Modifier.align(Alignment.BottomStart).padding(start = u * 12f))
                    PlayerBadge("Player 1", 0, u, Modifier.align(Alignment.BottomEnd).padding(end = u * 11f))
                }

                // Board with the two dice
                LudoBoard(Modifier.fillMaxWidth().height(u * 100f))

                // Bottom: three counters, then the turn label
                Spacer(Modifier.height(u * 2.5f))
                Row(horizontalArrangement = Arrangement.spacedBy(u * 4f)) {
                    CounterOrb(0, Palette.Blue, u * 13.2f)
                    CounterOrb(0, Palette.Red, u * 13.2f)
                    CounterOrb(0, Palette.Green, u * 13.2f)
                }
                Spacer(Modifier.height(u * 2f))
                TurnPill("Your Turn", u)
            }
        }
    }
}
