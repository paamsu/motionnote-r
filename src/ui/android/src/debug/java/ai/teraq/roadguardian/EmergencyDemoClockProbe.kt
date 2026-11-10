package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Real elapsed Android timer and actual HTTP; controls use performClick. */
class EmergencyDemoClockProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: EmergencyDemoActivity? = null
        try {
            val rows = JSONArray()
            fun texts(): List<TextView> {
                val out = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(checkNotNull(activity).window.decorView)
                return out
            }

            fun waitFor(limit: Int = 200, condition: () -> Boolean) {
                repeat(limit) {
                    var yes = false
                    runOnMainSync { yes = condition() }
                    if (yes) return
                    Thread.sleep(100)
                }
                error("UI wait expired")
            }

            fun click(label: String) {
                runOnMainSync { texts().single { it.text.toString() == label }.performClick() }
            }

            fun shot(name: String) {
                Thread.sleep(250)
                val image = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "emergency-clock-$name.png").outputStream()
                    .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            for ((case, light) in listOf(
                "response" to true,
                "cancel" to false,
                "timeout" to false,
                "background" to true
            )) {
                activity = startActivitySync(
                    Intent(
                        targetContext,
                        EmergencyDemoActivity::class.java
                    ).putExtra("light", light).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ) as EmergencyDemoActivity
                val ux = EmergencyDemoActivity::class.java.getDeclaredField("ux")
                    .apply { isAccessible = true }
                waitFor { (ux.get(activity) as? VehicleUxMonitor)?.restricted == false }
                click("시연 시작")
                waitFor { texts().any { it.text.toString() == "응답을 기다립니다" } }
                if (case == "response") shot("day-waiting")
                if (case == "timeout") shot("night-waiting")
                if (case == "background") {
                    uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { p ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(p).readBytes()
                    }
                    Thread.sleep(2500)
                    val same = activity
                    targetContext.startActivity(
                        Intent(
                            targetContext,
                            EmergencyDemoActivity::class.java
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    waitFor { texts().any { it.text.toString() == "확인 중단" } }
                    check(same === activity)
                    shot("background-interrupted")
                    rows.put(JSONObject().put("case", case).put("state", "interrupted"))
                } else {
                    if (case != "timeout") {
                        Thread.sleep(1100)
                        click(if (case == "response") "응답하기" else "취소")
                    }
                    waitFor(450) { texts().any { it.text.toString() == "기록 확보 완료" } }
                    shot(case)
                    val field = EmergencyDemoActivity::class.java.getDeclaredField("eventId")
                        .apply { isAccessible = true }
                    var id = ""
                    runOnMainSync { id = field.get(activity) as String }
                    val event = JSONObject(BackendClient().request("/api/events/$id"))
                    val expected = when (case) {
                        "response" -> "responded"
                        "cancel" -> "cancelled"
                        else -> "timed_out"
                    }
                    check(
                        event.getJSONObject("assessment").getJSONObject("emergency_assessment")
                            .getString("status") == expected
                    )
                    check(event.getJSONArray("dispatches").length() == 0)
                    File(
                        targetContext.filesDir,
                        "emergency-clock-$case.json"
                    ).writeText(event.toString(2))
                    val zip = File(targetContext.filesDir, "emergency-clock-$case.zip")
                    zip.writeBytes(BackendClient().evidencePackage(id))
                    EvidencePackageVerifier.verify(zip, id)
                    rows.put(
                        JSONObject().put("case", case).put("event_id", id).put("status", expected)
                            .put("zip_verified", true)
                    )
                }
                runOnMainSync { activity?.finish() }
                Thread.sleep(250)
            }
            File(
                targetContext.filesDir,
                "emergency-clock-result.json"
            ).writeText(
                JSONObject().put("rows", rows).put(
                    "scope",
                    "Actual Android elapsed clock, UI performClick, HOME and resume, HTTP and ZIP; no human-state detection or real dispatch"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: real timer, response/cancel, background interruption, HTTP/ZIP\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
