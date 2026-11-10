package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

/** Text callback injection into the real handler. No microphone access or STT claim. */
class VoiceHandlerProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val out = Bundle()
        var activity: MainActivity? = null
        try {
            activity = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = activity
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            val render =
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            val handle =
                MainActivity::class.java.getDeclaredMethod("handleVoiceResult", String::class.java)
                    .apply { isAccessible = true }
            val allowed = MainActivity::class.java.getDeclaredMethod("voiceAllowed")
                .apply { isAccessible = true }

            fun waitFor(test: () -> Boolean) {
                repeat(300) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("voice state timeout")
            }

            fun rows() = JSONArray(BackendClient().request("/api/events"))
            fun inject(text: String) {
                runOnMainSync {
                    field("voiceListening").setBoolean(a, true)
                    handle.invoke(a, text)
                }
            }

            fun capture(name: String) {
                waitForIdleSync()
                Thread.sleep(500)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "voice-handler-$name.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            waitFor { !field("busy").getBoolean(a) }
            runOnMainSync {
                field("selected").set(a, "차량")
                render.invoke(a)
            }
            waitFor { allowed.invoke(a) == true }
            val before = rows().length()
            check(before == 0) { "isolated empty store required" }
            capture("entry")
            inject("진단하지 마")
            check(rows().length() == before)
            runOnMainSync { check(field("voiceMessage").get(a) == "진단 요청 취소") }
            inject("진단 취소")
            check(rows().length() == before)
            // A late delivery after cancel must not dispatch, even with otherwise valid text.
            runOnMainSync { handle.invoke(a, "차량이 이상해 진단해") }
            check(rows().length() == before)
            inject("차량이 이상해 진단해")
            waitFor {
                !field("busy").getBoolean(a) && (field("opened").get(a) as? JSONObject)?.optJSONObject(
                    "assessment"
                )?.optString("kind") == "diagnosis"
            }
            check(rows().length() == before + 1)
            capture("diagnosis")
            runOnMainSync { handle.invoke(a, "차량이 이상해 진단해") }
            check(rows().length() == before + 1)
            // Current-tab and app driving gates remain active for injected final text.
            runOnMainSync {
                field("selected").set(a, "기록")
                field("voiceListening").setBoolean(a, true)
                handle.invoke(a, "차량 진단")
            }
            check(rows().length() == before + 1)
            runOnMainSync {
                field("selected").set(a, "차량")
                val state = field("drivingState").get(a) as DrivingState
                state.useSimulation(true)
                state.setSimulatedMoving(true)
                field("voiceListening").setBoolean(a, true)
                handle.invoke(a, "차량 진단")
                state.useSimulation(false)
            }
            check(rows().length() == before + 1)
            runOnMainSync {
                callActivityOnPause(a)
                field("voiceListening").setBoolean(a, true)
                handle.invoke(a, "차량 진단")
            }
            check(rows().length() == before + 1)
            File(
                targetContext.filesDir,
                "voice-handler-result.json"
            ).writeText(
                JSONObject().put("passed", true).put("input_source", "text_callback_injection")
                    .put("actual_stt", false).put("microphone_opened", false)
                    .put("created_diagnosis_events", 1)
                    .put("cancel_and_late_callback_rejected", true)
                    .put("tab_driving_background_rejected", true).put("actual_vhal_changed", false)
                    .toString(2)
            )
            out.putString("result", "PASS text callback to one diagnosis; no STT")
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("error", t.stackTraceToString())
            finish(Activity.RESULT_CANCELED, out)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
