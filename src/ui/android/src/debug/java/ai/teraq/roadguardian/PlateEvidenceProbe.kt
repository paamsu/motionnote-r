package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Real Main renderer with stored synthetic event injection; not HTTP or human taps. */
class PlateEvidenceProbe : Instrumentation() {
    override fun onCreate(a: Bundle?) {
        super.onCreate(a)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        try {
            val event = JSONObject(File(targetContext.filesDir, "plate-event.json").readText())
            check(PlateAnalysis.checked(event) != null)
            val verified = EvidencePackageVerifier.verify(
                File(targetContext.filesDir, "plate-evidence.zip"),
                event.getString("id")
            )
            val bad = JSONObject(event.toString())
            bad.getJSONObject("plate_analysis").put("input_photo_sha256", "0".repeat(64))
            check(PlateAnalysis.checked(bad) == null)
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            fun texts(): List<TextView> {
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

            fun capture(name: String) {
                Thread.sleep(300)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, name).outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            waitFor { !field("busy").getBoolean(activity) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false }
            for (light in listOf(true, false)) {
                runOnMainSync {
                    val handler = field("timer").get(activity) as Handler
                    for (n in listOf(
                        "feedPoll",
                        "drivingWatch"
                    )) handler.removeCallbacks(field(n).get(activity) as Runnable)
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
                waitFor { texts().any { it.text.toString() == "상세 기록 펼치기" } }
                capture("plate-${if (light) "day" else "night"}-main.png")
                runOnMainSync {
                    texts().single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                }
                waitFor { texts().any { it.text.toString() == PlateAnalysis.detail(event) && it.height > 0 } }
                runOnMainSync {
                    texts().last { it.text.toString() == PlateAnalysis.detail(event) }.let {
                        it.requestRectangleOnScreen(
                            android.graphics.Rect(
                                0,
                                0,
                                it.width,
                                it.height + 120
                            ), true
                        )
                    }
                }
                capture("plate-${if (light) "day" else "night"}-detail.png")
            }
            File(
                targetContext.filesDir,
                "plate-native-result.json"
            ).writeText(
                JSONObject().put("zip_files", verified.files).put("zip_verified", true)
                    .put("photo_rebinding_rejected", true).put(
                    "scope",
                    "Programmatic Main state injection/disclosure; no HTTP fetch, human taps or physical vehicle."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: native archive verification, photo rebinding rejection, Main day/night captures.\n"
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
