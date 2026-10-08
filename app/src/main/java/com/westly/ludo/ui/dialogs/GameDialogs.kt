package com.westly.ludo.ui.dialogs

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.westly.ludo.R
import com.westly.ludo.game.GameSettings
import com.westly.ludo.game.PlayerNames
import com.westly.ludo.online.OnlineAuth
import com.westly.ludo.ui.MenuBackground
import kotlinx.coroutines.launch

// Glue between the game and the gold dialogs. Each function here shows one gold dialog on the
// picture that the old screen used, and hands the game's data and callbacks to it.

private fun Context.hostActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** The sign-in dialog with the real Google sign-in behind it. */
@Composable
fun GoldSignInPanel(auth: OnlineAuth, onSuccess: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { auth.clearError() }
    GoldSignInDialog(
        onSignIn = {
            val activity = context.hostActivity()
            if (activity != null) {
                scope.launch {
                    if (auth.signIn(activity)) onSuccess()
                }
            }
        },
        onCancel = { if (!auth.busy) onClose() },
        signInLabel = if (auth.error != null) "Try again" else "Sign in with Google",
        busy = auth.busy,
        status = auth.error,
        detail = if (auth.error != null) auth.errorDetail else null,
        onAddAccount = if (auth.error != null && auth.canAddAccount) {
            {
                try {
                    context.hostActivity()?.startActivity(auth.addAccountIntent())
                } catch (e: Exception) {
                    // No settings screen to open on this phone: the message above still tells what to do.
                }
            }
        } else {
            null
        }
    )
}

/** Change names for the four saved player names. */
@Composable
fun GoldPlayerNamesDialog(names: PlayerNames, onClose: () -> Unit) {
    GoldChangeNamesDialog(
        currentNames = names.names.toList(),
        defaultNames = PlayerNames.DEFAULTS,
        onSave = { list -> list.forEachIndexed { i, n -> names.set(i, n) } },
        onClose = onClose,
        maxLength = PlayerNames.MAX_LENGTH
    )
}

/** The Game settings menu on the settings picture. Settings, Rules and Configuration open their pages. */
@Composable
fun GoldSettingsHost(
    names: PlayerNames,
    onSound: () -> Unit,
    onRules: () -> Unit,
    onConfiguration: () -> Unit,
    onClose: () -> Unit
) {
    val namesOpen = remember { androidx.compose.runtime.mutableStateOf(false) }
    MenuBackground(R.drawable.bg_settings) {}
    GoldGameSettingsDialog(
        onSettings = onSound,
        onRules = onRules,
        onConfiguration = onConfiguration,
        onChangeNames = { namesOpen.value = true },
        onClose = onClose
    )
    if (namesOpen.value) GoldPlayerNamesDialog(names, onClose = { namesOpen.value = false })
}

@Composable
fun GoldSoundHost(settings: GameSettings, onClose: () -> Unit) {
    MenuBackground(R.drawable.bg_settings) {}
    GoldSoundVibrationDialog(
        soundOn = settings.soundOn,
        vibrationOn = settings.vibrationOn,
        onSound = { settings.chooseSound(it) },
        onVibration = { settings.chooseVibration(it) },
        onDone = onClose
    )
}

@Composable
fun GoldConfigHost(settings: GameSettings, onClose: () -> Unit) {
    MenuBackground(R.drawable.bg_settings) {}
    GoldConfigurationDialog(
        boardType = settings.boardType,
        speed = settings.speed,
        level = settings.level,
        onBoardType = { settings.chooseBoard(it) },
        onSpeed = { settings.chooseSpeed(it) },
        onLevel = { settings.chooseLevel(it) },
        onDone = onClose
    )
}

/** "Who's playing?" for a new Family game. [onStart] gets the names (2 to 4 of them). */
@Composable
fun GoldFamilyHost(onStart: (List<String>) -> Unit, onBack: () -> Unit) {
    // The dialog calls onSave and then onClose; after a save we must not also go back.
    val saved = remember { booleanArrayOf(false) }
    MenuBackground(R.drawable.bg_modes) {}
    GoldFamilyDialog(
        onSave = { list ->
            saved[0] = true
            onStart(list)
        },
        onClose = { if (!saved[0]) onBack() }
    )
}
