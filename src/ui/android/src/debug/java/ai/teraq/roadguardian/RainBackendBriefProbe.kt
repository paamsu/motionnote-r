package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Actual native render, test-only font configuration and simulated motion. */
class RainBackendBriefProbe : Instrumentation() {
    override fun onCreate(a: Bundle?) {
        super.onCreate(a)
        start()
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        var previous: Configuration? = null
        try {
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            previous = Configuration(activity.resources.configuration)
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            fun labels(): List<TextView> {
                val out = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(activity.window.decorView)
                return out
            }

            fun waitFor(test: () -> Boolean) {
                repeat(200) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("UI timeout")
            }

            fun render(event: JSONObject, light: Boolean, moving: Boolean = false) {
                runOnMainSync {
                    val handler = field("timer").get(activity) as Handler
                    for (n in listOf(
                        "feedPoll",
                        "drivingWatch"
                    )) handler.removeCallbacks(field(n).get(activity) as Runnable)
                    (field("drivingState").get(activity) as DrivingState).apply {
                        useSimulation(true)
                        setSimulatedMoving(moving)
                    }
                    field("selected").set(activity, "기록")
                    field("opened").set(activity, event)
                    field("light").setBoolean(activity, light)
                    MainActivity::class.java.getDeclaredMethod("render")
                        .apply { isAccessible = true }.invoke(activity)
                }
            }

            fun capture(name: String) {
                Thread.sleep(300)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, name).outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }

            fun fullyVisible(text: String) {
                runOnMainSync {
                    val view = labels().first { it.text.toString() == text }
                    val rect = Rect()
                    check(view.getGlobalVisibleRect(rect) && rect.height() == view.height && rect.width() == view.width)
                    val layout = checkNotNull(view.layout)
                    check((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                }
            }
            waitFor { !field("busy").getBoolean(activity) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false }
            runOnMainSync {
                val config = Configuration(activity.resources.configuration)
                config.fontScale = 1.5f
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            }
            val client = BackendClient("http://127.0.0.1:9498")
            fun poll() {
                runOnMainSync {
                    field("client").set(activity, client)
                    MainActivity::class.java.getDeclaredMethod("pollComparisons")
                        .apply { isAccessible = true }.invoke(activity)
                }
                waitFor { !field("comparisonLoading").getBoolean(activity) }
            }

            val rows = JSONArray()
            for (light in listOf(true, false)) for (case in listOf(
                "wipe",
                "illumination",
                "accumulate"
            )) {
                runOnMainSync { field("driverEventNotice").set(activity, DriverEventNoticeState()) }
                poll()
                val e = JSONObject(
                    client.request(
                        "/api/dev/weather-create",
                        JSONObject().put("case", case)
                            .put("request_key", java.util.UUID.randomUUID().toString())
                    )
                )
                check(e.getBoolean("evidence_valid"))
                val expected =
                    if (case == "wipe") "워셔 사용 구간" else if (case == "accumulate") "전방 시야 저하" else "시야 변화 확인 중"
                check(EventBriefState.from(e).title == expected)
                if (case == "accumulate") {
                    check(EventBriefState.authoredRainCandidate(e))
                    for (mutation in listOf("washer", "camera", "condition", "evidence")) {
                        val bad = JSONObject(e.toString())
                        when (mutation) {
                            "washer" -> bad.getJSONObject("assessment")
                                .getJSONObject("weather_timeline_assessment")
                                .put("status", "washer_suppressed")

                            "camera" -> bad.getJSONObject("assessment")
                                .getJSONObject("weather_assessment")
                                .put("visual_analysis_performed", true)

                            "condition" -> bad.getJSONObject("assessment")
                                .getJSONObject("weather_assessment").put("condition", "unknown")

                            else -> bad.put("evidence_valid", false)
                        }
                        check(!EventBriefState.authoredRainCandidate(bad))
                        val control = DriverEventNoticeState()
                        control.accept(JSONArray(), 0)
                        control.accept(JSONArray().put(bad), 1)
                        check(control.current(1) == null)
                    }
                }
                render(e, light, true)
                poll()
                waitFor { labels().any { it.text.toString() == expected && it.height > 0 && !it.isLayoutRequested } }
                fullyVisible(expected)
                if (case == "accumulate") fullyVisible("속도를 줄이고 거리를 확보하세요")
                runOnMainSync { check(labels().none { it.text.toString().contains("상세 기록") }) }
                val theme = if (light) "day" else "night"
                capture("rain-backend-$case-$theme.png")
                val fetched = JSONObject(client.request("/api/events/" + e.getString("id")))
                check(fetched.getString("hash") == e.getString("hash"))
                rows.put(
                    JSONObject().put("case", case).put("light", light).put("id", e.getString("id"))
                        .put("hash", e.getString("hash")).put("title", expected)
                )
            }
            File(
                targetContext.filesDir,
                "rain-backend-results.json"
            ).writeText(
                JSONObject().put("pass", true).put("rows", rows).put(
                    "scope",
                    "Isolated Python Store/backend GET with test-only authored creation endpoint; actual Main HTTP poll; simulated driving, no real sensors."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: six stored backend events with four rain rejection controls per theme through actual Main HTTP poll.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            runCatching {
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "rain-backend-failure.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                main?.let { a ->
                    previous?.let {
                        a.resources.updateConfiguration(
                            it,
                            a.resources.displayMetrics
                        )
                    }
                    a.finish()
                }
            }
        }
    }
}
