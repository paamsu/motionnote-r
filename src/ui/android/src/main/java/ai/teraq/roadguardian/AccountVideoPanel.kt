package ai.teraq.roadguardian

import android.app.Activity
import android.graphics.Color
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File

/** 표시만 한다. 권한과 무결성을 확인한 로컬 파일만 받는다 */
class AccountVideoPanel(activity: Activity, ui: PhoneAccountUi, body: LinearLayout) {
    private val status = ui.notice("영상 다운로드와 무결성 검사 중입니다.")
    private val video = VideoView(activity)
    private val frame = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(230)).apply { bottomMargin = ui.dp(20) }
        addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        visibility = View.GONE
    }
    private val play = ui.button("영상 준비 중", true) { toggle() }.apply {
        isEnabled = false
        visibility = View.GONE
    }
    private val timer = Handler(Looper.getMainLooper())
    private var ready = false
    private var stopped = true
    private var dragging = false
    private var completed = false
    private var duration = 0
    private var player: MediaPlayer? = null
    private val time = ui.notice("00:00 / 00:00").apply { visibility = View.GONE }
    private val seek = SeekBar(activity).apply {
        contentDescription = "영상 재생 위치"
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(48))
        progressTintList = ColorStateList.valueOf(Color.rgb(40, 95, 166))
        thumbTintList = progressTintList
        progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(150, 165, 184))
        isEnabled = false
        visibility = View.GONE
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!ready) return
            if (!dragging) updatePosition(if (completed) duration else video.currentPosition)
            timer.postDelayed(this, 250)
        }
    }

    init {
        body.addView(status)
        body.addView(frame)
        body.addView(time)
        body.addView(seek)
        body.addView(play)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
            }

            override fun onProgressChanged(bar: SeekBar, position: Int, fromUser: Boolean) {
                if (fromUser) {
                    time.text = "${stamp(position)} / ${stamp(duration)}"
                    if (!dragging) moveTo(position)
                }
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                moveTo(bar.progress)
                dragging = false
            }
        })
        video.setOnPreparedListener { player ->
            if (stopped) return@setOnPreparedListener
            this.player = player
            duration = player.duration.coerceAtLeast(0)
            seek.max = duration
            ready = true
            completed = false
            play.isEnabled = true
            seek.isEnabled = duration > 0
            play.text = "영상 재생"
            timer.removeCallbacks(tick)
            timer.post(tick)
        }
        video.setOnCompletionListener {
            if (!stopped) {
                completed = true
                play.text = "다시 재생"
                updatePosition(duration)
            }
        }
        video.setOnErrorListener { _, _, _ ->
            if (!stopped) fail("영상을 재생하지 못했습니다. 기록에서 다시 열어 주세요.")
            true
        }
    }

    private fun toggle() {
        if (!ready) return
        if (video.isPlaying) {
            video.pause()
            play.text = "영상 재생"
        } else {
            if (completed) {
                video.seekTo(0)
                completed = false
            }
            video.start()
            play.text = "일시 정지"
        }
    }

    private fun moveTo(position: Int) {
        if (!ready) return
        completed = false
        player?.seekTo(position.coerceIn(0, duration).toLong(), MediaPlayer.SEEK_CLOSEST)
        play.text = if (video.isPlaying) "일시 정지" else "영상 재생"
    }

    private fun updatePosition(position: Int) {
        val bounded = position.coerceIn(0, duration)
        seek.progress = bounded
        time.text = "${stamp(bounded)} / ${stamp(duration)}"
    }

    private fun stamp(milliseconds: Int): String {
        val seconds = milliseconds / 1000
        return java.lang.String.format(
            java.util.Locale.ROOT,
            "%02d:%02d",
            seconds / 60,
            seconds % 60
        )
    }

    fun show(file: File, synthetic: Boolean) {
        stopped = false
        status.text = if (synthetic) "합성 시험 영상 · 실제 촬영 아님" else "서버에 저장된 영상 · 파일 무결성 확인됨"
        ready = false
        completed = false
        play.isEnabled = false
        seek.isEnabled = false
        timer.removeCallbacks(tick)
        frame.visibility = View.VISIBLE
        play.visibility = View.VISIBLE
        seek.visibility = View.VISIBLE
        time.visibility = View.VISIBLE
        video.setVideoPath(file.absolutePath)
    }

    fun fail(message: String) {
        stop()
        status.text = message
    }

    fun stop() {
        stopped = true
        ready = false
        player = null
        timer.removeCallbacks(tick)
        video.stopPlayback()
        play.isEnabled = false
        seek.isEnabled = false
        frame.visibility = View.GONE
        play.visibility = View.GONE
        seek.visibility = View.GONE
        time.visibility = View.GONE
    }
}
