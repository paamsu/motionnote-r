package ai.teraq.roadguardian

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/** 앱에 넣어 둔 한국어 안내 문장을 읽는다. 음성 엔진 다운로드나 네트워크 합성은 쓰지 않는다 */
class ComparisonSpeech(context: Context, private val startAllowed: () -> Boolean = { true }) {
    companion object {
        const val MESSAGE = "시험 비교 자료가 준비됐습니다."
    }

    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(AudioManager::class.java)
    private val policy = ComparisonSpeechPolicy()
    private var player: MediaPlayer? = null
    private var ready = false
    private var closed = false
    private var focused = false
    var status = "음성 준비 중"
        private set
    internal var attempts = 0
        private set
    internal var starts = 0
        private set
    internal var completions = 0
        private set
    internal val playing get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    private val attributes =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> if (change <= 0) stop() }, handler).build()

    init {
        val output = MediaPlayer()
        try {
            output.setAudioAttributes(attributes)
            context.resources.openRawResourceFd(R.raw.comparison_ready_v206)
                .use { output.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            output.prepare()
            output.setOnCompletionListener {
                if (!closed) {
                    completions++
                    releaseFocus()
                    status = "한국어 안내 준비됨"
                }
            }
            output.setOnErrorListener { _, _, _ ->
                ready = false
                stop()
                status = "음성 재생 실패"
                true
            }
            player = output
            ready = true
            status = "한국어 안내 준비됨"
        } catch (_: Exception) {
            output.release()
            status = "음성 파일 사용 불가"
        }
    }

    fun update(sequence: Long, permitted: Boolean, freshToStart: Boolean) {
        if (closed) return
        if (!permitted) {
            stop()
            return
        }
        if (!policy.take(sequence, freshToStart, ready)) return
        attempts++
        if (!startAllowed()) return
        focused =
            runCatching { manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(
                false
            )
        if (!focused) {
            status = "다른 소리 우선 · 안내 생략"
            return
        }
        // 재생 직전에 권한을 다시 확인한다. 오디오 포커스를 받는 사이에 바뀔 수 있다
        if (!startAllowed()) {
            releaseFocus()
            return
        }
        try {
            player?.seekTo(0)
            player?.start()
            starts++
            status = "안내 재생 중"
        } catch (_: Exception) {
            releaseFocus()
            status = "음성 재생 실패"
        }
    }

    private fun releaseFocus() {
        if (focused) {
            focused = false
            runCatching { manager.abandonAudioFocusRequest(focus) }
        }
    }

    fun stop() {
        if (playing) {
            runCatching {
                player?.pause()
                player?.seekTo(0)
            }
        }
        releaseFocus()
        if (ready) status = "한국어 안내 준비됨"
    }

    fun close() {
        closed = true
        stop()
        player?.release()
        player = null
        handler.removeCallbacksAndMessages(null)
    }
}
