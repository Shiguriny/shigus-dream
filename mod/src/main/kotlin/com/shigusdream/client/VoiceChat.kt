package com.shigusdream.client

import com.shigusdream.ShigusDream
import com.shigusdream.ShigusDreamClient
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Голосовой канал «рация» через backend: push-to-talk (V), захват микрофона 16 кГц mono,
 * бинарные WS-кадры, воспроизведение входящего. Качество рации, задержка ~100 мс.
 */
object VoiceChat {

    private const val RATE = 16000f

    @Volatile
    private var pttActive = false

    @Volatile
    private var captureThread: Thread? = null

    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
    private var playLine: SourceDataLine? = null
    private var playUpsample = 1
    private var lastPlaybackAt = 0L
    private var playbackThread: Thread? = null

    private var send: (ByteArray) -> Unit = {}

    fun setSender(send: (ByteArray) -> Unit) {
        this.send = send
    }

    /** Вызывается из END-тика: удержание клавиши PTT включает захват. */
    fun setPTT(active: Boolean) {
        if (active == pttActive) return
        pttActive = active
        if (active) startCapture() else stopCapture()
    }

    private fun startCapture() {
        if (captureThread?.isAlive == true) return
        captureThread = Thread({
            val line = openCapture() ?: run {
                ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Микрофон недоступен")
                pttActive = false
                return@Thread
            }
            ShigusDream.LOGGER.info("voice: захват {} Гц", line.format.sampleRate)
            val chunk = ByteArray(2048)
            try {
                while (pttActive) {
                    val read = line.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        val pcm = if (line.format.sampleRate == RATE) chunk.copyOf(read)
                        else decimate(chunk, read, (line.format.sampleRate / RATE).toInt().coerceAtLeast(2))
                        send(pcm)
                    }
                }
            } catch (e: Exception) {
                ShigusDream.LOGGER.warn("voice capture error", e)
            } finally {
                line.stop()
                line.close()
            }
        }, "shigusdream-voice-capture").apply { isDaemon = true; start() }
    }

    private fun stopCapture() {
        pttActive = false
        // Линия закроется в потоке захвата после выхода из цикла.
    }

    private fun openCapture(): TargetDataLine? {
        for (rate in floatArrayOf(16000f, 48000f, 44100f)) {
            val fmt = AudioFormat(rate, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, fmt)
            if (AudioSystem.isLineSupported(info)) {
                return try {
                    val line = AudioSystem.getTargetDataLine(fmt)
                    line.open(fmt)
                    line.start()
                    line
                } catch (e: Exception) {
                    ShigusDream.LOGGER.warn("mic open failed @{}", rate, e)
                    null
                }
            }
        }
        return null
    }

    /** Понижение частоты: усредняем группы сэмплов (16-бит LE mono, знаковые). */
    private fun decimate(input: ByteArray, length: Int, factor: Int): ByteArray {
        val outSamples = length / (2 * factor)
        val out = ByteArray(outSamples * 2)
        for (i in 0 until outSamples) {
            var sum = 0L
            var count = 0
            for (j in 0 until factor) {
                val idx = (i * factor + j) * 2
                if (idx + 1 < length) {
                    val v = ((input[idx + 1].toInt() shl 8) or (input[idx].toInt() and 0xFF)).toShort().toInt()
                    sum += v
                    count++
                }
            }
            val avg = if (count > 0) (sum / count).toInt() else 0
            out[i * 2] = (avg and 0xFF).toByte()
            out[i * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Входящий голос от другого игрока. */
    fun enqueuePlayback(pcm: ByteArray) {
        playbackQueue += pcm
        lastPlaybackAt = System.currentTimeMillis()
        if (playbackThread?.isAlive != true) startPlayback()
    }

    private fun startPlayback() {
        playbackThread = Thread({
            val out = openPlayback()
            if (out == null) {
                playbackQueue.clear()
                return@Thread
            }
            ShigusDream.LOGGER.info("voice: воспроизведение {} Гц (x{})", out.format.sampleRate, playUpsample)
            while (true) {
                val chunk = playbackQueue.poll()
                if (chunk == null) {
                    if (System.currentTimeMillis() - lastPlaybackAt > 2000) break
                    Thread.sleep(20)
                    continue
                }
                if (playUpsample > 1) {
                    val samples = chunk.size / 2
                    val up = ByteArray(samples * playUpsample * 2)
                    for (s in 0 until samples) {
                        for (f in 0 until playUpsample) {
                            val dst = (s * playUpsample + f) * 2
                            up[dst] = chunk[s * 2]
                            up[dst + 1] = chunk[s * 2 + 1]
                        }
                    }
                    out.write(up, 0, up.size)
                } else {
                    out.write(chunk, 0, chunk.size)
                }
            }
            out.drain()
            out.stop()
            out.close()
            playbackThread = null
        }, "shigusdream-voice-play").apply { isDaemon = true; start() }
    }

    private fun openPlayback(): SourceDataLine? {
        for (rate in floatArrayOf(16000f, 48000f, 44100f)) {
            val fmt = AudioFormat(rate, 16, 1, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, fmt)
            if (AudioSystem.isLineSupported(info)) {
                return try {
                    val line = AudioSystem.getSourceDataLine(fmt)
                    line.open(fmt)
                    line.start()
                    playUpsample = if (rate == RATE) 1 else Math.round(rate / RATE).coerceAtLeast(1)
                    line
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }

    fun stopAll() {
        pttActive = false
        playbackQueue.clear()
    }
}
