package com.westly.ludo.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

private const val RATE = 22050
private val TWO_PI = (2.0 * PI).toFloat()

/**
 * Short game sounds generated in code (no audio files needed).
 * If a sound cannot be created on some device, it is simply skipped.
 */
class Sounds {
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

    fun roll() = play(rattle)
    fun land() = play(thud)
    fun tick() = play(tick)
    fun out() = play(pop)
    fun finish() = play(chime)
    fun win() = play(fanfare)

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
