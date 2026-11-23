package app.pocketpad

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays the laptop's audio (16-bit stereo PCM chunks over UDP). A short queue absorbs Wi-Fi jitter;
 * if it grows, the oldest chunks are dropped so the sound never drifts far behind the game.
 */
class AudioPlayer {
    private class Chunk(val rate: Int, val data: ByteArray)

    private val q = ArrayBlockingQueue<Chunk>(256)

    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start() {
        running = true
        worker = Thread({ loop() }, "pocketpad-audio").also {
            it.isDaemon = true
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
    }

    fun feed(rate: Int, data: ByteArray) {
        if (!running || rate < 8000 || rate > 192000) return
        q.offer(Chunk(rate, data))
        if (q.size > 40) while (q.size > 12) q.poll() // about 250 ms behind: skip ahead
    }

    private fun build(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun loop() {
        var track: AudioTrack? = null
        var cur = 0
        try {
            while (running) {
                val c = q.poll(300, TimeUnit.MILLISECONDS) ?: continue
                if (track == null || c.rate != cur) {
                    track?.release()
                    track = build(c.rate)
                    cur = c.rate
                    track.play()
                }
                track.write(c.data, 0, c.data.size)
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
        }
    }
}
