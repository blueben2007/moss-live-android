package cn.moss.live.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import cn.moss.live.protocol.AudioChunk
import cn.moss.live.protocol.MossEvent
import cn.moss.live.protocol.MossLiveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * 扬声器播放：48kHz 单声道 PCM S16LE，按接收顺序流式播放，播完逐块回执。
 *
 * 协议要点：
 * ④ 每块写完即回 playback.chunk.completed；某 run 的全部块播完且回执齐后回 playback.completed
 * ⑤ 收到 response.audio.stop → 立即停播该 run、清空队列、回 playback.stopped（含已播毫秒数）
 *
 * 翻译模式不实例化本类——代码层面不存在 TTS/播放路径（预览承诺的静态保证）。
 */
class SpeakerPlayback(private val client: MossLiveClient) {

    private class RunState {
        @Volatile var turnId = ""
        @Volatile var received = 0
        @Volatile var played = 0
        @Volatile var serverDone = false
        var completedSent = false
    }

    private val queue = ConcurrentLinkedQueue<AudioChunk>()
    private val runs = ConcurrentHashMap<String, RunState>()
    private var track: AudioTrack? = null
    private var playerThread: Thread? = null
    private var collector: Job? = null
    @Volatile private var running = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        playerThread = thread(name = "speaker-playback") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuf = AudioTrack.getMinBufferSize(
                48_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val t = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(48_000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                maxOf(minBuf, 48_000), // ≥1s 缓冲，抗网络抖动
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            track = t
            t.play()
            while (running) {
                val chunk = queue.poll()
                if (chunk == null) {
                    Thread.sleep(8)
                    continue
                }
                t.write(chunk.pcm, 0, chunk.pcm.size, AudioTrack.WRITE_BLOCKING)
                val state = runs[chunk.runId] ?: continue
                state.played++
                client.chunkCompleted(chunk.runId, chunk.turnId, chunk.chunkId)
                maybeCompleteRun(chunk.runId, chunk.turnId)
            }
        }
        collector = scope.launch {
            client.events.collect { ev ->
                when (ev) {
                    is AudioChunk -> {
                        runs.computeIfAbsent(ev.runId) { RunState() }.also {
                            it.turnId = ev.turnId
                            it.received++
                        }
                        queue.add(ev)
                    }
                    is MossEvent.ResponseAudioDone -> onServerDone(ev.runId)
                    is MossEvent.ResponseEnded -> onServerDone(ev.runId)
                    is MossEvent.ResponseAudioStop -> interrupt(ev.runId, ev.turnId)
                    else -> Unit
                }
            }
        }
    }

    private fun onServerDone(runId: String) {
        val state = runs[runId] ?: return
        state.serverDone = true
        // 播放线程可能早已写完最后一块、而 response.audio.done 比它晚到：
        // 这时不会再有后续块触发检查，必须在这里补发 playback.completed，
        // 否则该 run 永远不收尾，服务端会一直等（表现为对话中断）。
        maybeCompleteRun(runId, state.turnId)
    }

    private fun maybeCompleteRun(runId: String, turnId: String) {
        val state = runs[runId] ?: return
        // 播放线程与事件协程都会调用：加锁保证只发一次
        val shouldSend = synchronized(state) {
            if (state.serverDone && state.played >= state.received && !state.completedSent) {
                state.completedSent = true
                true
            } else {
                false
            }
        }
        if (shouldSend) client.playbackCompleted(runId, turnId)
    }

    /** 被打断：停播清队，回 playback.stopped（含当前已播毫秒数）。 */
    private fun interrupt(runId: String, turnId: String) {
        queue.clear()
        val t = track ?: return
        val playedMs = t.playbackHeadPosition * 1000L / 48_000
        try {
            t.pause()
            t.flush()
            t.play()
        } catch (_: IllegalStateException) {
        }
        runs.values.forEach { it.serverDone = true } // 旧 run 全部终结，防止悬挂
        client.playbackStopped(runId, turnId, playedMs)
    }

    fun stopAll() {
        running = false
        playerThread?.join(800)
        playerThread = null
        collector?.cancel()
        collector = null
        track?.run {
            try {
                pause()
                flush()
                release()
            } catch (_: IllegalStateException) {
            }
        }
        track = null
        queue.clear()
        runs.clear()
    }
}
