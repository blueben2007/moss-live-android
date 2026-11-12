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
 * 播放期间也保持采集（支持全双工打断）。
 *
 * 音源选择：助理用 VOICE_COMMUNICATION（系统回声消除，防止扬声器回答被当成人声）；
 * 翻译不播放声音，用普通 MIC——部分 ROM 上 VOICE_COMMUNICATION 音源会静音，绕开兼容坑。
 */
class MicCapture(private val onChunk: (ByteArray) -> Unit) {

    companion object {
        private const val SAMPLE_RATE = 16_000
        // 官方 Playground 同款帧大小：320 采样 = 20ms/帧
        private const val CHUNK_SAMPLES = 320
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    private val _level = MutableStateFlow(0f)

    /** 0..1 归一化音量电平，驱动声纹动画。 */
    val level: StateFlow<Float> = _level

    private val _captureError = MutableStateFlow<String?>(null)

    /** 采集异常（初始化失败、持续读失败等），供界面诊断显示，不再抛异常炸掉事件循环。 */
    val captureError: StateFlow<String?> = _captureError

    fun start(source: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
        if (running) return
        _captureError.value = null
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_SAMPLES * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            _captureError.value = "AudioRecord 初始化失败（检查是否授予录音权限）"
            return
        }
        record = rec
        running = true
        thread = thread(name = "mic-capture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                _captureError.value = "录音启动失败（recordingState 异常）"
                return@thread
            }
            val buf = ShortArray(CHUNK_SAMPLES)
            var failures = 0
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    failures++
                    if (failures > 100) {
                        _captureError.value = "录音读取持续失败（code=$n），采集已停止"
                        break
                    }
                    continue
                }
                if (n == 0) continue
                failures = 0
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
