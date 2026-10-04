package com.westly.ludo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.westly.ludo.R
import com.westly.ludo.online.OnlineAuth
import com.westly.ludo.online.OnlineUser
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Online play: the Online menu, the Google sign-in panel and the account dialog.
// The screens only show what OnlineAuth holds and call its functions; no networking in here.
// ---------------------------------------------------------------------------

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Same look as the Connect menu screens: a title pill with the back button, then the content. */
@Composable
private fun OnlineFrame(
    title: String,
    titleWidth: Float,
    onBack: () -> Unit,
    content: @Composable ColumnScope.(Dp) -> Unit
) {
    MenuBackground(R.drawable.bg_modes) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 170f)
            // The scroll is only a safety net for very small phones; normally everything fits.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                    GlossButton(
                        title, Palette.Green, u * titleWidth, u * 14f,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.CenterEnd).clickable { onBack() }
                    )
                }
                content(u)
            }
        }
    }
}

@Composable
private fun OnlineText(
    text: String,
    u: Dp,
    size: Float,
    color: Color = Color.White,
    maxLines: Int = 4
) {
    BasicText(
        text,
        modifier = Modifier.fillMaxWidth(),
        style = menuText((u * size).sp(), color),
        maxLines = maxLines
    )
}

/**
 * The Online screen. In this phase it only shows who is signed in and under which name the player
 * will appear; Start a Room and Join a Room are shown but not active yet.
 */
@Composable
fun OnlineScreen(account: OnlineUser?, playerName: String, onBack: () -> Unit) {
    OnlineFrame("Online", 52f, onBack) { u ->
        Spacer(Modifier.height(u * 8f))
        Column(Modifier.width(u * 84f)) {
            OnlineText("Signed in as ${account?.name ?: "-"}", u, 4.6f, maxLines = 2)
            Spacer(Modifier.height(u * 1.5f))
            OnlineText("Playing as $playerName", u, 4.6f, maxLines = 2)
        }
        Spacer(Modifier.height(u * 6f))
        GlossButton("Start a Room", Palette.Green, u * 78f, u * 18f, subtitle = "Coming soon", dimmed = true)
        Spacer(Modifier.height(u * 4f))
        GlossButton("Join a Room", Palette.Blue, u * 78f, u * 18f, subtitle = "Coming soon", dimmed = true)
    }
}

/** Dimmed full-screen layer that blocks taps behind it (a private copy of the dialogs' own layer). */
@Composable
private fun OnlineScrim(content: @Composable BoxScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(interactionSource = source, indication = null) { }
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** A dark-teal rounded card in the menu look. [content] gets `u`, which is 1% of the screen width. */
@Composable
private fun OnlineCard(content: @Composable ColumnScope.(Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val u = minOf(maxWidth / 100f, maxHeight / 170f)
        val shape = RoundedCornerShape(u * 5f)
        Column(
            Modifier
                .width(u * 88f)
                .background(Brush.verticalGradient(listOf(Palette.PillLight, Palette.PillDark)), shape)
                .border(u * 0.6f, Palette.PillEdge, shape)
                .padding(horizontal = u * 5f, vertical = u * 6f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content(u)
        }
    }
}

/** The white "Sign in with Google" button with the Google "G". */
@Composable
private fun OnlineGoogleButton(u: Dp, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .width(u * 72f)
            .height(u * 14f)
            .background(Color.White, shape)
            .clickable(interactionSource = source, indication = null) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GoogleGIcon(u * 8f)
        Spacer(Modifier.width(u * 2.5f))
        BasicText(
            label,
            style = TextStyle(
                color = Color(0xFF1F1F1F),
                fontSize = (u * 4.4f).sp(),
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * The sign-in panel. It floats over the current screen. [onSuccess] is called once the player is
 * signed in; [onClose] when the panel is dismissed. A closed Google chooser is not an error, so
 * the panel just stays and the player can tap the button again or cancel.
 */
@Composable
fun SignInPanel(auth: OnlineAuth, onSuccess: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { auth.clearError() }
    BackHandler(true) {
        if (!auth.busy) onClose()
    }
    OnlineScrim {
        OnlineCard { u ->
            OnlineText("Sign in", u, 7f, maxLines = 1)
            Spacer(Modifier.height(u * 3f))
            OnlineText("Sign in with Google to play online with friends.", u, 4.2f, maxLines = 3)
            Spacer(Modifier.height(u * 5f))

            if (auth.busy) {
                OnlineText("Signing in...", u, 4.6f, maxLines = 1)
            } else {
                OnlineGoogleButton(
                    u,
                    label = if (auth.error != null) "Try again" else "Sign in with Google"
                ) {
                    val activity = context.findActivity()
                    if (activity != null) {
                        scope.launch {
                            if (auth.signIn(activity)) onSuccess()
                        }
                    }
                }
            }

            val message = auth.error
            if (message != null) {
                Spacer(Modifier.height(u * 3f))
                OnlineText(message, u, 3.8f, color = Color(0xFFFFD9A0), maxLines = 4)
                val detail = auth.errorDetail
                if (detail != null) {
                    Spacer(Modifier.height(u * 1.5f))
                    // Small technical line, used when troubleshooting.
                    BasicText(
                        detail,
                        modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = (u * 2.8f).sp(),
                            textAlign = TextAlign.Center
                        ),
                        maxLines = 4
                    )
                }
                if (auth.canAddAccount) {
                    Spacer(Modifier.height(u * 3f))
                    GlossButton(
                        "Add account", Palette.Blue, u * 56f, u * 12f,
                        onClick = {
                            try {
                                context.findActivity()?.startActivity(auth.addAccountIntent())
                            } catch (e: Exception) {
                                // No settings screen to open on this phone: the message above still tells what to do.
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(u * 5f))
            GlossButton(
                "Cancel", Palette.Orange, u * 44f, u * 12f,
                dimmed = auth.busy,
                onClick = if (auth.busy) null else onClose
            )
        }
    }
}

/** The account dialog opened from the Home "G" button when signed in. */
@Composable
fun AccountDialog(user: OnlineUser, onSignOut: () -> Unit, onClose: () -> Unit) {
    BackHandler(true) { onClose() }
    OnlineScrim {
        OnlineCard { u ->
            AccountLetterCircle(u * 20f, user.name)
            Spacer(Modifier.height(u * 3f))
            OnlineText(user.name, u, 6f, maxLines = 2)
            Spacer(Modifier.height(u * 1f))
            OnlineText("Signed in with Google", u, 3.8f, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
            Spacer(Modifier.height(u * 6f))
            GlossButton("Sign out", Palette.Orange, u * 62f, u * 14f, onClick = onSignOut)
            Spacer(Modifier.height(u * 3f))
            GlossButton("Close", Palette.Green, u * 62f, u * 14f, onClick = onClose)
        }
    }
}

/** A round badge with the first letter of [name]. Shown on the Home screen in place of the Google "G" when signed in. */
@Composable
internal fun AccountLetterCircle(diameter: Dp, name: String, modifier: Modifier = Modifier) {
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.minDimension
            val c = Offset(s / 2f, s / 2f)
            drawCircle(Color.Black.copy(alpha = 0.35f), s / 2f, c + Offset(0f, s * 0.03f))
            drawCircle(Color.White, s / 2f, c)
            drawCircle(Palette.Blue.base, s / 2f - s * 0.07f, c)
        }
        BasicText(
            letter,
            style = TextStyle(
                color = Color.White,
                fontSize = (diameter * 0.5f).sp(),
                fontWeight = FontWeight.ExtraBold
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}
