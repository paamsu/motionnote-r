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
class PlateBoundaryUiProbe : Instrumentation() {
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
                JSONObject(File(targetContext.filesDir, "plate-boundary-cases.json").readText())
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
            for (light in listOf(true, false)) for (name in listOf(
                "matched",
                "inconclusive",
                "corrupt"
            )) {
                val event = cases.getJSONObject(name)
                val expected = when (name) {
                    "matched" -> "번호 문자 2 건 일치 · 검토 필요"
                    "inconclusive" -> "번호판 판독 보류"
                    else -> "번호판 판독 근거 확인 필요"
                }
                check(PlateAnalysis.detail(event) == expected)
                render(event, light)
                waitFor { labels().any { it.text.toString() == expected && it.height > 0 && !it.isLayoutRequested } }
                fullyVisible("번호판 판독 기록")
                fullyVisible(expected)
                fullyVisible("상세 기록 펼치기")
                val theme = if (light) "day" else "night"
                capture("plate-boundary-$name-$theme.png")
                if (name == "inconclusive") {
                    runOnMainSync {
                        labels().single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                    }
                    waitFor { labels().count { it.text.toString() == expected } == 2 }
                    runOnMainSync {
                        labels().last { it.text.toString() == expected }.let {
                            it.requestRectangleOnScreen(
                                Rect(0, 0, it.width, it.height + 120),
                                true
                            )
                        }
                    }
                    runOnMainSync {
                        check(labels().none { it.text.toString().contains("DEMO-") })
                        check(labels().any { it.text.toString() == "시연 설정 펼치기" })
                    }
                    capture("plate-boundary-detail-$theme.png")
                    render(event, light, true)
                    waitFor { labels().none { it.text.toString().contains("상세 기록") } }
                    runOnMainSync {
                        check(labels().none {
                            it.text.toString().contains("DEMO-") || it.text.toString() == expected
                        })
                    }
                    capture("plate-boundary-moving-$theme.png")
                    render(event, light, false)
                    waitFor { labels().any { it.text.toString() == "상세 기록 펼치기" } }
                }
                rows.put(
                    JSONObject().put("case", name).put("light", light).put("expected", expected)
                        .put("summary_bounds_checked", true)
                )
            }
            File(
                targetContext.filesDir,
                "plate-boundary-results.json"
            ).writeText(
                JSONObject().put("pass", true).put("font_scale", 1.5).put("cases", rows)
                    .put("simulated_driving_hides_detail", true).put(
                    "scope",
                    "Main injected saved events, programmatic disclosure, process resource font override; not HTTP, OS font setting, human taps or physical vehicle."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: six enlarged summaries and two disclosure/motion transitions.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            runCatching {
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "plate-boundary-failure.png").outputStream()
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
