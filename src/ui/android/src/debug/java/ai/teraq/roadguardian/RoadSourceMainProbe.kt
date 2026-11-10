package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.json.JSONObject

/** Main method routes with actual local HTTP; no synthetic control taps. */
class RoadSourceMainProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9463")
            val ids = JSONObject(client.request("/api/dev/road-native-fixture"))
            val parent = JSONObject(client.request("/api/events/${ids.getString("clear")}"))
            check(
                RoadObservationSource.load(
                    client,
                    parent
                ).second.getString("id") == parent.getJSONObject("road_followup_case")
                    .getString("followup_id")
            )
            val invalid = JSONObject(client.request("/api/events/${ids.getString("invalid")}"))
            check(RoadObservationSource.reference(invalid) == null)
            val altered = JSONObject(parent.toString())
            altered.getJSONObject("road_followup_case").put("followup_sha256", "0".repeat(64))
            check(runCatching { RoadObservationSource.load(client, altered) }.isFailure)
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

            val render = method("render")
            val open =
                MainActivity::class.java.getDeclaredMethod("openRoadSource", JSONObject::class.java)
                    .apply { isAccessible = true }

            fun await(test: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < end) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(40)
                }
                error("source route timeout")
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
                !field("busy").getBoolean(a) && !field("comparisonLoading").getBoolean(a) && (field(
                    "uxMonitor"
                ).get(a) as? VehicleUxMonitor)?.restricted == false
            }
            runOnMainSync {
                (field("timer").get(a) as android.os.Handler).removeCallbacks(
                    field("feedPoll").get(
                        a
                    ) as Runnable
                )
                field("client").set(a, client)
                val state = field("drivingState").get(a) as DrivingState
                state.useSimulation(true)
                state.setSimulatedMoving(false)
                field("opened").set(a, parent)
                render.invoke(a)
                check(labels().contains("이후 관측 원본"))
                open.invoke(a, parent)
            }
            await { field("roadSource").get(a) != null && !field("busy").getBoolean(a) }
            runOnMainSync { check(labels().any { it.contains("수직 신호 최댓값") && it.contains("0.070") }) }
            waitForIdleSync()
            Thread.sleep(300)
            uiAutomation.takeScreenshot()?.let { b ->
                File(targetContext.filesDir, "road-source-main-v210.png").outputStream()
                    .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            runOnMainSync { method("returnFromRoadSource").invoke(a) }
            await {
                !field("busy").getBoolean(a) && (field("opened").get(a) as? JSONObject)?.optString(
                    "id"
                ) == parent.getString("id")
            }
            runOnMainSync {
                check(field("roadSource").get(a) == null)
                check(labels().contains("이후 관측 원본"))
                open.invoke(a, parent)
            }
            await { field("roadSource").get(a) != null }
            runOnMainSync {
                (field("drivingState").get(a) as DrivingState).setSimulatedMoving(true)
                render.invoke(a)
                check(field("roadSource").get(a) == null && labels().none { it.contains("수직 신호 최댓값") })
            }
            runOnMainSync {
                (field("drivingState").get(a) as DrivingState).setSimulatedMoving(false)
                render.invoke(a)
                open.invoke(a, parent)
                callActivityOnPause(a)
            }
            Thread.sleep(1500)
            runOnMainSync {
                check(
                    field("roadSource").get(a) == null && field("roadSourceParent").get(
                        a
                    ) == null && labels().none { it.contains("수직 신호 최댓값") })
            }
            runOnMainSync {
                callActivityOnResume(a);
                (field("timer").get(a) as android.os.Handler).removeCallbacks(
                    field("feedPoll").get(
                        a
                    ) as Runnable
                )
            }
            await { !field("busy").getBoolean(a) }
            runOnMainSync {
                field("opened").set(a, parent)
                open.invoke(a, parent)
            }
            await { field("roadSource").get(a) != null }
            runOnMainSync {
                method("returnFromRoadSource").invoke(a)
                callActivityOnPause(a)
            }
            Thread.sleep(1500)
            runOnMainSync {
                check(
                    field("roadSource").get(a) == null && field("roadSourceParent").get(
                        a
                    ) == null && labels().none { it.contains("수직 신호 최댓값") })
            }
            File(
                targetContext.filesDir,
                "road-source-main-v210.json"
            ).writeText(
                JSONObject().put("parent_id", parent.getString("id")).put(
                    "followup_id",
                    parent.getJSONObject("road_followup_case").getString("followup_id")
                ).put("live_source_loaded", true).put("mismatched_reference_rejected", true)
                    .put("return_to_same_parent", true).put("driving_clears_source", true)
                    .put("pause_discards_response", true)
                    .put("pause_discards_return_response", true).put(
                    "scope",
                    "actual Main method routes with local HTTP and debug driving states; no control taps or actual vehicle motion"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: live source load, mismatched reference refusal, same-parent return, D hides source, pause invalidates pending response.\n"
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
