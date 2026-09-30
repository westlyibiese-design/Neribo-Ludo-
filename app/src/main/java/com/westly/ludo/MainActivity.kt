package com.westly.ludo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.westly.ludo.game.LudoGame
import com.westly.ludo.game.Phase
import com.westly.ludo.ui.CounterOrb
import com.westly.ludo.ui.ExitButton
import com.westly.ludo.ui.LudoBoard
import com.westly.ludo.ui.MenuButton
import com.westly.ludo.ui.Palette
import com.westly.ludo.ui.PlayerBadge
import com.westly.ludo.ui.PointingHand
import com.westly.ludo.ui.TurnPill
import kotlin.math.sin
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val game = LudoGame()

    private fun prefs() = getSharedPreferences("ludomate", Context.MODE_PRIVATE)

    private fun saveGame() {
        prefs().edit().putString("save", game.toSaveString()).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs().getString("save", null)?.let { game.restore(it) }
        setContent { LudoScreen(game) }
    }

    override fun onPause() {
        super.onPause()
        saveGame()
    }

    override fun onStop() {
        super.onStop()
        saveGame()
    }
}

/**
 * The game screen around the board (Phase 2 layout, unchanged) wired to the Phase 3 game.
 * Every size is a multiple of `u`, which is 1% of the screen width, so the layout
 * scales with the device.
 */
@Composable
fun LudoScreen(game: LudoGame) {
    val scope = rememberCoroutineScope()

    // Soft 0..1 pulse used by the glowing pieces and the dice hint.
    val pulse = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(game.phase) {
        if (game.phase == Phase.Choose || game.phase == Phase.AwaitRoll) {
            while (true) {
                val t = withFrameNanos { it } / 1_000_000_000f
                pulse.floatValue = (sin(t * 5f) + 1f) / 2f
            }
        }
    }
    val choosing = game.phase == Phase.Choose
    val over = game.phase == Phase.GameOver

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
                    PlayerBadge(
                        "Player 2", 0, u,
                        Modifier.align(Alignment.BottomStart).padding(start = u * 12f)
                            .alpha(if (game.activePlayer == 1) 1f else 0.5f)
                    )
                    PlayerBadge(
                        "Player 1", 0, u,
                        Modifier.align(Alignment.BottomEnd).padding(end = u * 11f)
                            .alpha(if (game.activePlayer == 0) 1f else 0.5f)
                    )
                }

                // Board with the two dice
                LudoBoard(
                    Modifier.fillMaxWidth().height(u * 100f),
                    leftDie = game.face1,
                    rightDie = game.face2,
                    pieces = { game.pieceViews() },
                    pulse = { pulse.floatValue },
                    rollHint = game.phase == Phase.AwaitRoll,
                    onBoardTap = { row, col -> scope.launch { game.onBoardTap(row, col) } }
                )

                // Bottom: dice indicators (blue = first die, red = total, green = second die)
                Spacer(Modifier.height(u * 2.5f))
                Row(horizontalArrangement = Arrangement.spacedBy(u * 4f)) {
                    CounterOrb(
                        game.die1, Palette.Blue, u * 13.2f,
                        Modifier
                            .alpha(if (game.used1) 0.4f else 1f)
                            .then(
                                if (choosing && game.selectedDie == 0)
                                    Modifier.border(u * 0.7f, Color.White, CircleShape)
                                else Modifier
                            )
                            .clickable(enabled = choosing) { game.selectDie(0) }
                    )
                    CounterOrb(game.die1 + game.die2, Palette.Red, u * 13.2f)
                    CounterOrb(
                        game.die2, Palette.Green, u * 13.2f,
                        Modifier
                            .alpha(if (game.used2) 0.4f else 1f)
                            .then(
                                if (choosing && game.selectedDie == 1)
                                    Modifier.border(u * 0.7f, Color.White, CircleShape)
                                else Modifier
                            )
                            .clickable(enabled = choosing) { game.selectDie(1) }
                    )
                }
                Spacer(Modifier.height(u * 2f))
                TurnPill(
                    if (over) "Player ${game.winner + 1} Wins!" else "Player ${game.activePlayer + 1} Turn",
                    u,
                    textScale = 0.75f
                )
            }
        }
    }
}
