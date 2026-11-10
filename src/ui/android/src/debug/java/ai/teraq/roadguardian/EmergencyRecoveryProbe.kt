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

class EmergencyRecoveryProbe : Instrumentation() {
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

            fun click(label: String) {
                runOnMainSync { texts().single { it.text.toString() == label }.performClick() }
            }

            fun shot(name: String) {
                Thread.sleep(200)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "emergency-recovery-$name.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            waitFor { (field("ux").get(activity) as? VehicleUxMonitor)?.restricted == false }
            val marker = File(targetContext.filesDir, "emergency-recovery-pending.json")
            if (phase == "offline") {
                click("시연 시작")
                Thread.sleep(1200)
                click("응답하기")
                waitFor { !field("saving").getBoolean(activity) && texts().any { it.text.toString() == "기록은 기기에 보관됨" } }
                var payload: JSONObject? = null
                runOnMainSync { payload = field("finalPayload").get(activity) as JSONObject }
                marker.writeText(checkNotNull(payload).toString(2))
                shot("offline")
            } else {
                require(phase in setOf("recover", "repeat"))
                val original = JSONObject(marker.readText())
                val key = original.getString("request_key")
                runOnMainSync {
                    check(field("key").get(activity) == key)
                    check((field("finalPayload").get(activity) as JSONObject).toString() == original.toString())
                    check(!field("saving").getBoolean(activity))
                }
                shot("$phase-restored")
                click("기록 보관 재시도")
                waitFor { texts().any { it.text.toString() == "기록 확보 완료" } }
                var id = ""
                runOnMainSync { id = field("eventId").get(activity) as String }
                val event = JSONObject(BackendClient().request("/api/events/$id"))
                check(event.getJSONArray("dispatches").length() == 0)
                val output = File(targetContext.filesDir, "emergency-recovery-result.json")
                if (phase == "repeat") check(JSONObject(output.readText()).getString("event_id") == id)
                output.writeText(
                    JSONObject().put("event_id", id).put("phase", phase).put("request_key", key)
                        .put("dispatches", 0).toString(2)
                )
                val zip = File(targetContext.filesDir, "emergency-recovery.zip")
                zip.writeBytes(BackendClient().evidencePackage(id))
                EvidencePackageVerifier.verify(zip, id)
                shot("$phase-saved")
                if (phase == "recover") {
                    // Preserve the ack elsewhere to simulate a lost acknowledgement, not raw-record loss.
                    val ack = File(targetContext.filesDir, "emergency-demo/$key.ack")
                    check(
                        ack.renameTo(
                            File(
                                targetContext.filesDir,
                                "emergency-recovery-preserved.ack"
                            )
                        )
                    )
                }
            }
            result.putString("stream", "PASS emergency recovery phase=$phase\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
