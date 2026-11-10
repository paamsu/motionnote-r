package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.concurrent.Executors

/** 소유자 기록도 차량 기록과 같은 근거 화면을 쓴다 */
class IncidentReviewActivity : Activity() {
    private val work = Executors.newSingleThreadExecutor()
    private val client = BackendClient()
    private val timer = Handler(Looper.getMainLooper())
    private val drivingState = DrivingState(BuildConfig.DEBUG)
    private var adapter: PleosAdapter? = null
    private var uxMonitor: VehicleUxMonitor? = null
    private var automotive = false
    private var loaded: JSONObject? = null
    private var showing = false
    private var foreground = false
    private var generation = 0
    private var failed = false
    private val light get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
    private fun allowed() =
        foreground && !isFinishing && !isDestroyed && (!automotive || (!drivingState.restricted(
            SystemClock.elapsedRealtime()
        ) && uxMonitor?.restricted == false))

    private fun message(value: String) {
        val ui = PhoneAccountUi(this, light)
        val body = ui.install()
        body.addView(ui.text(value, 26f, true))
        body.addView(ui.button("‹ 기록") { finish() })
    }

    private val watch = object : Runnable {
        override fun run() {
            if (foreground) {
                if (!allowed()) {
                    if (showing) {
                        generation++
                        showing = false
                    }
                    message("주차 상태 확인이 필요합니다")
                } else if (!showing && loaded != null) {
                    showing = true
                    render(checkNotNull(loaded))
                }
            }
            timer.postDelayed(this, 500)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getStringExtra("event_id") ?: return finish()
        automotive =
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
        drivingState.useSimulation(BuildConfig.DEBUG && intent.getBooleanExtra("simulation", false))
        if (automotive && !drivingState.isSimulation) adapter = PleosAdapter(
            this,
            {},
            { gear, time -> drivingState.observe(gear, time) }).also { it.start() }
        if (automotive) uxMonitor = VehicleUxMonitor(this) {}.also { it.start() }
        message("기록 불러오는 중…")
    }

    private fun refresh() {
        val id = intent.getStringExtra("event_id") ?: return
        val ticket = ++generation
        loaded = null
        failed = false
        showing = false
        message("기록 불러오는 중…")
        work.execute {
            try {
                val event = JSONObject(client.request("/api/events/$id"))
                runOnUiThread {
                    if (foreground && ticket == generation && !isDestroyed && !isFinishing) {
                        if (event.optJSONObject("assessment")
                                ?.optString("kind") == "parking" && event.opt("evidence_valid") == true && event.optString(
                                "status"
                            ) != "dismissed"
                        ) loaded = event
                        else {
                            failed = true
                            message("기록 확인 필요")
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (foreground && ticket == generation && !isDestroyed) {
                        failed = true
                        message("기록을 열 수 없습니다")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        refresh()
        timer.removeCallbacks(watch)
        timer.post(watch)
    }

    override fun onPause() {
        foreground = false
        generation++
        showing = false
        timer.removeCallbacks(watch)
        message("기록 열람 대기")
        super.onPause()
    }

    override fun onDestroy() {
        timer.removeCallbacksAndMessages(null)
        adapter?.close()
        uxMonitor?.close()
        work.shutdownNow()
        super.onDestroy()
    }

    private fun render(event: JSONObject) {
        val ui = PhoneAccountUi(this, light)
        val body = ui.install()
        val epoch = ++generation
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val video = event.optJSONObject("video")
        if (video?.opt("integrity_valid") == true) actions.addView(ui.button("합성 입력 영상") {
            if (allowed() && generation == epoch) startActivity(
                Intent(
                    this,
                    VideoActivity::class.java
                ).putExtra("event_id", event.getString("id"))
                    .putExtra("simulation", drivingState.isSimulation).putExtra("light", light)
            )
        })
        VehicleEventDetail.render(
            this,
            body,
            event,
            light,
            actions,
            client,
            allowed = { allowed() && epoch == generation })
        body.addView(ui.button("‹ 기록") { finish() }, 0)
    }
}
