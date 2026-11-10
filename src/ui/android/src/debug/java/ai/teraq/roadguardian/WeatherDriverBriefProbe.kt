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
class WeatherDriverBriefProbe : Instrumentation() {
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
            val cases =
                JSONObject(File(targetContext.filesDir, "weather-driver-cases.json").readText())
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
            val rows = JSONArray()
            val base = cases.getJSONObject("valid")
            fun event(state: String) = JSONObject(base.toString()).apply {
                put("id", java.util.UUID.randomUUID().toString())
                getJSONObject("assessment").getJSONObject("weather_timeline_assessment")
                    .put("status", state)
            }

            val now = android.os.SystemClock.elapsedRealtime()
            val policy = DriverEventNoticeState()
            val old = event("washer_suppressed")
            policy.accept(JSONArray().put(old), now)
            check(policy.current(now) == null)
            val fresh = event("collecting")
            policy.accept(JSONArray().put(old).put(fresh), now + 1)
            check(policy.current(now + 1)?.getString("id") == fresh.getString("id"))
            check(policy.current(now + 10001) == null)
            policy.accept(JSONArray().put(old).put(fresh), now + 10002)
            check(policy.current(now + 10002) == null)
            val invalid = event("recovered").put("evidence_valid", false)
            policy.accept(JSONArray().put(invalid), now + 10003)
            check(policy.current(now + 10003) == null)
            val duplicate = event("collecting")
            policy.accept(JSONArray().put(duplicate).put(duplicate), now + 10004)
            check(policy.current(now + 10004) == null)
            val malformed = event("recovered").apply {
                getJSONObject("assessment").getJSONObject("weather_timeline_assessment")
                    .put("camera_analysis_performed", true)
            }
            policy.accept(JSONArray().put(malformed), now + 10005)
            check(policy.current(now + 10005) == null)
            for (light in listOf(true, false)) for (state in listOf(
                "collecting",
                "washer_suppressed",
                "recovered"
            )) {
                val e = event(state)
                val expected = EventBriefState.from(e).title
                val expectedGraphic = when (state) {
                    "collecting" -> "visibility_change"
                    "washer_suppressed" -> "washer"
                    else -> "record_saved"
                }
                check(DriverBriefPanel.verifiedBrief(e)?.first == expectedGraphic) { "Weather graphic misrepresents $state" }
                runOnMainSync {
                    val notice = field("driverEventNotice").get(activity) as DriverEventNoticeState
                    notice.invalidate()
                    val t = android.os.SystemClock.elapsedRealtime()
                    notice.accept(JSONArray(), t)
                    notice.accept(JSONArray().put(e), t)
                }
                render(e, light, true)
                waitFor { labels().any { it.text.toString() == expected && it.height > 0 && !it.isLayoutRequested } }
                fullyVisible(expected)
                val message = when (state) {
                    "collecting" -> "기록 보관"
                    "washer_suppressed" -> "강우 판단 보류"
                    else -> "시야를 확인하세요"
                }
                fullyVisible(message)
                runOnMainSync {
                    check(labels().none {
                        it.text.toString().contains("상세 기록") || it.text.toString() == "합성 입력 영상"
                    })
                    fun checkControls(v: View) {
                        if (!v.isShown) return
                        check(!v.isClickable)
                        check(v !is android.widget.ScrollView)
                        if (v is ViewGroup) for (i in 0 until v.childCount) checkControls(
                            v.getChildAt(
                                i
                            )
                        )
                    }
                    checkControls(field("driverBriefHost").get(activity) as View)
                }
                val theme = if (light) "day" else "night"
                capture("weather-driver-$state-$theme.png")
                rows.put(
                    JSONObject().put("state", state).put("light", light).put("title", expected)
                        .put("controls", 0).put("graphic", expectedGraphic)
                )
            }
            File(
                targetContext.filesDir,
                "weather-driver-results.json"
            ).writeText(
                JSONObject().put("pass", true).put("font_scale", 1.5).put("cases", rows)
                    .put("policy_controls", 7).put(
                    "scope",
                    "Actual Main; injected new-event notice; simulated drive; app resource font override; no HTTP or physical vehicle."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: six weather driver briefs and freshness/invalid/duplicate controls.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            runCatching {
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "weather-driver-failure.png").outputStream()
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
