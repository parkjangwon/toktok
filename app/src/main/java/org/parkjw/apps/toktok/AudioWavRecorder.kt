package org.parkjw.apps.toktok

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16kHz mono 16-bit WAV recorder via AudioRecord.
 * (MediaRecorder can't write WAV, so PCM is captured and a WAV header is written.)
 */
class AudioWavRecorder(private val outFile: File) {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    private var recorder: AudioRecord? = null
    private var writerThread: Thread? = null
    @Volatile private var running = false
    private var audioBytes = 0L

    /** Latest peak amplitude 0..1, updated by the writer thread. */
    @Volatile var amplitude: Float = 0f
        private set

    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        return try {
            val raf = RandomAccessFile(outFile, "rw")
            raf.setLength(0)
            raf.write(ByteArray(44)) // placeholder header, fixed up on stop
            audioBytes = 0
            running = true
            rec.startRecording()
            recorder = rec
            writerThread = Thread({
                val buf = ByteArray(minBuf)
                val shorts = ShortArray(minBuf / 2)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        synchronized(raf) { raf.write(buf, 0, n) }
                        audioBytes += n
                        // Peak amplitude for waveform UI
                        var peak = 0
                        val count = minOf(n / 2, shorts.size)
                        for (i in 0 until count) {
                            val s = ((buf[i * 2 + 1].toInt() shl 8) or
                                (buf[i * 2].toInt() and 0xFF))
                            val a = kotlin.math.abs(s)
                            if (a > peak) peak = a
                        }
                        amplitude = (peak / 32768f).coerceIn(0f, 1f)
                    } else if (n < 0) {
                        break
                    }
                }
                synchronized(raf) {
                    raf.seek(0)
                    raf.write(wavHeader(audioBytes))
                    raf.close()
                }
            }, "toktok-wav").also { it.start() }
            true
        } catch (e: Exception) {
            running = false
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
            false
        }
    }

    fun stop() {
        running = false
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
        try {
            writerThread?.join(3000)
        } catch (_: InterruptedException) {
        }
        writerThread = null
    }

    private fun wavHeader(dataLen: Long): ByteArray {
        val byteRate = SAMPLE_RATE * 2L // mono 16-bit
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataLen).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(SAMPLE_RATE)
            putInt(byteRate.toInt())
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray())
            putInt(dataLen.toInt())
        }.array()
    }
}
