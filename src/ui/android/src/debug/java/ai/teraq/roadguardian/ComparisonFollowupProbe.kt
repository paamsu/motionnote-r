package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Actual Main routes and HTTP; authored readiness timing, no direct control taps. */
class ComparisonFollowupProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            val rows = JSONArray(BackendClient().request("/api/events"))
            val event = (0 until rows.length()).map { rows.getJSONObject(it) }.first {
                it.optString("source") == "synthetic" && EvidenceScope.canNotifyComparison(it)
            }
            val id = event.getString("id")
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = screen
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            fun method(n: String) =
                MainActivity::class.java.getDeclaredMethod(n).apply { isAccessible = true }

            fun await(test: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < end) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(40)
                }
                error("follow-up timeout")
            }

            fun views(): List<View> {
                val out = mutableListOf<View>()
                fun visit(v: View) {
                    if (v.isShown) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return out
            }

            fun labels() = views().filterIsInstance<TextView>().map { it.text.toString() }
            await {
                !field("busy").getBoolean(a) && !field("comparisonLoading").getBoolean(a) && (field(
                    "uxMonitor"
                ).get(a) as? VehicleUxMonitor)?.restricted == false
            }
            val state = field("drivingState").get(a) as DrivingState
            val notice = field("comparisonNotice").get(a) as ComparisonNoticeState
            val render = method("render")
            runOnMainSync {
                (field("timer").get(a) as android.os.Handler).removeCallbacks(
                    field("feedPoll").get(
                        a
                    ) as Runnable
                )
                state.useSimulation(true)
                state.setSimulatedMoving(false)
                field("selected").set(a, "기록")
                field("opened").set(a, null)
                field("events").set(a, JSONArray().put(event))
                field("light").setBoolean(a, true)
                val now = SystemClock.elapsedRealtime()
                notice.invalidate()
                notice.accept(emptySet(), now - 31000)
                notice.accept(setOf(id), now - 30000)
                notice.accept(setOf(id), now)
                check(!notice.active(now) && notice.pendingCount(now) == 1)
                render.invoke(a)
                check(labels().contains("새 비교 자료 1건 · 열기"))
                val action = views().filterIsInstance<Button>()
                    .first { it.text.toString() == "새 비교 자료 1건 · 열기" }
                check(action.typeface == PleosTypography.face())
                check(action.currentTextColor == PleosPalette.ink(true))
            }
            waitForIdleSync()
            Thread.sleep(250)
            uiAutomation.takeScreenshot()?.let { b ->
                File(targetContext.filesDir, "comparison-followup-v212.png").outputStream()
                    .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            runOnMainSync {
                state.setSimulatedMoving(true)
                render.invoke(a)
                check(labels().none { it.contains("새 비교 자료") })
                check(views().filterIsInstance<Button>().isEmpty())
                method("openPendingComparison").invoke(a)
                check(field("opened").get(a) == null)
                state.setSimulatedMoving(false)
                render.invoke(a)
                check(labels().contains("새 비교 자료 1건 · 열기"))
                method("openPendingComparison").invoke(a)
            }
            await { !field("busy").getBoolean(a) }
            runOnMainSync {
                check((field("opened").get(a) as? JSONObject)?.optString("id") == id)
                check(notice.pendingCount(SystemClock.elapsedRealtime()) == 0)
                field("opened").set(a, null)
                val now = SystemClock.elapsedRealtime()
                notice.accept(setOf(id, "missing-v212"), now)
                render.invoke(a)
                method("openPendingComparison").invoke(a)
            }
            await { !field("busy").getBoolean(a) }
            runOnMainSync {
                check(field("requestFailed").getBoolean(a))
                check(field("opened").get(a) == null)
                check(notice.pendingCount(SystemClock.elapsedRealtime()) == 1)
                method("openPendingComparison").invoke(a)
                callActivityOnPause(a)
            }
            Thread.sleep(700)
            runOnMainSync {
                check(field("opened").get(a) == null)
                check(notice.pendingCount(SystemClock.elapsedRealtime()) == 0)
            }
            File(
                targetContext.filesDir,
                "comparison-followup-v212.json"
            ).writeText(
                JSONObject().put("event_id", id).put("survives_notice_timeout", true)
                    .put("hidden_and_open_blocked_in_D", true).put("zero_driving_buttons", true)
                    .put("returns_in_P", true).put("actual_http_detail_open", true)
                    .put("acknowledged_after_success", true).put("failure_retains_pending", true)
                    .put("pause_discards_response", true).put(
                    "scope",
                    "actual Main and local HTTP, authored readiness timing and debug P/D; no UI taps or human comprehension measurement"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: expired notice retains parked follow-up; D hides/blocks; P restores; HTTP opens same record; failure retains unread; pause discards response.\n"
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
