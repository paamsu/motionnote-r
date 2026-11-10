package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Controlled permission callback host, not Main/OEM/physical driving. */
class SurroundLifecyclePreviewActivity : Activity() {
    var permitted = true
    lateinit var panel: SyntheticSurroundPanel
    lateinit var body: LinearLayout
    lateinit var marker: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = PhoneAccountUi(this, true)
        body = ui.install()
        body.addView(ui.text("합성 사진 표시 검사", 30f, true))
        marker = ui.text("제어된 표시 허용 상태", 20f)
        body.addView(marker)
        panel = SyntheticSurroundPanel(
            this,
            ui,
            JSONObject(intent.getStringExtra("event")!!),
            BackendClient(intent.getStringExtra("endpoint")!!)
        ) { permitted && !isFinishing && !isDestroyed }
        body.addView(panel)
    }

    fun revoke() {
        permitted = false
        marker.text = "제어된 표시 허용 해제"
    }

    fun detachPanel() {
        body.removeView(panel)
        marker.text = "사진 화면 분리"
    }
}

/** Real HTTP gates and production panel; no direct taps, Main transitions or OEM events. */
class SurroundLifecycleProbe : Instrumentation() {
    private var endpoint = "http://127.0.0.1:9488"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        endpoint = arguments?.getString("endpoint") ?: endpoint
        start()
    }

    override fun onStart() {
        val output = Bundle()
        var activity: SurroundLifecyclePreviewActivity? = null
        var main: MainActivity? = null
        val client = BackendClient(endpoint)
        val rows = JSONArray()
        var activeRun: String? = null
        try {
            fun field(panel: SyntheticSurroundPanel, name: String): Any? =
                SyntheticSurroundPanel::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(panel)

            fun labels(root: View): List<TextView> {
                val all = mutableListOf<TextView>()
                fun visit(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) all.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(root)
                return all
            }

            fun waitUi(message: String, condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 12000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ok = false
                    runOnMainSync { ok = condition() }
                    if (ok) return
                    Thread.sleep(40)
                }
                error("Timed out: $message")
            }

            fun status(run: String) =
                JSONObject(client.request("/api/dev/surround-lifecycle/status/$run"))

            fun entered(run: String) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (status(run).getBoolean("entered")) return
                    Thread.sleep(35)
                }
                error("Gate was not entered")
            }

            fun save(name: String, expectedText: String? = null) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    waitForIdleSync()
                    var anchor: Bitmap? = null
                    var left = 0
                    var top = 0
                    var ink = 0
                    if (expectedText != null) runOnMainSync {
                        val host: Activity? = activity ?: main
                        val view =
                            host?.let { labels(it.window.decorView).firstOrNull { node -> node.text.toString() == expectedText && node.layout?.text?.toString() == expectedText && !node.isLayoutRequested && node.width > 0 && node.height > 0 } }
                        if (view != null) {
                            val visible = Rect()
                            if (view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height) {
                                val at = IntArray(2)
                                view.getLocationOnScreen(at)
                                left = at[0]
                                top = at[1]
                                ink = view.currentTextColor
                                anchor = Bitmap.createBitmap(
                                    view.width,
                                    view.height,
                                    Bitmap.Config.ARGB_8888
                                ).also { view.draw(Canvas(it)) }
                            }
                        }
                    }
                    if (expectedText != null && anchor == null) {
                        Thread.sleep(50)
                        continue
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    val matched = try {
                        if (expectedText == null) true else {
                            val mask = checkNotNull(anchor)
                            var samples = 0
                            var matching = 0
                            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                                val expected = mask.getPixel(x, y)
                                if (Color.alpha(expected) < 245 || kotlin.math.abs(
                                        Color.red(
                                            expected
                                        ) - Color.red(ink)
                                    ) > 24 || kotlin.math.abs(
                                        Color.green(expected) - Color.green(
                                            ink
                                        )
                                    ) > 24 || kotlin.math.abs(Color.blue(expected) - Color.blue(ink)) > 24
                                ) continue
                                samples++
                                if (left + x !in 0 until screenshot.width || top + y !in 0 until screenshot.height) continue
                                val actual = screenshot.getPixel(left + x, top + y)
                                if (kotlin.math.abs(Color.red(actual) - Color.red(expected)) < 12 && kotlin.math.abs(
                                        Color.green(actual) - Color.green(expected)
                                    ) < 12 && kotlin.math.abs(
                                        Color.blue(actual) - Color.blue(
                                            expected
                                        )
                                    ) < 12
                                ) matching++
                            }
                            samples > 30 && matching >= samples * .97
                        }
                    } finally {
                        anchor?.recycle()
                    }
                    try {
                        if (matched) {
                            File(
                                targetContext.filesDir,
                                "surround-lifecycle-$name.png"
                            ).outputStream().use {
                                check(
                                    screenshot.compress(
                                        Bitmap.CompressFormat.PNG,
                                        100,
                                        it
                                    )
                                )
                            }
                            return
                        }
                    } finally {
                        screenshot.recycle()
                    }
                    Thread.sleep(60)
                }
                error("Actual screenshot glyphs absent: $name / $expectedText")
            }

            fun cleared(panel: SyntheticSurroundPanel, detached: Boolean = false) {
                check(field(panel, "bitmap") == null)
                check((field(panel, "separate") as List<*>).isEmpty())
                val grid = field(panel, "grid") as View
                check(!grid.isShown && (detached || grid.visibility == View.GONE))
                check(labels(panel).none { it.text.toString() == "합성 사진 4개 보관" })
            }

            fun drain(worker: ExecutorService?) {
                checkNotNull(worker)
                if (worker.isShutdown) check(worker.awaitTermination(12, TimeUnit.SECONDS))
                else worker.submit {}.get(12, TimeUnit.SECONDS)
                waitForIdleSync()
            }

            fun loadedPixels(host: Activity, panel: SyntheticSurroundPanel) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<Triple<Bitmap, Int, Int>>()
                    runOnMainSync {
                        val photos = field(panel, "photos") as List<*>
                        check(
                            photos.size == 4 && (field(
                                panel,
                                "separate"
                            ) as List<*>).filterIsInstance<Bitmap>().size == 4
                        )
                        for (item in photos) {
                            val view = item as View
                            val rect = Rect()
                            check(view.getGlobalVisibleRect(rect) && rect.width() == view.width && rect.height() == view.height)
                            val at = IntArray(2)
                            view.getLocationOnScreen(at)
                            anchors.add(
                                Triple(
                                    Bitmap.createBitmap(
                                        view.width,
                                        view.height,
                                        Bitmap.Config.ARGB_8888
                                    ).also { view.draw(Canvas(it)) }, at[0], at[1]
                                )
                            )
                        }
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    val matches = try {
                        anchors.all { (bitmap, left, top) ->
                            var count = 0
                            var matched = 0
                            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                                val expected = bitmap.getPixel(x, y)
                                if (Color.alpha(expected) < 245) continue
                                count++
                                if (left + x !in 0 until screenshot.width || top + y !in 0 until screenshot.height) continue
                                val actual = screenshot.getPixel(left + x, top + y)
                                if (kotlin.math.abs(Color.red(actual) - Color.red(expected)) < 12 && kotlin.math.abs(
                                        Color.green(actual) - Color.green(expected)
                                    ) < 12 && kotlin.math.abs(
                                        Color.blue(actual) - Color.blue(
                                            expected
                                        )
                                    ) < 12
                                ) matched++
                            }
                            count > 100 && matched >= count * .97
                        }
                    } finally {
                        anchors.forEach { it.first.recycle() }
                        screenshot.recycle()
                    }
                    if (matches) return
                    Thread.sleep(60)
                }
                error("Actual loaded photo pixels missing: ${host.javaClass.simpleName}")
            }
            for (case in listOf(
                "loaded_revoke",
                "front_revoke",
                "last_revoke",
                "last_detach",
                "fresh_dismissed",
                "fresh_error",
                "fresh_changed_hash"
            )) {
                val run = UUID.randomUUID().toString()
                activeRun = run
                val gate = when (case) {
                    "loaded_revoke" -> "normal"
                    "front_revoke" -> "front_gate"
                    "last_revoke", "last_detach" -> "last_gate"
                    else -> case
                }
                val armed = JSONObject(
                    client.request(
                        "/api/dev/surround-lifecycle/arm",
                        JSONObject().put("case", gate).put("run_id", run)
                    )
                )
                val event = armed.getJSONObject("event")
                SyntheticSurroundViews.validate(
                    event.getJSONObject("surround_views"),
                    event.getString("id"),
                    event.getString("hash"),
                    true
                )
                activity = startActivitySync(
                    Intent(
                        targetContext,
                        SurroundLifecyclePreviewActivity::class.java
                    ).putExtra("event", event.toString()).putExtra("endpoint", endpoint)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ) as SurroundLifecyclePreviewActivity
                val host = checkNotNull(activity)
                val panel = host.panel
                var worker: ExecutorService? = null
                runOnMainSync { worker = field(panel, "worker") as ExecutorService? }
                if (case == "loaded_revoke") {
                    waitUi("four photos loaded") { labels(panel).any { it.text.toString() == "합성 사진 4개 보관" } }
                    loadedPixels(host, panel)
                    save("loaded")
                    var bitmaps: List<Bitmap> = emptyList()
                    runOnMainSync {
                        bitmaps = (field(panel, "separate") as List<*>).filterIsInstance<Bitmap>()
                        host.revoke()
                    }
                    waitUi("loaded photos cleared") {
                        (field(
                            panel,
                            "separate"
                        ) as List<*>).isEmpty() && !(field(panel, "grid") as View).isShown
                    }
                    drain(worker)
                    runOnMainSync {
                        cleared(panel)
                        check(bitmaps.all { it.isRecycled })
                        check(labels(panel).any { it.text.toString() == "사진 표시 종료" })
                    }
                } else {
                    entered(run)
                    if (case == "last_detach") runOnMainSync {
                        host.detachPanel()
                        cleared(panel, true)
                    }
                    else if (!case.startsWith("fresh_")) {
                        runOnMainSync { host.revoke() }
                        waitUi("pending display ended") {
                            !(field(
                                panel,
                                "grid"
                            ) as View).isShown && labels(panel).any { it.text.toString() == "사진 표시 종료" }
                        }
                    }
                    client.request(
                        "/api/dev/surround-lifecycle/release",
                        JSONObject().put("run_id", run)
                    )
                    drain(worker)
                    if (case.startsWith("fresh_")) waitUi("fresh source rejected") { labels(panel).any { it.text.toString() == if (case == "fresh_error") "센서 기록 보관 · 사진 확인 실패" else "기록 확인 필요" } }
                    runOnMainSync { cleared(panel, case == "last_detach") }
                    val until = SystemClock.elapsedRealtime() + 650
                    while (SystemClock.elapsedRealtime() < until) {
                        runOnMainSync { cleared(panel, case == "last_detach") }
                        Thread.sleep(50)
                    }
                }
                var finalStatus = status(run)
                val completedDeadline = SystemClock.elapsedRealtime() + 3000
                while (!finalStatus.getBoolean("completed") && SystemClock.elapsedRealtime() < completedDeadline) {
                    Thread.sleep(35)
                    finalStatus = status(run)
                }
                check(finalStatus.getBoolean("completed") && !finalStatus.getBoolean("timed_out"))
                if (case != "loaded_revoke") check(
                    finalStatus.getBoolean("entered") && finalStatus.getBoolean(
                        "released"
                    )
                )
                if (case in setOf("front_revoke", "last_revoke", "last_detach")) {
                    val auditRows = finalStatus.getJSONArray("audit")
                    val requested = (0 until auditRows.length()).map { auditRows.getJSONObject(it) }
                        .filter { it.getString("action") == "request_started" }
                        .map { it.getString("path") }
                    val expectedDirections =
                        if (case == "front_revoke") listOf("front") else SyntheticSurroundViews.directions
                    check(requested == expectedDirections.map { "/api/events/${event.getString("id")}/surround-views/$it" }) { "Unexpected requests before or after release: $requested" }
                    val audit = finalStatus.getJSONArray("audit")
                    var released = false
                    for (index in 0 until audit.length()) {
                        val item = audit.getJSONObject(index)
                        if (item.getString("action") == "release") released = true
                        else if (released) check(item.getString("action") != "request_started") { "Remaining HTTP started after cancellation/release" }
                    }
                    check(released)
                }
                val expectedPixels = when (case) {
                    "last_detach" -> "사진 화면 분리"
                    "fresh_dismissed", "fresh_changed_hash" -> "기록 확인 필요"
                    "fresh_error" -> "센서 기록 보관 · 사진 확인 실패"
                    else -> "사진 표시 종료"
                }
                save(case, expectedPixels)
                rows.put(
                    JSONObject().put("case", case).put("run_id", run)
                        .put("event_id", event.getString("id"))
                        .put("sensor_sha256", event.getString("hash"))
                        .put("actual_status_glyph_pixels_verified", true).put("grid_hidden", true)
                        .put("display_bitmaps_cleared", true).put("worker_drained", true)
                        .put("no_success_after_release", true)
                        .put("loaded_pixels_verified", case == "loaded_revoke").put(
                        "remaining_http_after_release_checked",
                        case in setOf("front_revoke", "last_revoke", "last_detach")
                    ).put("gate_status", finalStatus)
                )
                runOnMainSync { host.finish() }
                activity = null
                client.request("/api/dev/surround-lifecycle/reset", JSONObject().put("run_id", run))
                activeRun = null
            }
            val mainRun = UUID.randomUUID().toString()
            activeRun = mainRun
            val mainEvent = JSONObject(
                client.request(
                    "/api/dev/surround-lifecycle/arm",
                    JSONObject().put("case", "normal").put("run_id", mainRun)
                )
            ).getJSONObject("event")
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val host = checkNotNull(main)
            fun mainField(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun panels(): List<SyntheticSurroundPanel> {
                val result = mutableListOf<SyntheticSurroundPanel>()
                fun visit(view: View) {
                    if (view is SyntheticSurroundPanel) result.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                        view.getChildAt(
                            i
                        )
                    )
                }
                visit(host.window.decorView)
                return result
            }
            waitUi("Main OEM allowance") {
                !mainField("busy").getBoolean(host) && !mainField("comparisonLoading").getBoolean(
                    host
                ) && (mainField("uxMonitor").get(host) as? VehicleUxMonitor)?.restricted == false
            }
            runOnMainSync {
                val timer = mainField("timer").get(host) as android.os.Handler
                for (name in listOf("feedPoll", "drivingWatch")) timer.removeCallbacks(
                    mainField(
                        name
                    ).get(host) as Runnable
                )
                mainField("client").set(host, client)
                mainField("selected").set(host, "기록")
                mainField("light").setBoolean(host, true)
                (mainField("drivingState").get(host) as DrivingState).apply {
                    useSimulation(true)
                    setSimulatedMoving(false)
                }
                mainField("opened").set(host, null)
                mainField("detailReturnId").set(host, mainEvent.getString("id"))
                MainActivity::class.java.getDeclaredMethod("refreshReturnedDetail")
                    .apply { isAccessible = true }.invoke(host)
            }
            waitUi("Main four photos") { labels(host.window.decorView).any { it.text.toString() == "합성 사진 4개 보관" } && panels().size == 1 }
            lateinit var oldPanel: SyntheticSurroundPanel
            var oldBitmaps: List<Bitmap> = emptyList()
            runOnMainSync {
                oldPanel = panels().single()
                oldBitmaps = (field(oldPanel, "separate") as List<*>).filterIsInstance<Bitmap>()
            }
            loadedPixels(host, oldPanel)
            save("main-loaded")
            runOnMainSync {
                (mainField("drivingState").get(host) as DrivingState).setSimulatedMoving(true)
                (mainField("drivingWatch").get(host) as Runnable).run()
            }
            waitUi("Main simulated driving clears photos") {
                !oldPanel.isAttachedToWindow && panels().isEmpty() && labels(
                    host.window.decorView
                ).any { it.text.toString() == "주행 시연" }
            }
            runOnMainSync {
                cleared(oldPanel, true)
                check(oldBitmaps.all { it.isRecycled })
            }
            save("main-simulated-drive", "주행 시연")
            rows.put(
                JSONObject().put("case", "main_debug_simulated_drive")
                    .put("event_id", mainEvent.getString("id")).put("loaded_pixels_verified", true)
                    .put("grid_detached", true).put("display_bitmaps_cleared", true)
                    .put("actual_status_glyph_pixels_verified", true)
                    .put("real_oem_transition", false)
            )
            runOnMainSync { host.finish() }
            main = null
            client.request("/api/dev/surround-lifecycle/reset", JSONObject().put("run_id", mainRun))
            activeRun = null
            File(
                targetContext.filesDir,
                "surround-lifecycle.json"
            ).writeText(
                JSONObject().put("pass", true).put("cases", rows).put(
                    "scope",
                    "Production SyntheticSurroundPanel with real gated HTTP and controlled allowed callback / View detachment. Seven controlled preview lifecycle cases plus one actual Main debug-simulated moving transition. Programmatic emulator actions; no real OEM driving transition, direct touch, physical vehicle, or camera evidence."
                ).toString(2)
            )
            output.putString(
                "stream",
                "PASS: seven controlled HTTP surround lifecycle cases plus Main debug-simulated driving; loaded pixels, bitmap clearing and late response refusal.\n"
            )
            finish(Activity.RESULT_OK, output)
        } catch (failure: Throwable) {
            runCatching {
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(targetContext.filesDir, "surround-lifecycle-failure.png").outputStream()
                        .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            File(
                targetContext.filesDir,
                "surround-lifecycle-failure.txt"
            ).writeText(failure.stackTraceToString())
            output.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, output)
        } finally {
            activeRun?.let {
                runCatching {
                    client.request(
                        "/api/dev/surround-lifecycle/reset",
                        JSONObject().put("run_id", it)
                    )
                }
            }
            runOnMainSync {
                activity?.finish()
                main?.finish()
            }
        }
    }
}
