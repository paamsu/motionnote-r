package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Invokes only synthetic request controls; never a telephone intent. */
class HelpRequestFlowProbe : Instrumentation() {
    private var retryFault = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        retryFault = arguments?.getString("retryFault") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9465")
            activity = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = activity
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun method(name: String) =
                MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }

            fun labels(): List<TextView> {
                val out = mutableListOf<TextView>()
                fun visit(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return out
            }

            fun await(label: String, condition: () -> Boolean) {
                val until = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < until) {
                    var ok = false
                    runOnMainSync { ok = condition() }
                    if (ok) return
                    Thread.sleep(60)
                }
                error("Timeout $label")
            }

            fun click(text: String) {
                runOnMainSync {
                    check(labels().single { it.text.toString() == text }.performClick())
                }
            }

            fun shown(text: String) = labels().any { it.text.toString() == text }
            fun frame(name: String, text: String) {
                await(name) { shown(text) }
                waitForIdleSync()
                Thread.sleep(350)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "help-flow-$name.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }

            fun ids() = JSONArray(client.request("/api/events")).let { list ->
                (0 until list.length()).map {
                    list.getJSONObject(it).getString("id")
                }.toSet()
            }

            fun stopPoll() {
                val handler = field("timer").get(a) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) handler.removeCallbacks(field(name).get(a) as Runnable)
            }
            await("idle") { !field("busy").getBoolean(a) && (field("uxMonitor").get(a) as? VehicleUxMonitor)?.restricted == false }
            runOnMainSync {
                stopPoll()
                field("client").set(a, client);
                (field("drivingState").get(a) as DrivingState).apply {
                    useSimulation(true)
                    setSimulatedMoving(false)
                }
                field("opened").set(a, null)
                field("helpRequested").setBoolean(a, true)
                field("helpTarget").set(a, null)
                method("render").invoke(a)
            }
            val original = ids()
            frame("select", "119 구조·구급")
            for (number in listOf("112", "119")) {
                click(if (number == "119") "119 구조·구급" else "112 경찰")
                frame("confirm$number", "$number 모의 요청을 보낼까요?")
                check(ids() == original)
                click("취소")
                frame("cancel$number", "119 구조·구급")
                check(ids() == original)
            }
            val results = JSONArray()
            for (number in listOf("112", "119")) {
                runOnMainSync {
                    stopPoll()
                    field("opened").set(a, null)
                    field("helpRequested").setBoolean(a, true)
                    field("helpTarget").set(a, null)
                    method("render").invoke(a)
                }
                val before = ids()
                click(if (number == "119") "119 구조·구급" else "112 경찰")
                runOnMainSync {
                    val button = labels().single { it.text.toString() == "모의 요청 보내기" }
                    check(button.performClick())
                    button.performClick()
                }
                if (retryFault) {
                    await("$number response failure") {
                        !field("busy").getBoolean(a) && field("requestFailed").getBoolean(
                            a
                        )
                    }
                    val committed = ids() - before
                    check(committed.size == 1)
                    val pending = a.getSharedPreferences(
                        "pending_emergency",
                        android.content.Context.MODE_PRIVATE
                    )
                    check(
                        pending.getString("target", null) == number && !pending.getString(
                            "key",
                            null
                        ).isNullOrBlank()
                    )
                    frame("retry$number", "접수 결과 확인 필요")
                    runOnMainSync { check(!shown("취소") && shown("닫기")) }
                    click("같은 요청 다시 확인")
                }
                await("$number acknowledgement") {
                    !field("busy").getBoolean(a) && shown("$number 도움 요청") && shown(
                        "접수 완료"
                    )
                }
                val after = ids()
                val created = after - before
                check(created.size == 1) { "Duplicate request created ${created.size}" }
                val event = JSONObject(client.request("/api/events/${created.single()}"))
                check(event.getJSONObject("assessment").getString("recipient") == "$number-mock")
                check(event.getJSONArray("dispatches").length() == 1)
                frame("result$number", "$number 도움 요청")
                results.put(
                    JSONObject().put("target", number).put("event_id", event.getString("id"))
                        .put("response_failure_retry", retryFault).put("dispatch_count", 1)
                        .put("double_click_new_events", 1)
                )
            }
            runOnMainSync {
                stopPoll();
                (field("drivingState").get(a) as DrivingState).setSimulatedMoving(true)
                method("render").invoke(a)
            }
            frame("moving", "주행 시연")
            runOnMainSync {
                check(!shown("상세 기록 펼치기"))
                check(!shown("합성 입력 영상"))
            }
            val beforeBlocked = ids()
            runOnMainSync {
                val request =
                    MainActivity::class.java.getDeclaredMethod("requestHelp", String::class.java)
                        .apply { isAccessible = true }
                request.invoke(a, "112")
            }
            check(ids() == beforeBlocked)
            File(targetContext.filesDir, "help-flow-result.json").writeText(
                JSONObject().put(
                    "pass",
                    true
                ).put("cancel_creates_no_record", true).put("moving_request_blocked", true)
                    .put("moving_detail_hidden", true).put("cases", results).put(
                    "scope",
                    "Real Main control performClick and synthetic HTTP. No direct physical taps, real telephone, real vehicle motion or human readability. Cancel occurs before sending only."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: target selection, cancel without record, 119/112 send, double-click single record/dispatch, simulated moving detail and request guards.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
