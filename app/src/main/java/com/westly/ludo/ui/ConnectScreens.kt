package com.westly.ludo.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.westly.ludo.R
import com.westly.ludo.connect.ConnectSession
import com.westly.ludo.connect.ConnectState
import com.westly.ludo.connect.HostSaveInfo
import com.westly.ludo.connect.JoinTicket
import com.westly.ludo.connect.RejoinInfo
import com.westly.ludo.connect.renderQr
import com.westly.ludo.game.PlayerNames

// ---------------------------------------------------------------------------
// Connect and Play (offline): menu screens, prerequisite checks, host lobby, guest flow.
// The screens only show what ConnectSession holds and call its functions; no networking in here.
// ---------------------------------------------------------------------------

/** Same look as the other menu screens: a title pill with the back button, then the content. */
@Composable
private fun ConnectFrame(
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
private fun InfoText(text: String, u: Dp, size: Float = 4.4f, widthU: Float = 84f) {
    BasicText(
        text,
        modifier = Modifier.width(u * widthU),
        style = menuText((u * size).sp()),
        maxLines = 6
    )
}

/** Keeps the screen awake while a lobby or a Connect and Play game is shown, and lets go again afterwards. */
@Composable
fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ---------------------------------------------------------------------------
// Menu screens
// ---------------------------------------------------------------------------

/** Connect and Play: Offline works, Online is reserved for later. */
@Composable
fun ConnectMenuScreen(onBack: () -> Unit, onOffline: () -> Unit) {
    ConnectFrame("Connect and Play", 66f, onBack) { u ->
        Spacer(Modifier.height(u * 8f))
        GlossButton(
            "Offline", Palette.Blue, u * 78f, u * 18f,
            subtitle = "Nearby phones, no internet", onClick = onOffline
        )
        Spacer(Modifier.height(u * 4f))
        GlossButton("Online", Palette.Orange, u * 78f, u * 18f, subtitle = "Coming soon", dimmed = true)
    }
}

/** Offline: host a game or join one. */
@Composable
fun ConnectOfflineScreen(
    onBack: () -> Unit,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    notice: String? = null,
    /** A game this phone left (or lost) and can return to: shows the Rejoin button. */
    rejoin: RejoinInfo? = null,
    onRejoin: () -> Unit = {},
    /** A hosted game that was paused (the app was closed): shows the Resume button. */
    resume: HostSaveInfo? = null,
    onResume: () -> Unit = {}
) {
    ConnectFrame("Offline", 56f, onBack) { u ->
        Spacer(Modifier.height(u * 6f))
        if (resume != null) {
            GlossButton(
                "Resume hosted game", Palette.Orange, u * 78f, u * 16f,
                subtitle = "Room ${resume.room}, ${resume.playerCount} players", onClick = onResume
            )
            Spacer(Modifier.height(u * 3f))
        }
        if (rejoin != null) {
            GlossButton(
                "Rejoin ${rejoin.hostName}'s game", Palette.Orange, u * 78f, u * 16f,
                subtitle = if (rejoin.watcher) "Back to watching" else "Back to your seat", onClick = onRejoin
            )
            Spacer(Modifier.height(u * 3f))
        }
        GlossButton("Host a Game", Palette.Green, u * 78f, u * 18f, subtitle = "Show a QR code", onClick = onHost)
        Spacer(Modifier.height(u * 4f))
        GlossButton("Join a Game", Palette.Blue, u * 78f, u * 18f, subtitle = "Scan a QR code", onClick = onJoin)
        // For example "The host ended the game." after a game was closed by the host.
        if (notice != null) {
            Spacer(Modifier.height(u * 6f))
            InfoText(notice, u)
        }
    }
}

/** A small banner over the board, for example "Waiting for Joy to reconnect...". Takes no taps. */
@Composable
fun ConnectNotice(text: String, u: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .background(Color(0xE60B2E2F), shape)
            .border(u * 0.3f, Palette.PillEdge, shape)
            .padding(horizontal = u * 4f, vertical = u * 1.6f)
    ) {
        BasicText(text, style = menuText((u * 3.4f).sp()), maxLines = 1, softWrap = false)
    }
}

/**
 * The line shown above the board in a Connect and Play game: a dot for each colour this phone
 * plays, then "You are Red" / "You are Green + Blue".
 */
@Composable
fun YouAreLabel(colors: List<Color>, text: String, u: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (c in colors) {
            Box(
                Modifier
                    .size(u * 3.2f)
                    .background(c, RoundedCornerShape(50))
                    .border(u * 0.3f, Color.White, RoundedCornerShape(50))
            )
            Spacer(Modifier.width(u * 1.2f))
        }
        BasicText(text, style = menuText((u * 3.6f).sp()), maxLines = 1, softWrap = false)
    }
}

/** Host step 1: how many players. */
@Composable
fun ConnectCountScreen(onBack: () -> Unit, onPick: (Int) -> Unit) {
    ConnectFrame("Host", 56f, onBack) { u ->
        Spacer(Modifier.height(u * 8f))
        GlossButton(
            "2 Players", Palette.Blue, u * 78f, u * 18f,
            subtitle = "Colors picked at random", onClick = { onPick(2) }
        )
        Spacer(Modifier.height(u * 4f))
        GlossButton(
            "3 Players", Palette.Orange, u * 78f, u * 18f,
            subtitle = "Knock-out rounds", onClick = { onPick(3) }
        )
        Spacer(Modifier.height(u * 4f))
        GlossButton(
            "4 Players", Palette.Red, u * 78f, u * 18f,
            subtitle = "Knock-out rounds", onClick = { onPick(4) }
        )
    }
}

// ---------------------------------------------------------------------------
// Prerequisites: Play Services, permissions, Bluetooth / Wi-Fi / Location switched on
// ---------------------------------------------------------------------------

private enum class Problem { PLAY_SERVICES, NEARBY_PERMISSION, CAMERA_PERMISSION, RADIOS, LOCATION }

/** The permissions Nearby needs on this Android version (plus the camera for joining). */
private fun nearbyPermissions(): List<String> = when {
    Build.VERSION.SDK_INT >= 33 -> listOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )
    Build.VERSION.SDK_INT >= 31 -> listOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    Build.VERSION.SDK_INT >= 29 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun missingNearby(context: Context): List<String> =
    nearbyPermissions().filter { !isGranted(context, it) }

private fun radiosOn(context: Context): Boolean {
    val bluetoothOk = try {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        adapter == null || adapter.isEnabled
    } catch (e: Exception) {
        true
    }
    val wifiOk = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi == null || wifi.isWifiEnabled
    } catch (e: Exception) {
        true
    }
    return bluetoothOk && wifiOk
}

/** Some Android versions only let Nearby find phones while Location is switched on. */
private fun locationOk(context: Context): Boolean {
    if (Build.VERSION.SDK_INT > 32) return true
    return try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        if (Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (e: Exception) {
        true
    }
}

/** The first failing step, in the required order, or null when everything is ready. */
private fun findProblem(context: Context, needCamera: Boolean): Problem? = when {
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS ->
        Problem.PLAY_SERVICES
    missingNearby(context).isNotEmpty() -> Problem.NEARBY_PERMISSION
    needCamera && !isGranted(context, Manifest.permission.CAMERA) -> Problem.CAMERA_PERMISSION
    !radiosOn(context) -> Problem.RADIOS
    !locationOk(context) -> Problem.LOCATION
    else -> null
}

private fun problemText(problem: Problem): String = when (problem) {
    Problem.PLAY_SERVICES -> "This feature needs Google Play Services on your phone."
    Problem.NEARBY_PERMISSION -> "Ludo Mate needs Bluetooth and Wi-Fi access to find nearby phones."
    Problem.CAMERA_PERMISSION -> "Ludo Mate needs the camera to scan the QR code."
    Problem.RADIOS -> "Please turn on Bluetooth and Wi-Fi. You do not need internet."
    Problem.LOCATION -> "Please turn on Location. Android needs it to find nearby phones. You do not need internet."
}

private fun openSettingsFor(context: Context, problem: Problem) {
    try {
        val intent = when (problem) {
            Problem.RADIOS -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            Problem.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // No settings page to open: the Try again button still works.
    }
}

/**
 * Runs the prerequisite checks and shows [content] only when everything is ready. Otherwise it shows
 * a friendly message for the first failing step, with Try again (and Open Settings where it helps).
 */
@Composable
private fun PrerequisiteGate(
    needCamera: Boolean,
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<Problem?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        problem = findProblem(context, needCamera)
        checking = false
    }

    fun recheck(askForPermissions: Boolean) {
        val found = findProblem(context, needCamera)
        val missing = ArrayList<String>()
        if (found == Problem.NEARBY_PERMISSION || found == Problem.CAMERA_PERMISSION) {
            missing.addAll(missingNearby(context))
            if (needCamera && !isGranted(context, Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA)
        }
        if (askForPermissions && missing.isNotEmpty()) {
            checking = true
            launcher.launch(missing.toTypedArray())
        } else {
            problem = found
            checking = false
        }
    }

    LaunchedEffect(Unit) { recheck(true) }

    val current = problem
    if (!checking && current == null) {
        content()
    } else {
        ConnectFrame(title, 56f, onBack) { u ->
            Spacer(Modifier.height(u * 10f))
            if (current == null) {
                InfoText("One moment...", u)
            } else {
                InfoText(problemText(current), u)
                Spacer(Modifier.height(u * 8f))
                GlossButton("Try again", Palette.Green, u * 60f, u * 14f, onClick = { recheck(true) })
                if (current != Problem.PLAY_SERVICES) {
                    Spacer(Modifier.height(u * 4f))
                    GlossButton(
                        "Open Settings", Palette.Blue, u * 60f, u * 14f,
                        onClick = { openSettingsFor(context, current) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces of the lobbies: seat list and small dialogs
// ---------------------------------------------------------------------------

@Composable
private fun SeatRow(seat: Int, label: String, filled: Boolean, u: Dp) {
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
            softWrap = false
        )
    }
}

/** One row per seat: the person's name once joined, otherwise "Waiting...". */
@Composable
private fun SeatList(session: ConnectSession, u: Dp) {
    for (seat in 0 until session.playerCount) {
        val entry = session.roster.firstOrNull { it.seat == seat }
        val label = when {
            entry == null -> "Waiting..."
            entry.isHost -> entry.name + " (Host)"
            seat == session.mySeat -> entry.name + " (You)"
            else -> entry.name
        }
        SeatRow(seat, label, entry != null, u)
        if (seat < session.playerCount - 1) Spacer(Modifier.height(u * 1.5f))
    }
}

/** A message box over the screen with one or two buttons. Taps behind it are blocked. */
@Composable
internal fun ConnectDialog(
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    primarySwatch: Swatch = Palette.Green
) {
    val source = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(interactionSource = source, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        val u = minOf(maxWidth / 100f, maxHeight / 170f)
        val shape = RoundedCornerShape(u * 5f)
        Column(
            Modifier
                .width(u * 86f)
                .background(Brush.verticalGradient(listOf(Palette.PillLight, Palette.PillDark)), shape)
                .border(u * 0.5f, Palette.PillEdge, shape)
                .padding(u * 5f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(message, style = menuText((u * 4.6f).sp()), maxLines = 6)
            Spacer(Modifier.height(u * 5f))
            GlossButton(primaryLabel, primarySwatch, u * 52f, u * 12f, onClick = onPrimary)
            if (secondaryLabel != null) {
                Spacer(Modifier.height(u * 3f))
                GlossButton(secondaryLabel, Palette.Orange, u * 52f, u * 12f, onClick = onSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Host
// ---------------------------------------------------------------------------

/** Host: checks the prerequisites, opens the lobby and shows the QR code with the live seat list. */
@Composable
fun ConnectHostScreen(session: ConnectSession, names: PlayerNames, count: Int, onClose: () -> Unit) {
    PrerequisiteGate(needCamera = false, title = "Host", onBack = onClose) {
        HostLobby(session, names, count, onClose)
    }
}

@Composable
private fun HostLobby(session: ConnectSession, names: PlayerNames, count: Int, onClose: () -> Unit) {
    KeepScreenOn()
    var askClose by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (session.state == ConnectState.IDLE && session.error == null) {
            session.startHosting(count, names[0])
        }
    }

    // Leaving an open lobby disconnects everyone, so ask first.
    val requestClose: () -> Unit = {
        if (session.state == ConnectState.HOST_LOBBY) askClose = true else onClose()
    }
    BackHandler { requestClose() }

    Box(Modifier.fillMaxSize()) {
        ConnectFrame("Lobby", 56f, requestClose) { u ->
            val problem = session.error
            if (problem != null) {
                Spacer(Modifier.height(u * 10f))
                InfoText(problem, u)
                Spacer(Modifier.height(u * 8f))
                GlossButton(
                    "Try again", Palette.Green, u * 60f, u * 14f,
                    onClick = { session.startHosting(count, names[0]) }
                )
            } else if (session.state != ConnectState.HOST_LOBBY) {
                Spacer(Modifier.height(u * 10f))
                InfoText("Opening the lobby...", u)
            } else {
                Spacer(Modifier.height(u * 2.5f))
                val qr = remember(session.ticketText) {
                    runCatching { renderQr(session.ticketText, 640).asImageBitmap() }.getOrNull()
                }
                Box(
                    Modifier.background(Color.White, RoundedCornerShape(u * 2f)).padding(u * 1.5f),
                    contentAlignment = Alignment.Center
                ) {
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = "QR code to join this game",
                            modifier = Modifier.size(u * 50f),
                            filterQuality = FilterQuality.None
                        )
                    } else {
                        Spacer(Modifier.size(u * 50f))
                    }
                }
                Spacer(Modifier.height(u * 2f))
                InfoText("Other players: tap Join a Game and scan this code.", u, size = 3.4f)
                Spacer(Modifier.height(u * 2f))
                BasicText(
                    "${session.roster.size} of ${session.playerCount} joined",
                    style = menuText((u * 3.8f).sp()),
                    maxLines = 1
                )
                Spacer(Modifier.height(u * 1.5f))
                SeatList(session, u)
                Spacer(Modifier.height(u * 2.5f))
                GlossButton(
                    if (session.allowWatchers) "Allow watchers: On" else "Allow watchers: Off",
                    if (session.allowWatchers) Palette.Green else Palette.Orange,
                    u * 60f, u * 9f,
                    onClick = { session.switchWatchers(!session.allowWatchers) }
                )
                Spacer(Modifier.height(u * 2.5f))
                val enabled = session.isFull
                // Start opens the real game on every phone; the app then moves on to the game screen by itself.
                val startAction: () -> Unit = { session.requestStart() }
                GlossButton(
                    "Start", Palette.Green, u * 50f, u * 12f,
                    dimmed = !enabled, onClick = if (enabled) startAction else null
                )
                Spacer(Modifier.height(u * 2f))
            }
        }
        if (askClose) {
            ConnectDialog(
                "Close the lobby? Everyone will be disconnected.",
                "Close lobby", onPrimary = {
                    askClose = false
                    onClose()
                },
                secondaryLabel = "Stay", onSecondary = { askClose = false },
                primarySwatch = Palette.Red
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Guest
// ---------------------------------------------------------------------------

/** Guest: checks the prerequisites (including the camera), scans the QR code, connects and shows the lobby. */
@Composable
fun ConnectJoinScreen(session: ConnectSession, names: PlayerNames, onClose: () -> Unit, rejoin: Boolean = false) {
    // Returning to a remembered game needs no camera.
    PrerequisiteGate(needCamera = !rejoin, title = if (rejoin) "Rejoin" else "Join", onBack = onClose) {
        JoinFlow(session, names, onClose, rejoin)
    }
}

@Composable
private fun JoinFlow(session: ConnectSession, names: PlayerNames, onClose: () -> Unit, rejoin: Boolean) {
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var autoOpened by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text != null) {
            val ticket = JoinTicket.parse(text)
            if (ticket == null) {
                scanMessage = "That is not a Ludo Mate code."
            } else {
                scanMessage = null
                session.startJoin(ticket, names[0])
            }
        }
    }

    fun openScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the host's QR code")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    // Open the camera by itself the first time, so joining is one tap less. A rejoin goes straight back instead.
    LaunchedEffect(Unit) {
        if (!autoOpened && session.state == ConnectState.IDLE && session.error == null && session.ended == null) {
            autoOpened = true
            if (rejoin) session.startRejoin(names[0]) else openScanner()
        }
    }

    val endedText = session.ended
    val problem = session.error
    when {
        endedText != null -> ConnectFrame("Join", 56f, onClose) { u ->
            Spacer(Modifier.height(u * 10f))
            InfoText(endedText, u)
            Spacer(Modifier.height(u * 8f))
            GlossButton(
                "OK", Palette.Green, u * 50f, u * 14f,
                onClick = {
                    session.clearNotices()
                    onClose()
                }
            )
        }
        problem != null -> ConnectFrame("Join", 56f, onClose) { u ->
            Spacer(Modifier.height(u * 10f))
            InfoText(problem, u)
            Spacer(Modifier.height(u * 8f))
            // A rejoin with nothing left to rejoin has no use for Try again.
            if (rejoin && session.rejoinInfo == null) {
                GlossButton(
                    "OK", Palette.Green, u * 50f, u * 14f,
                    onClick = {
                        session.clearNotices()
                        onClose()
                    }
                )
            } else {
                GlossButton(
                    "Try again", Palette.Green, u * 60f, u * 14f,
                    onClick = {
                        session.clearNotices()
                        if (rejoin) session.startRejoin(names[0]) else openScanner()
                    }
                )
            }
        }
        session.state == ConnectState.SEARCHING -> ConnectFrame("Join", 56f, onClose) { u ->
            Spacer(Modifier.height(u * 10f))
            InfoText("Looking for the host...", u)
        }
        session.state == ConnectState.CONNECTING -> ConnectFrame("Join", 56f, onClose) { u ->
            Spacer(Modifier.height(u * 10f))
            InfoText("Connecting to ${session.hostName}...", u)
        }
        session.state == ConnectState.GUEST_LOBBY -> {
            KeepScreenOn()
            ConnectFrame("Lobby", 56f, onClose) { u ->
                Spacer(Modifier.height(u * 5f))
                InfoText("Host: ${session.hostName}", u)
                Spacer(Modifier.height(u * 3f))
                BasicText(
                    "${session.roster.size} of ${session.playerCount} joined",
                    style = menuText((u * 3.8f).sp()),
                    maxLines = 1
                )
                Spacer(Modifier.height(u * 1.5f))
                SeatList(session, u)
                Spacer(Modifier.height(u * 5f))
                InfoText("Waiting for the host to start...", u)
            }
        }
        else -> ConnectFrame("Join", 56f, onClose) { u ->
            Spacer(Modifier.height(u * 10f))
            InfoText(scanMessage ?: "Scan the host's QR code to join.", u)
            Spacer(Modifier.height(u * 8f))
            GlossButton("Scan QR code", Palette.Green, u * 66f, u * 14f, onClick = { openScanner() })
        }
    }
}

// ---------------------------------------------------------------------------
// Phase 4: resume, banners and dialogs shown over the game
// ---------------------------------------------------------------------------

/** Host: checks the prerequisites, then resumes the saved game (same room, same QR code). */
@Composable
fun ConnectResumeScreen(session: ConnectSession, names: PlayerNames, onClose: () -> Unit) {
    PrerequisiteGate(needCamera = false, title = "Resume", onBack = onClose) {
        var tried by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!tried && session.state == ConnectState.IDLE && session.error == null) {
                tried = true
                session.resumeHosting(names[0])
            }
        }
        ConnectFrame("Resume", 56f, onClose) { u ->
            Spacer(Modifier.height(u * 10f))
            val problem = session.error
            if (problem != null) {
                InfoText(problem, u)
                Spacer(Modifier.height(u * 8f))
                GlossButton(
                    "OK", Palette.Green, u * 50f, u * 14f,
                    onClick = {
                        session.clearNotices()
                        onClose()
                    }
                )
            } else {
                InfoText("Opening the game again...", u)
            }
        }
    }
}

/**
 * The line over the board while a phone is missing: "Waiting for Joy to reconnect...". On the host it also
 * carries a Remove player button (only when [canRemove], i.e. the connection of that person is really lost).
 */
@Composable
fun ConnectWaitBanner(text: String, canRemove: Boolean, onRemove: () -> Unit, u: Dp, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ConnectNotice(text, u)
        if (canRemove) {
            Spacer(Modifier.height(u * 1.2f))
            GlossButton("Remove player", Palette.Red, u * 40f, u * 8f, onClick = onRemove)
        }
    }
}

/** Host: the QR code again during the game, so latecomers and returning people can scan it. */
@Composable
fun ConnectQrDialog(ticketText: String, watching: Int, allowWatchers: Boolean, onClose: () -> Unit) {
    val qr = remember(ticketText) {
        runCatching { renderQr(ticketText, 640).asImageBitmap() }.getOrNull()
    }
    val source = remember { MutableInteractionSource() }
    BackHandler(true) { onClose() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(interactionSource = source, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        val u = minOf(maxWidth / 100f, maxHeight / 170f)
        val shape = RoundedCornerShape(u * 5f)
        Column(
            Modifier
                .width(u * 86f)
                .background(Brush.verticalGradient(listOf(Palette.PillLight, Palette.PillDark)), shape)
                .border(u * 0.5f, Palette.PillEdge, shape)
                .padding(u * 5f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.background(Color.White, RoundedCornerShape(u * 2f)).padding(u * 1.5f),
                contentAlignment = Alignment.Center
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "QR code to join this game",
                        modifier = Modifier.size(u * 52f),
                        filterQuality = FilterQuality.None
                    )
                } else {
                    Spacer(Modifier.size(u * 52f))
                }
            }
            Spacer(Modifier.height(u * 2.5f))
            BasicText(
                if (allowWatchers) "Watchers allowed. Watching: $watching" else "Watchers are off. Watching: $watching",
                style = menuText((u * 3.6f).sp()),
                maxLines = 2
            )
            Spacer(Modifier.height(u * 3f))
            GlossButton("Close", Palette.Green, u * 52f, u * 12f, onClick = onClose)
        }
    }
}
