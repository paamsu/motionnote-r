package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.UUID

class EmergencyDeathProbe : Instrumentation() {
    private var phase = ""
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        phase = checkNotNull(args?.getString("phase"))
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: EmergencyDemoActivity? = null
        try {
            if (phase == "corrupt") {
                val key = UUID.randomUUID().toString()
                val file = File(targetContext.filesDir, "emergency-demo/$key.json")
                file.writeText("{\"state\":\"responded\",\"payload\":")
                File(targetContext.filesDir, "emergency-death-corrupt-key.txt").writeText(key)
            }
            activity = startActivitySync(
                Intent(
                    targetContext,
                    EmergencyDemoActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as EmergencyDemoActivity
            fun field(name: String) = EmergencyDemoActivity::class.java.getDeclaredField(name)
                .apply { isAccessible = true }

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
                    var yes = false
                    runOnMainSync { yes = test() }
                    if (yes) return
                    Thread.sleep(100)
                }
                error("UI timeout")
            }

            fun shot(name: String) {
                Thread.sleep(200)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "emergency-death-$name.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            waitFor { (field("ux").get(activity) as? VehicleUxMonitor)?.restricted == false }
            if (phase == "start") {
                runOnMainSync { texts().single { it.text.toString() == "시연 시작" }.performClick() }
                Thread.sleep(1200)
                waitFor { field("state").get(activity) == "waiting" }
                var key = ""
                runOnMainSync { key = field("key").get(activity) as String }
                shot("waiting")
                File(
                    targetContext.filesDir,
                    "emergency-death-ready.json"
                ).writeText(
                    JSONObject().put("key", key).put("pid", android.os.Process.myPid()).toString()
                )
                Thread.sleep(60000)
                error("Host failed to kill waiting process")
            } else {
                require(phase in setOf("restore", "corrupt"))
                val expected = if (phase == "restore") "interrupted" else "record_unavailable"
                waitFor { field("state").get(activity) == expected }
                runOnMainSync {
                    check(field("finalPayload").get(activity) == null)
                    check(!field("saving").getBoolean(activity))
                    check(texts().none { it.text.toString() == "기록 보관 재시도" })
                }
                shot(phase)
                File(
                    targetContext.filesDir,
                    "emergency-death-$phase.json"
                ).writeText(
                    JSONObject().put("state", expected).put("upload_started", false).toString()
                )
                result.putString("stream", "PASS emergency process phase=$phase\n")
                finish(Activity.RESULT_OK, result)
            }
        } catch (e: Throwable) {
            result.putString("stream", "FAIL ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
