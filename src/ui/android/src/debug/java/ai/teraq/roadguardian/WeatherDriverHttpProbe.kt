package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/** Authored loopback HTTP and event-state checks in actual Main. No taps or physical driving. */
class WeatherDriverHttpProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = screen
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            val render =
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            val visible = MainActivity::class.java.getDeclaredMethod(
                "driverEventVisible",
                java.lang.Long.TYPE
            ).apply { isAccessible = true }

            fun current(now: Long = SystemClock.elapsedRealtime()) =
                visible.invoke(activity, now) as? JSONObject

            fun await(test: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 18000
                while (SystemClock.elapsedRealtime() < end) {
                    var accepted = false
                    runOnMainSync { accepted = test() }
                    if (accepted) return
                    Thread.sleep(50)
                }
                error("Main did not reach the required actual OEM-permitted state")
            }

            fun nodes(): List<View> {
                val values = mutableListOf<View>()
                fun visit(view: View) {
                    if (!view.isShown) return
                    values.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                        view.getChildAt(
                            i
                        )
                    )
                }
                visit(activity.window.decorView)
                return values
            }

            fun labels() = nodes().filterIsInstance<TextView>().map { it.text.toString() }
            await {
                !field("busy").getBoolean(activity) && !field("comparisonLoading").getBoolean(
                    activity
                ) &&
                        (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false
            }
            lateinit var state: DrivingState
            lateinit var notice: DriverEventNoticeState
            runOnMainSync {
                val handler = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) handler.removeCallbacks(field(name).get(activity) as Runnable)
                (field("adapter").get(activity) as PleosAdapter).close()
                state = field("drivingState").get(activity) as DrivingState
                state.useSimulation(true)
                state.setSimulatedMoving(true)
                notice = field("driverEventNotice").get(activity) as DriverEventNoticeState
            }
            fun asset(name: String, key: String? = null): JSONObject {
                val value = JSONObject(
                    targetContext.assets.open(name).bufferedReader().use { it.readText() })
                return JSONObject((if (key == null) value else value.getJSONObject(key)).toString())
                    .put("id", UUID.randomUUID().toString())
                    .put("created", System.currentTimeMillis() / 1000.0)
            }

            val followup = JSONObject(
                targetContext.assets.open("driver-followup-v221.json").bufferedReader()
                    .use { it.readText() })
            val weather = JSONObject(
                File(
                    targetContext.filesDir,
                    "weather-driver-cases.json"
                ).readText()
            ).getJSONObject("valid")
            val weatherCases =
                listOf("collecting", "washer_suppressed", "recovered").map { status ->
                    val event =
                        JSONObject(weather.toString()).put("id", UUID.randomUUID().toString())
                    event.getJSONObject("assessment").getJSONObject("weather_timeline_assessment")
                        .put("status", status)
                    Triple("weather-$status", event, EventBriefState.from(event).title)
                }
            val cases = listOf(
                Triple("pothole", asset("dossier-road-v149.json"), "포트홀 감지"),
                Triple("crash", asset("crash-cases-v133.json", "ready"), "사고 감지"),
                Triple("diagnosis", asset("diagnostic-cases-v135.json", "measurement"), "저전압 확인"),
                Triple("repeat-low", followup.getJSONObject("low"), "저전압 다시 감지"),
                Triple("recovered", followup.getJSONObject("recovered"), "재측정 전압 회복")
            ) + weatherCases
            val rows = JSONArray()
            fun resetNotice() {
                notice = DriverEventNoticeState()
                field("driverEventNotice").set(activity, notice)
            }

            fun seed(event: JSONObject, now: Long) {
                resetNotice()
                notice.accept(JSONArray(), now)
                notice.accept(JSONArray().put(event), now)
            }

            val poll = MainActivity::class.java.getDeclaredMethod("pollComparisons")
                .apply { isAccessible = true }

            fun httpPoll(body: String) {
                val failure = AtomicReference<Throwable?>()
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 8000
                    val worker = Thread {
                        try {
                            server.accept().use { socket ->
                                socket.soTimeout = 5000
                                val input = socket.getInputStream().bufferedReader()
                                check(input.readLine() == "GET /api/events HTTP/1.1")
                                while (true) {
                                    val line = input.readLine() ?: break
                                    if (line.isEmpty()) break
                                }
                                val bytes = body.toByteArray(Charsets.UTF_8)
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(bytes)
                                    flush()
                                }
                            }
                        } catch (error: Throwable) {
                            failure.set(error)
                        }
                    }.apply { start() }
                    runOnMainSync {
                        field("client").set(
                            activity,
                            BackendClient("http://127.0.0.1:${server.localPort}")
                        )
                        poll.invoke(activity)
                        check(field("comparisonLoading").getBoolean(activity))
                    }
                    await { !field("comparisonLoading").getBoolean(activity) }
                    worker.join(9000)
                    check(!worker.isAlive)
                    failure.get()?.let { throw it }
                }
                waitForIdleSync()
            }
            for ((kind, event, title) in cases) {
                val message = when (kind) {
                    "weather-collecting" -> "기록 보관"
                    "weather-washer_suppressed" -> "강우 판단 보류"
                    "weather-recovered" -> "시야를 확인하세요"
                    "pothole" -> "센서 기록 보관"
                    "crash" -> "정차 후 보험 안내"
                    "diagnosis" -> "정차 후 센터 안내"
                    "repeat-low" -> "서비스센터 방문 추천"
                    else -> "측정 구간의 결과"
                }
                runOnMainSync {
                    state.useSimulation(true)
                    state.setSimulatedMoving(true)
                    resetNotice()
                }
                httpPoll("[]")
                httpPoll(JSONArray().put(event).toString())
                runOnMainSync {
                    check(current()?.optString("id") == event.getString("id"))
                    render.invoke(activity)
                }
                waitForIdleSync()
                lateinit var graphic: View
                lateinit var targets: List<View>
                runOnMainSync {
                    val shown = nodes()
                    check(title in labels() && message in labels())
                    check(shown.none { it is Button || it is ScrollView || it.isClickable }) { "Driving event contains controls" }
                    graphic = shown.single { it.javaClass.simpleName == "DriverEventGraphic" }
                    check(graphic.width > 0 && graphic.height > 0)
                    targets = listOf(
                        shown.filterIsInstance<TextView>().single { it.text.toString() == title },
                        graphic,
                        shown.filterIsInstance<TextView>().single { it.text.toString() == message })
                    for (view in shown.filterIsInstance<TextView>()) view.layout?.let { layout ->
                        for (line in 0 until layout.lineCount) {
                            check(layout.getEllipsisCount(line) == 0)
                            check(layout.getLineMax(line) <= view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 2)
                        }
                    }
                }
                var accepted: Bitmap? = null
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<Triple<Bitmap, Int, Int>>()
                    runOnMainSync {
                        for (view in targets) {
                            val at = IntArray(2)
                            view.getLocationOnScreen(at)
                            val anchor = Bitmap.createBitmap(
                                view.width,
                                view.height,
                                Bitmap.Config.ARGB_8888
                            )
                            view.draw(Canvas(anchor))
                            anchors.add(Triple(anchor, at[0], at[1]))
                        }
                    }
                    val frame = checkNotNull(uiAutomation.takeScreenshot())
                    val presented = try {
                        anchors.all { (anchor, left, top) ->
                            var n = 0
                            var matches = 0
                            for (y in 0 until anchor.height) for (x in 0 until anchor.width) {
                                val pixel = anchor.getPixel(x, y)
                                if (Color.alpha(pixel) < 245) continue
                                n++
                                if (left + x !in 0 until frame.width || top + y !in 0 until frame.height) continue
                                val shown = frame.getPixel(left + x, top + y)
                                if (kotlin.math.abs(Color.red(pixel) - Color.red(shown)) < 12 && kotlin.math.abs(
                                        Color.green(pixel) - Color.green(shown)
                                    ) < 12 && kotlin.math.abs(Color.blue(pixel) - Color.blue(shown)) < 12
                                ) matches++
                            }
                            n > 30 && matches >= n * .97
                        }
                    } finally {
                        anchors.forEach { it.first.recycle() }
                    }
                    if (presented && SystemClock.elapsedRealtime() <= deadline) {
                        accepted = frame
                        break
                    }
                    frame.recycle()
                    Thread.sleep(80)
                }
                val frame =
                    checkNotNull(accepted) { "Event title, action and graphic pixels not presented within five seconds: $kind" }
                val filename = "driver-event-main-$kind-v295.png"
                File(targetContext.filesDir, filename).outputStream()
                    .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
                httpPoll(
                    JSONArray().put(JSONObject(event.toString()).put("evidence_valid", false))
                        .toString()
                )
                runOnMainSync {
                    check(current() == null)
                    check(nodes().none { it.javaClass.simpleName == "DriverEventGraphic" })
                }
                runOnMainSync { resetNotice() }
                httpPoll("[]")
                httpPoll(JSONArray().put(event).toString())
                runOnMainSync { check(current() != null) }
                httpPoll("[]")
                runOnMainSync {
                    check(current() == null)
                    check(nodes().none { it.javaClass.simpleName == "DriverEventGraphic" })
                }
                rows.put(
                    JSONObject().put("http_baseline_event_invalid_missing", true).put("kind", kind)
                        .put("event_id", event.getString("id")).put("title", title)
                        .put("message", message).put("text_pixels_verified", true)
                        .put("graphic_pixels_verified", true).put("controls", 0)
                        .put("screenshot", filename)
                )
            }
            val event = weatherCases.first().second
            runOnMainSync {
                state.useSimulation(false)
                for (gear in listOf("GEAR_REVERSE", "GEAR_NEUTRAL", "GEAR_UNKNOWN")) {
                    val now = SystemClock.elapsedRealtime()
                    seed(event, now)
                    state.observe(gear, now)
                    check(current(now) == null) { "Event visible in $gear" }
                    render.invoke(activity)
                    check(nodes().none { it.javaClass.simpleName == "DriverEventGraphic" })
                }
                val now = SystemClock.elapsedRealtime()
                seed(event, now)
                state.observe("GEAR_DRIVE", now - 6000)
                check(current(now) == null)
                state.useSimulation(true)
                state.setSimulatedMoving(true)
                seed(event, SystemClock.elapsedRealtime())
                check(current() != null)
                field("foreground").setBoolean(activity, false)
                check(current() == null)
                field("foreground").setBoolean(activity, true)
                // An absent monitor is denied; never fabricate an OEM permission or mutate its actual restrictions.
                val monitor = field("uxMonitor").get(activity)
                try {
                    field("uxMonitor").set(activity, null)
                    check(current() == null)
                } finally {
                    field("uxMonitor").set(activity, monitor)
                }
                notice.invalidate()
                check(current() == null)
                val stale = SystemClock.elapsedRealtime()
                seed(event, stale)
                check(current(stale + 10000) == null)
                // Reset the test clock before rendering at actual elapsed time.
                resetNotice()
                render.invoke(activity)
                check(nodes().none { it.javaClass.simpleName == "DriverEventGraphic" })
                seed(event, SystemClock.elapsedRealtime())
                render.invoke(activity)
                check(current() != null)
                callActivityOnPause(activity)
                check(current() == null)
                check(field("driverBriefHost").get(activity) == null)
                check(nodes().none { it.javaClass.simpleName == "DriverEventGraphic" })
            }
            val report = JSONObject().put("rows", rows)
                .put("reverse_neutral_unknown_and_stale_gear_hidden", true)
                .put("foreground_and_missing_oem_monitor_hidden", true)
                .put("notice_invalidated_and_expired", true).put("pause_clears_host", true)
                .put("actual_oem_allowed_observed", true)
                .put("actual_oem_restricted_state_exercised", false)
                .put(
                    "scope",
                    "Authored fixture HTTP responses from an in-app loopback server through actual Main pollComparisons, with debug driving state; additional state method boundaries and native title, message and graphic pixels. No real backend ingestion, sensor inference, taps, OEM permission override or physical driving."
                )
            File(
                targetContext.filesDir,
                "driver-event-main-v295.json"
            ).writeText(report.toString(2))
            result.putString(
                "stream",
                "PASS: eight synthetic driving event summaries and native titles/messages/graphics, no controls, gear/foreground/expiry boundaries. Authored loopback HTTP input and actual OEM allowance observed; no live driving or real backend claim.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            uiAutomation.takeScreenshot()?.let { frame ->
                File(targetContext.filesDir, "driver-event-main-failed-v295.png").outputStream()
                    .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
            }
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
