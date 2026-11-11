package cn.moss.live.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 麦克风采集：16kHz 单声道 PCM S16LE，128ms 一块，按实时速率经回调上行。
 *
 * 协议要点：用户停止说话后仍持续采集上行（自然含静音帧，供服务端检测停顿）；
 * 播放期间也保持采集（支持全双工打断）。音源用 VOICE_COMMUNICATION 启用
 * 系统级回声消除，避免扬声器播放的回复被当作用户语音。
 */
class MicCapture(private val onChunk: (ByteArray) -> Unit) {

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 2048 // 128ms
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    private val _level = MutableStateFlow(0f)

    /** 0..1 归一化音量电平，驱动声纹动画。 */
    val level: StateFlow<Float> = _level

    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_SAMPLES * 4),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        record = rec
        running = true
        thread = thread(name = "mic-capture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            rec.startRecording()
            val buf = ShortArray(CHUNK_SAMPLES)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i].toDouble()
                    sum += v * v
                }
                val rms = sqrt(sum / n) / 32768.0
                _level.value = min(1f, (rms * 8).toFloat())
                // Short → S16LE 字节序
                val out = ByteArray(n * 2)
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    out[2 * i] = (s and 0xFF).toByte()
                    out[2 * i + 1] = (s shr 8).toByte()
                }
                onChunk(out)
            }
        }
    }

    fun stop() {
        running = false
        thread?.let { t ->
            t.join(800)
        }
        thread = null
        record?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        record = null
        _level.value = 0f
    }
}
