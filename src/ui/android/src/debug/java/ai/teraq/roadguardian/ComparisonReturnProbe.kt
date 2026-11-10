package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Actual return controller under failure, competing unread action and lifecycle changes. */
class ComparisonReturnProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            val id =
                JSONObject(BackendClient().request("/api/dev/road-native-fixture")).getString("repeat")
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = screen
            fun f(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            fun m(n: String) =
                MainActivity::class.java.getDeclaredMethod(n).apply { isAccessible = true }

            fun await(test: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 12000
                while (SystemClock.elapsedRealtime() < end) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(40)
                }
                error("return controller timeout")
            }

            fun labels(): List<String> {
                val out = mutableListOf<String>()
                fun visit(v: View) {
                    if (v is TextView && v.isShown) out.add(v.text.toString())
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return out
            }
            await {
                !f("busy").getBoolean(a) && !f("comparisonLoading").getBoolean(a) && (f("uxMonitor").get(
                    a
                ) as? VehicleUxMonitor)?.restricted == false
            }
            val state = f("drivingState").get(a) as DrivingState
            val notice = f("comparisonNotice").get(a) as ComparisonNoticeState
            fun stop() {
                (f("timer").get(a) as android.os.Handler).removeCallbacks(f("feedPoll").get(a) as Runnable)
            }
            runOnMainSync {
                stop()
                state.useSimulation(true)
                state.setSimulatedMoving(false)
                f("opened").set(a, null)
                f("selected").set(a, "기록")
                val now = SystemClock.elapsedRealtime()
                notice.invalidate()
                notice.accept(emptySet(), now)
                notice.accept(setOf(id), now)
                f("detailReturnId").set(a, "missing-v213")
                m("refreshReturnedDetail").invoke(a)
            }
            await { f("detailReturnFailed").getBoolean(a) }
            runOnMainSync {
                check(labels().none { it.contains("새 비교 자료") })
                m("openPendingComparison").invoke(a)
                check(f("opened").get(a) == null && notice.pendingCount(SystemClock.elapsedRealtime()) == 1)
                f("detailReturnId").set(a, id)
                m("refreshReturnedDetail").invoke(a)
                callActivityOnPause(a)
            }
            Thread.sleep(700)
            runOnMainSync {
                check(f("opened").get(a) == null)
                callActivityOnResume(a)
                stop()
                state.setSimulatedMoving(true)
                m("render").invoke(a)
                state.setSimulatedMoving(false)
                m("render").invoke(a)
            }
            Thread.sleep(700)
            runOnMainSync {
                check(f("opened").get(a) == null && f("detailReturnId").get(a) == id)
                m("refreshReturnedDetail").invoke(a)
            }
            await {
                f("detailReturnId").get(a) == null && (f("opened").get(a) as? JSONObject)?.optString(
                    "id"
                ) == id
            }
            File(
                targetContext.filesDir,
                "comparison-return-v213.json"
            ).writeText(
                JSONObject().put("failure_hides_pending_action", true)
                    .put("hidden_action_cannot_acknowledge", true)
                    .put("pause_discards_return", true).put("D_then_P_discards_old_response", true)
                    .put("retry_loads_current_record", true).put(
                    "scope",
                    "actual Main methods and HTTP with debug lifecycle/driving state; no taps"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: failed return cannot consume unread; pause and D/P invalidate old response; retry loads current detail.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
