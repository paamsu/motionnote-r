package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

class RoadUnclearProbe : Instrumentation() {
    override fun onCreate(a: Bundle?) {
        super.onCreate(a)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        try {
            val cases =
                JSONObject(File(targetContext.filesDir, "road-unclear-cases.json").readText())
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            fun labels(): List<TextView> {
                val out = mutableListOf<TextView>()
                fun visit(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(activity.window.decorView)
                return out
            }

            fun waitFor(f: () -> Boolean) {
                repeat(240) {
                    var ok = false
                    runOnMainSync { ok = f() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("UI timeout")
            }
            waitFor { !field("busy").getBoolean(activity) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false }
            for (light in listOf(true, false)) for (key in listOf("impact", "low")) {
                val event = cases.getJSONObject(key)
                check(ScenePhotoAnalysis.checked(event) != null)
                val expected = if (key == "impact") "도로 충격 기록" else "사진 판독 보류"
                check(EventBriefState.from(event).title == expected)
                if (key == "impact") {
                    val invalid = JSONObject(event.toString())
                    invalid.getJSONObject("assessment").getJSONObject("metrics")
                        .put("peak_vertical_g", "2.5")
                    check(EventBriefState.from(invalid).title == "사진 판독 보류")
                    for (status in listOf("dismissed", "input_review_required")) {
                        val copy = JSONObject(event.toString()).put("status", status)
                        check(EventBriefState.from(copy).title != expected)
                    }
                }
                runOnMainSync {
                    val h = field("timer").get(activity) as Handler
                    for (n in listOf("feedPoll", "drivingWatch")) h.removeCallbacks(
                        field(n).get(
                            activity
                        ) as Runnable
                    )
                    (field("drivingState").get(activity) as DrivingState).apply {
                        useSimulation(true)
                        setSimulatedMoving(false)
                    }
                    field("selected").set(activity, "기록")
                    field("opened").set(activity, event)
                    field("light").setBoolean(activity, light)
                    MainActivity::class.java.getDeclaredMethod("render")
                        .apply { isAccessible = true }.invoke(activity)
                }
                waitFor { labels().any { it.text.toString() == expected && it.height > 0 && !it.isLayoutRequested } }
                runOnMainSync {
                    check(labels().none { it.text.toString() == "포트홀 감지" })
                    val v = labels().first { it.text.toString() == expected }
                    check((0 until v.layout.lineCount).all { v.layout.getEllipsisCount(it) == 0 })
                }
                Thread.sleep(300)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(
                    targetContext.filesDir,
                    "road-unclear-$key-${if (light) "day" else "night"}.png"
                ).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
                EvidencePackageVerifier.verify(
                    File(
                        targetContext.filesDir,
                        "road-unclear-$key.zip"
                    ), event.getString("id")
                )
            }
            result.putString(
                "stream",
                "PASS: high/low unclear day/night Main and original archives; malformed peak/status controls. Saved events injected; no HTTP or physical vehicle.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { main?.finish() }
        }
    }
}
