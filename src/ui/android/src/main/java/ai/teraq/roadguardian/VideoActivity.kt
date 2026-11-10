package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.net.Uri
import android.graphics.Color
import android.view.View
import android.widget.*

/** 합성 시연 영상 재생. 차량에서는 SDK와 UX 허용을 확인한 뒤 보여 준다 */
class VideoActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var privacyMask: View
    private var adapter: PleosAdapter? = null
    private var uxMonitor: VehicleUxMonitor? = null
    private val timer = android.os.Handler(android.os.Looper.getMainLooper())
    private val drivingState = DrivingState(BuildConfig.DEBUG)
    private var automotive = false
    private var ready = false
    private var completed = false
    private var started = false
    private var failed = false
    private var lightUi = true
    private var foreground = false
    private var helpConnected = false
    private var downloadedFile: java.io.File? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun permitted() =
        !automotive || (!drivingState.restricted(android.os.SystemClock.elapsedRealtime()) && uxMonitor?.videoRestricted == false)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getStringExtra("event_id") ?: return finish()
        val helpSession = intent.getStringExtra("help_video_session")
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return finish()
        automotive =
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
        drivingState.useSimulation(
            !automotive && BuildConfig.DEBUG && intent.getBooleanExtra(
                "simulation",
                false
            )
        )
        val light = intent.getBooleanExtra(
            "light",
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        )
        lightUi = light
        val ui = PhoneAccountUi(this, light)
        val background = PleosPalette.background(light)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility =
            if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(16))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                ui.dp(24) + insets.systemWindowInsetLeft,
                ui.dp(16) + insets.systemWindowInsetTop,
                ui.dp(24) + insets.systemWindowInsetRight,
                ui.dp(16) + insets.systemWindowInsetBottom
            )
            insets
        }
        val heading = ui.text(
            if (helpSession == null) "사건 영상" else "영상 연결 준비",
            if (automotive) 36f else 26f,
            true
        )
        root.addView(heading)
        val status = ui.text("영상 준비 중", if (automotive) 24f else 18f)
            .apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        frame.addView(video, FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
        // 준비하는 동안 Surface를 유지하고, 허용 여부를 알 때까지 불투명 가림막을 둔다
        privacyMask = View(this).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(privacyMask, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            frame,
            LinearLayout.LayoutParams(
                -1,
                if (automotive) 0 else ui.dp(220),
                if (automotive) 1f else 0f
            ).apply { bottomMargin = ui.dp(16) })
        if (!automotive) frame.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && root.height > 0) {
                var others = 0
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i)
                    if (child !== frame) {
                        val margins =
                            child.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                        others += child.measuredHeight + (margins?.topMargin
                            ?: 0) + (margins?.bottomMargin ?: 0)
                    }
                }
                val params = frame.layoutParams as LinearLayout.LayoutParams
                val available =
                    (root.height - root.paddingTop - root.paddingBottom - others - params.topMargin - params.bottomMargin).coerceAtLeast(
                        1
                    )
                val height = minOf(kotlin.math.round(width * 9f / 16f).toInt(), available)
                if (params.height != height) {
                    params.height = height
                    frame.layoutParams = params
                }
            }
        }
        val controls = MediaController(this)
        controls.setAnchorView(video)
        video.setMediaController(controls)
        fun update() {
            if (failed) return
            if (!foreground) {
                video.pause()
                privacyMask.visibility = View.VISIBLE
                controls.hide()
                return
            }
            if (!permitted()) {
                video.pause()
                privacyMask.visibility = View.VISIBLE
                controls.hide()
                video.setMediaController(null)
                status.text = "주차 상태 확인이 필요합니다 · 영상 정지"
            } else {
                video.setMediaController(controls)
                if (ready) {
                    video.visibility = View.VISIBLE
                    privacyMask.visibility = View.GONE
                    if (!started) {
                        started = true
                        video.start()
                        controls.show(3000)
                    }
                    if (video.isPlaying) completed = false
                    status.text =
                        if (helpConnected) "영상 연결 · 현장 기록 전달 완료" else if (video.isPlaying) "재생 중" else if (completed) "재생 완료" else "일시 정지"
                }
            }
        }
        video.setOnPreparedListener {
            ready = true
            update()
        }
        video.setOnCompletionListener {
            completed = true
            update()
        }
        fun fail(reason: String) {
            failed = true
            privacyMask.visibility = View.VISIBLE
            video.stopPlayback()
            video.visibility = View.INVISIBLE
            frame.visibility = View.GONE
            controls.hide()
            status.text = "영상을 열지 못했습니다 · $reason"
            status.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            status.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ui.cardColor)
                cornerRadius = ui.dp(14).toFloat()
            }
            downloadedFile?.delete()
            downloadedFile = null
        }
        video.setOnErrorListener { _, _, _ ->
            fail("영상 형식 확인 필요")
            true
        }
        if (helpSession != null) root.addView(ui.text("현장 영상", if (automotive) 22f else 18f))
        root.addView(ui.button(if (helpSession == null) "기록으로 돌아가기" else "연결 화면 닫기") { finish() })
        setContentView(root)
        io.execute {
            try {
                val session = if (helpSession == null) null else {
                    val event = org.json.JSONObject(BackendClient().request("/api/events/$id"))
                    HelpVideoConnectionPanel.session(event)
                        .also { require(it != null && it.optString("session_id") == helpSession) }
                }
                val file = SyntheticVideoInput.download(id, cacheDir)
                if (session != null) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    val actual =
                        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    if (actual != session.getString("video_sha256") || file.length() != session.getLong(
                            "video_bytes"
                        )
                    ) {
                        file.delete()
                        error("video session changed")
                    }
                }
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && !failed) {
                        if (session != null) {
                            helpConnected = true
                            heading.text =
                                session.getString("recipient").substringBefore('-') + " 영상 연결"
                            heading.textSize = if (automotive) 54f else 36f
                        }
                        downloadedFile = file
                        video.setVideoPath(file.absolutePath)
                    } else file.delete()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        fail((error as? SyntheticVideoException)?.display() ?: "연결 상태 확인 필요")
                    }
                }
            }
        }
        if (automotive) {
            uxMonitor = VehicleUxMonitor(this) { runOnUiThread { update() } }.also { it.start() }
            adapter = PleosAdapter(this, {}, { gear, observedAt ->
                runOnUiThread {
                    drivingState.observe(gear, observedAt)
                    update()
                }
            }).also { it.start() }
        }
        val watch = object : Runnable {
            override fun run() {
                update()
                timer.postDelayed(this, 500)
            }
        }
        timer.post(watch)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !automotive) {
            window.decorView.systemUiVisibility =
                if (lightUi) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val mask =
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (lightUi) mask else 0, mask)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        if (::privacyMask.isInitialized) privacyMask.visibility = View.VISIBLE
        if (::video.isInitialized) video.pause()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        timer.removeCallbacksAndMessages(null)
        adapter?.close()
        uxMonitor?.close()
        if (::video.isInitialized) video.stopPlayback()
        downloadedFile?.delete()
        downloadedFile = null
        super.onDestroy()
    }
}
