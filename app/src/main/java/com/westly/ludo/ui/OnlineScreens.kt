package com.westly.ludo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.westly.ludo.R
import com.westly.ludo.online.OnlineAuth
import com.westly.ludo.online.OnlineLink
import com.westly.ludo.online.OnlineRole
import com.westly.ludo.online.OnlineRoom
import com.westly.ludo.online.OnlineSession
import com.westly.ludo.online.OnlineUser
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Online play: the Online menu, Start a Room, Join a Room, the room screen, the Google sign-in
// panel and the account dialog. The screens only show what OnlineAuth / OnlineSession hold and
// call their functions; no networking in here.
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
    ime: Boolean = false,
    content: @Composable ColumnScope.(Dp) -> Unit
) {
    MenuBackground(R.drawable.bg_modes) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 170f)
            // The scroll is only a safety net for very small phones; normally everything fits.
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (ime) Modifier.imePadding() else Modifier)
                    .verticalScroll(rememberScrollState()),
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

/** The orange-tinted line used for problems and notices on the Online screens. */
private val OnlineWarn = Color(0xFFFFD9A0)

/** The four letters and digits a room code can use (no I, O, 0 or 1). */
private const val ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/**
 * The Online screen: who is signed in, the name used in games, and the Start a Room / Join a Room
 * buttons. When the server says this person is still in a live room, a Return to room button
 * appears above them.
 */
@Composable
fun OnlineScreen(
    account: OnlineUser?,
    playerName: String,
    session: OnlineSession,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onReturn: () -> Unit
) {
    LaunchedEffect(Unit) { session.checkMyRoom() }
    val enabled = !session.busy
    OnlineFrame("Online", 52f, onBack) { u ->
        Spacer(Modifier.height(u * 6f))
        Column(Modifier.width(u * 84f)) {
            OnlineText("Signed in as ${account?.name ?: "-"}", u, 4.6f, maxLines = 2)
            Spacer(Modifier.height(u * 1.5f))
            OnlineText("Playing as $playerName", u, 4.6f, maxLines = 2)
        }
        val message = session.notice ?: session.problem
        if (message != null) {
            Spacer(Modifier.height(u * 3f))
            Box(Modifier.width(u * 84f)) { OnlineText(message, u, 4f, color = OnlineWarn, maxLines = 3) }
        }
        Spacer(Modifier.height(u * 5f))
        val ref = session.myRoomRef
        if (ref != null) {
            val goBack: () -> Unit = {
                session.openRoom(ref.roomId) { ok -> if (ok) onReturn() }
            }
            GlossButton(
                "Return to room ${ref.code}", Palette.Orange, u * 78f, u * 18f,
                dimmed = !enabled, onClick = if (enabled) goBack else null
            )
            Spacer(Modifier.height(u * 4f))
        }
        GlossButton(
            "Start a Room", Palette.Green, u * 78f, u * 18f,
            subtitle = "Choose players and watchers",
            dimmed = !enabled, onClick = if (enabled) onCreate else null
        )
        Spacer(Modifier.height(u * 4f))
        GlossButton(
            "Join a Room", Palette.Blue, u * 78f, u * 18f,
            subtitle = "Type your friend's code",
            dimmed = !enabled, onClick = if (enabled) onJoin else null
        )
    }
}

// ---------------------------------------------------------------------------
// Start a Room
// ---------------------------------------------------------------------------

/** One round choice button (a number) in a row of choices. */
@Composable
private fun OnlineChoice(label: String, selected: Boolean, u: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(u * 3f)
    val source = remember { MutableInteractionSource() }
    val fill = if (selected) {
        Brush.verticalGradient(listOf(Palette.Green.light, Palette.Green.base, Palette.Green.dark))
    } else {
        Brush.verticalGradient(listOf(Palette.PillLight, Palette.PillDark))
    }
    Box(
        Modifier
            .size(u * 13.5f)
            .background(fill, shape)
            .border(u * 0.5f, if (selected) Color.White else Palette.PillEdge, shape)
            .clickable(interactionSource = source, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = menuText((u * 6.5f).sp()), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun OnlineChoiceRow(title: String, options: List<Int>, selected: Int, u: Dp, onPick: (Int) -> Unit) {
    OnlineText(title, u, 4.8f, maxLines = 1)
    Spacer(Modifier.height(u * 2f))
    Row(horizontalArrangement = Arrangement.spacedBy(u * 2.5f)) {
        for (option in options) {
            OnlineChoice("$option", option == selected, u) { onPick(option) }
        }
    }
}

/** The "You are already in room XXXX." dialog, with Go back to it and Leave it. */
@Composable
private fun OnlineConflictDialog(session: OnlineSession, onOpened: () -> Unit) {
    val c = session.conflict ?: return
    BackHandler(true) { session.dismissConflict() }
    val text = if (c.canLeave) {
        "You are already in room ${c.code}."
    } else {
        "You are already in room ${c.code}. Its game has started, so only the host can close it."
    }
    ConnectDialog(
        message = text,
        primaryLabel = "Go back to it",
        onPrimary = { session.openConflictRoom { ok -> if (ok) onOpened() } },
        secondaryLabel = if (c.canLeave) "Leave it" else "Cancel",
        onSecondary = { if (c.canLeave) session.leaveConflictRoom() else session.dismissConflict() }
    )
}

/** Start a Room: how many players (you included) and how many watchers, then Create room. */
@Composable
fun OnlineCreateScreen(session: OnlineSession, playerName: String, onBack: () -> Unit, onRoom: () -> Unit) {
    var players by rememberSaveable { mutableStateOf(4) }
    var watchers by rememberSaveable { mutableStateOf(0) }
    val enabled = !session.busy
    Box(Modifier.fillMaxSize()) {
        OnlineFrame("Start a Room", 66f, onBack) { u ->
            Spacer(Modifier.height(u * 6f))
            OnlineChoiceRow("Players", listOf(2, 3, 4), players, u) { players = it }
            Spacer(Modifier.height(u * 1.5f))
            OnlineText("You are one of the players.", u, 3.4f, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
            Spacer(Modifier.height(u * 6f))
            OnlineChoiceRow("Watchers", listOf(0, 1, 2, 3, 4), watchers, u) { watchers = it }
            val message = session.problem
            if (message != null) {
                Spacer(Modifier.height(u * 3f))
                Box(Modifier.width(u * 84f)) { OnlineText(message, u, 4f, color = OnlineWarn, maxLines = 3) }
            }
            Spacer(Modifier.height(u * 8f))
            val create: () -> Unit = {
                session.createRoom(players, watchers, playerName) { ok -> if (ok) onRoom() }
            }
            GlossButton(
                if (session.busy) "Creating..." else "Create room", Palette.Green, u * 66f, u * 16f,
                dimmed = !enabled, onClick = if (enabled) create else null
            )
        }
        OnlineConflictDialog(session, onRoom)
    }
}

// ---------------------------------------------------------------------------
// Join a Room
// ---------------------------------------------------------------------------

/** The big box where the 4-character room code is typed. Letters are upper-cased and anything outside the alphabet is dropped. */
@Composable
private fun OnlineCodeField(u: Dp, value: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(u * 4f)
    val style = TextStyle(
        color = Color.White,
        fontSize = (u * 11f).sp(),
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
        letterSpacing = (u * 2f).sp()
    )
    Box(
        Modifier
            .width(u * 66f)
            .height(u * 20f)
            .background(Brush.verticalGradient(listOf(Color(0xFF14566A), Color(0xFF0B3340))), shape)
            .border(u * 0.3f, Palette.PillEdge, shape)
            .padding(horizontal = u * 3f),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = { typed ->
                onChange(typed.uppercase().filter { ch -> ROOM_ALPHABET.indexOf(ch) >= 0 }.take(4))
            },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) {
                        BasicText("CODE", style = style.copy(color = Color.White.copy(alpha = 0.3f)))
                    }
                    inner()
                }
            }
        )
    }
}

/** Join a Room: type the code, then Join (play) or Watch (watch only). */
@Composable
fun OnlineJoinScreen(session: OnlineSession, playerName: String, onBack: () -> Unit, onRoom: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val ready = code.length == 4 && code.all { ROOM_ALPHABET.indexOf(it) >= 0 }
    val enabled = ready && !session.busy
    Box(Modifier.fillMaxSize()) {
        OnlineFrame("Join a Room", 66f, onBack, ime = true) { u ->
            Spacer(Modifier.height(u * 8f))
            OnlineText("Room code", u, 4.8f, maxLines = 1)
            Spacer(Modifier.height(u * 2f))
            OnlineCodeField(u, code) { code = it }
            val message = session.problem
            if (message != null) {
                Spacer(Modifier.height(u * 3f))
                Box(Modifier.width(u * 84f)) { OnlineText(message, u, 4f, color = OnlineWarn, maxLines = 3) }
            }
            Spacer(Modifier.height(u * 8f))
            val join: () -> Unit = {
                session.joinRoom(code, playerName, false) { ok -> if (ok) onRoom() }
            }
            val watch: () -> Unit = {
                session.joinRoom(code, playerName, true) { ok -> if (ok) onRoom() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(u * 3f)) {
                GlossButton("Join", Palette.Green, u * 38f, u * 15f, dimmed = !enabled, onClick = if (enabled) join else null)
                GlossButton("Watch", Palette.Blue, u * 38f, u * 15f, dimmed = !enabled, onClick = if (enabled) watch else null)
            }
            Spacer(Modifier.height(u * 2.5f))
            OnlineText("Join to play. Watch to follow the game without playing.", u, 3.4f, color = Color.White.copy(alpha = 0.8f), maxLines = 2)
        }
        val offered = session.offerWatchCode
        if (offered != null) {
            BackHandler(true) { session.dismissOfferWatch() }
            ConnectDialog(
                message = "This room is full. Watch the game instead?",
                primaryLabel = "Watch",
                onPrimary = {
                    session.dismissOfferWatch()
                    session.joinRoom(offered, playerName, true) { ok -> if (ok) onRoom() }
                },
                secondaryLabel = "Cancel",
                onSecondary = { session.dismissOfferWatch() }
            )
        }
        OnlineConflictDialog(session, onRoom)
    }
}

// ---------------------------------------------------------------------------
// The room
// ---------------------------------------------------------------------------

/** One seat in the room list: the seat number, then the name or "Waiting...". */
@Composable
private fun OnlineSeatRow(seat: Int, label: String, filled: Boolean, u: Dp) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .width(u * 80f)
            .height(u * 8.5f)
            .background(Brush.verticalGradient(listOf(Palette.PillLight, Palette.PillDark)), shape)
            .border(u * 0.3f, Palette.PillEdge, shape)
            .padding(horizontal = u * 1.2f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CounterOrb(seat + 1, if (filled) Palette.Green else Palette.Orange, u * 6.5f)
        Spacer(Modifier.width(u * 2.5f))
        BasicText(
            label,
            style = menuText((u * 3.8f).sp(), Color.White.copy(alpha = if (filled) 1f else 0.6f))
                .copy(textAlign = TextAlign.Start),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Everything under the title of the room screen once the room is known. */
@Composable
private fun ColumnScope.OnlineRoomBody(session: OnlineSession, room: OnlineRoom, u: Dp) {
    val context = LocalContext.current
    Spacer(Modifier.height(u * 2f))
    if (session.link == OnlineLink.OFFLINE) {
        ConnectNotice("No internet. Trying again...", u)
        Spacer(Modifier.height(u * 2f))
    }
    OnlineText("Room code", u, 3.8f, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    Spacer(Modifier.height(u * 1f))
    val boxShape = RoundedCornerShape(u * 4f)
    Box(
        Modifier
            .width(u * 70f)
            .height(u * 22f)
            .background(Brush.verticalGradient(listOf(Color(0xFF14566A), Color(0xFF0B3340))), boxShape)
            .border(u * 0.3f, Palette.PillEdge, boxShape),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            room.code,
            style = menuText((u * 13f).sp()).copy(letterSpacing = (u * 2f).sp()),
            maxLines = 1,
            softWrap = false
        )
    }
    Spacer(Modifier.height(u * 3f))
    val share: () -> Unit = {
        val text = "Join my Ludo Mate game! Room code: ${room.code}\n" +
            "Open Ludo Mate > Connect and Play > Online > Join a Room."
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            context.startActivity(Intent.createChooser(send, "Share room code"))
        } catch (e: Exception) {
            // No app can share text on this phone: nothing to do.
        }
    }
    GlossButton("Share code", Palette.Blue, u * 56f, u * 12f, onClick = share)
    Spacer(Modifier.height(u * 4f))

    for (seat in 0 until room.playerCount) {
        val member = session.members.firstOrNull { it.seat == seat && it.role != OnlineRole.WATCHER }
        val label = when {
            member == null -> "Waiting..."
            member.role == OnlineRole.HOST && seat == session.mySeat -> member.name + " (Host, You)"
            member.role == OnlineRole.HOST -> member.name + " (Host)"
            seat == session.mySeat -> member.name + " (You)"
            else -> member.name
        }
        OnlineSeatRow(seat, label, member != null, u)
        if (seat < room.playerCount - 1) Spacer(Modifier.height(u * 1.5f))
    }

    val watchers = session.members.filter { it.role == OnlineRole.WATCHER }
    if (room.maxWatchers > 0) {
        Spacer(Modifier.height(u * 3f))
        OnlineText("Watchers: ${watchers.size} of ${room.maxWatchers}", u, 3.8f, maxLines = 1)
        if (watchers.isNotEmpty()) {
            Box(Modifier.width(u * 80f)) {
                OnlineText(
                    watchers.joinToString(", ") { it.name },
                    u, 3.2f, color = Color.White.copy(alpha = 0.8f), maxLines = 2
                )
            }
        }
    }

    val present = session.members.count { it.role != OnlineRole.WATCHER }
    val missing = room.playerCount - present
    val status = when {
        session.myRole == OnlineRole.WATCHER -> "You are watching."
        room.status == "playing" -> "Everyone is here! The game itself arrives in the next update."
        missing <= 1 -> "Waiting for the last player..."
        else -> "Waiting for $missing more players..."
    }
    Spacer(Modifier.height(u * 4f))
    Box(Modifier.width(u * 84f)) { OnlineText(status, u, 4.4f, maxLines = 3) }
    if (session.busy) {
        Spacer(Modifier.height(u * 2f))
        OnlineText("One moment...", u, 3.4f, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
    }
    Spacer(Modifier.height(u * 2f))
}

/**
 * The room screen: the code, Share code, the seats, the watchers and a status line. It asks the
 * server for the room every 1.5 seconds while it is open. Exit frees the seat (guest) or, after a
 * question, closes the room for everyone (host).
 */
@Composable
fun OnlineRoomScreen(session: OnlineSession, onClosed: () -> Unit) {
    KeepScreenOn()
    var askExit by remember { mutableStateOf(false) }
    // After the screen was rebuilt there may be no room in memory yet; it is looked up once.
    var looked by remember { mutableStateOf(session.room != null) }

    LaunchedEffect(Unit) {
        if (session.room == null) {
            session.restore { looked = true }
        } else {
            looked = true
        }
    }
    DisposableEffect(Unit) {
        session.startPolling()
        onDispose { session.stopPolling() }
    }
    val room = session.room
    // The room is gone (closed by the host, or this person is no longer in it): back to the Online menu.
    LaunchedEffect(looked, room == null) {
        if (looked && room == null) onClosed()
    }

    val requestExit: () -> Unit = {
        if (session.myRole == OnlineRole.HOST) {
            askExit = true
        } else {
            session.leave(onClosed)
        }
    }
    BackHandler(true) {
        if (askExit) {
            askExit = false
        } else {
            requestExit()
        }
    }

    Box(Modifier.fillMaxSize()) {
        OnlineFrame("Room", 40f, requestExit) { u ->
            if (room == null) {
                Spacer(Modifier.height(u * 10f))
                OnlineText("Opening the room...", u, 4.6f, maxLines = 1)
            } else {
                OnlineRoomBody(session, room, u)
            }
        }
        if (askExit) {
            ConnectDialog(
                message = "Close this room for everyone?",
                primaryLabel = "Close room",
                onPrimary = {
                    askExit = false
                    session.endRoom(onClosed)
                },
                secondaryLabel = "Cancel",
                onSecondary = { askExit = false },
                primarySwatch = Palette.Red
            )
        }
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
