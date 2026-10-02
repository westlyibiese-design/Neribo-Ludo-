package com.westly.ludo.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.westly.ludo.R
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

private const val RATE = 22050
private val TWO_PI = (2.0 * PI).toFloat()

/**
 * Short game sounds generated in code (no audio files needed).
 * If a sound cannot be created on some device, it is simply skipped.
 * Only three sounds are real recordings (res/raw): dice roll, dice land and round win.
 */
class Sounds {
    companion object {
        /** Set once by MainActivity so the recorded sounds can be loaded. */
        @Volatile var appContext: Context? = null

        /** Sound & Vibration settings (set from GameSettings). When off, nothing plays / buzzes. */
        @Volatile var soundOn: Boolean = true
        @Volatile var vibrationOn: Boolean = true

        /** Set during spectator fast-forward: no sound and no buzz at all. */
        @Volatile var quiet: Boolean = false
    }

    private fun muted(): Boolean = !soundOn || quiet

    /** A short buzz (about 30-60 ms). Safe on every Android version; skipped if there is no vibrator. */
    fun buzz(ms: Long = 45L) {
        if (!vibrationOn || quiet) return
        val ctx = appContext ?: return
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib == null || !vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(ms)
            }
        } catch (e: Exception) {
            // ignore: vibration is optional
        }
    }

    private val ready = HashSet<Int>()
    private var rollId = 0
    private var landId = 0
    private var winId = 0
    private val pool: SoundPool? = try {
        val ctx = appContext
        if (ctx == null) null else {
            SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build().also { p ->
                    p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready.add(id) } }
                    rollId = p.load(ctx, R.raw.dice_roll, 1)
                    landId = p.load(ctx, R.raw.dice_land, 1)
                    winId = p.load(ctx, R.raw.win_round, 1)
                }
        }
    } catch (e: Exception) {
        null
    }

    /** Plays a recorded sound. Returns false if it is not loaded, so the caller can use the built-in one. */
    private fun sample(id: Int): Boolean {
        val p = pool ?: return false
        val ok = synchronized(ready) { id in ready }
        if (!ok) return false
        return try {
            p.play(id, 1f, 1f, 1, 0, 1f) != 0
        } catch (e: Exception) {
            false
        }
    }

    private val rattle = build(70) { t ->
        noise() * exp(-t * 60f) * 0.5f + sin(TWO_PI * 900f * t) * exp(-t * 70f) * 0.4f
    }
    private val thud = build(130) { t ->
        sin(TWO_PI * 180f * t) * exp(-t * 30f) * 0.9f + noise() * exp(-t * 80f) * 0.3f
    }
    private val tick = build(45) { t ->
        sin(TWO_PI * 1400f * t) * exp(-t * 90f) * 0.7f + noise() * exp(-t * 200f) * 0.25f
    }
    private val pop = build(150) { t ->
        sin(TWO_PI * (350f * t + 2500f * t * t)) * exp(-t * 16f)
    }
    private val chime = notes(listOf(784f, 1175f), 110, 450)
    private val fanfare = notes(listOf(523f, 659f, 784f, 1047f), 150, 600)

    /** Dice rolling: recorded sound, played once at the start of a roll. */
    fun roll() {
        buzz()
        if (muted()) return
        if (!sample(rollId)) play(rattle)
    }

    /** Dice landing: recorded sound. */
    fun dieLand() {
        if (muted()) return
        if (!sample(landId)) play(thud)
    }

    /** Built-in thud (used when a seed is captured). */
    fun land() {
        buzz()
        if (muted()) return
        play(thud)
    }
    fun tick() {
        if (muted()) return
        play(tick)
    }
    fun out() {
        if (muted()) return
        play(pop)
    }
    fun finish() {
        if (muted()) return
        play(chime)
    }
    /** Round win: recorded sound. */
    fun win() {
        buzz(60L)
        if (muted()) return
        if (!sample(winId)) play(fanfare)
    }

    private fun noise(): Float = Random.nextFloat() * 2f - 1f

    private fun notes(freqs: List<Float>, noteMs: Int, tailMs: Int): AudioTrack? {
        val total = noteMs * (freqs.size - 1) + tailMs
        return build(total) { t ->
            var v = 0f
            for ((k, f) in freqs.withIndex()) {
                val s = t - k * noteMs / 1000f
                if (s >= 0f) v += sin(TWO_PI * f * s) * exp(-s * 7f) * 0.5f
            }
            v
        }
    }

    private fun build(ms: Int, gen: (Float) -> Float): AudioTrack? {
        return try {
            val n = RATE * ms / 1000
            val data = ShortArray(n) { i ->
                val v = gen(i / RATE.toFloat()).coerceIn(-1f, 1f)
                (v * 32767f * 0.6f).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, n)
            track
        } catch (e: Exception) {
            null
        }
    }

    private fun play(track: AudioTrack?) {
        if (track == null) return
        try {
            track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: Exception) {
            // ignore: sound is optional
        }
    }
}
