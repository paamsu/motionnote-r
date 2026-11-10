package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Exercises delayed Main task completion with authored restriction/lifecycle states; no taps or OEM override. */
class MainRequestLifecycleProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            activity = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = activity
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            val render =
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            val task = MainActivity::class.java.getDeclaredMethod(
                "task",
                kotlin.jvm.functions.Function0::class.java
            ).apply { isAccessible = true }

            fun idle() {
                val end = System.currentTimeMillis() + 15000
                while (System.currentTimeMillis() < end) {
                    var busy = true
                    runOnMainSync { busy = field("busy").getBoolean(a) }
                    if (!busy) return
                    Thread.sleep(40)
                }
                error("Main task timed out")
            }

            fun texts(): List<String> {
                val out = mutableListOf<String>()
                fun visit(v: View) {
                    if (v is TextView) out.add(v.text.toString())
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return out
            }
            idle()
            // Pause real monitoring before isolated injected state checks. No vehicle property is changed.
            runOnMainSync {
                callActivityOnPause(a);
                (field("adapter").get(a) as PleosAdapter).close();
                (field("uxMonitor").get(a) as VehicleUxMonitor).close()
            }
            val rows = JSONArray()
            for (failed in listOf(false, true)) for (moving in listOf(
                false,
                true
            )) for (foreground in listOf(false, true)) {
                val release = CountDownLatch(1)
                val entered = CountDownLatch(1)
                var prior: View? = null
                runOnMainSync {
                    val state = field("drivingState").get(a) as DrivingState
                    state.useSimulation(true)
                    state.setSimulatedMoving(false)
                    field("foreground").setBoolean(a, true)
                    field("selected").set(a, "설정")
                    val work: () -> Unit = {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        if (failed) error("PRIVATE_RAW_EXCEPTION_v203")
                    }
                    task.invoke(a, work)
                    state.setSimulatedMoving(moving)
                    field("foreground").setBoolean(a, foreground)
                    render.invoke(a)
                    prior = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                }
                check(entered.await(5, TimeUnit.SECONDS))
                release.countDown()
                idle()
                runOnMainSync {
                    val shown = texts()
                    check(shown.none { it.contains("PRIVATE_RAW_EXCEPTION") || it.contains("다시 시도") })
                    // OEM monitor is deliberately unavailable, so both injected P/D remain restricted.
                    check(shown.none {
                        it == "처리 중…" || it == "자료를 불러오지 못했습니다" || it.contains("주간 화면") || it.contains(
                            "야간 화면"
                        )
                    })
                    if (!foreground) check(
                        prior === a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    ) { "Background completion replaced UI" }
                    check(field("requestFailed").getBoolean(a) == failed)
                    rows.put(
                        JSONObject().put("failed", failed).put("moving", moving)
                            .put("foreground", foreground).put("texts", JSONArray(shown))
                    )
                }
            }
            File(
                targetContext.filesDir,
                "main-request-lifecycle-v203.json"
            ).writeText(
                JSONObject().put("rows", rows).put(
                    "scope",
                    "8 injected Main completion states; OEM unavailable stays restricted; no actual driving or direct taps"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: 8 delayed Main completion states; restricted header and background UI preserved.\n"
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
