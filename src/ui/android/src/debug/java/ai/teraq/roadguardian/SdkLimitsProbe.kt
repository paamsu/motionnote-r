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
import org.json.JSONObject
import java.io.File

/** Actual emulator reads + explicitly separate debug simulation and close controls. */
class SdkLimitsProbe : Instrumentation() {
    private var eventId: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        eventId = arguments?.getString("eventId")
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        var player: Activity? = null
        try {
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun render() {
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
                    .invoke(activity)
            }

            val adapter = field("adapter").get(activity) as PleosAdapter
            val state = field("drivingState").get(activity) as DrivingState
            val ux = field("uxMonitor").get(activity) as VehicleUxMonitor
            fun labels(): List<String> {
                val out = mutableListOf<String>()
                fun visit(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v.text.toString())
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(activity.window.decorView)
                return out
            }

            fun await(test: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 18000
                while (SystemClock.elapsedRealtime() < end) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(80)
                }
                error("SDK UI condition timed out")
            }

            fun frame(name: String) {
                waitForIdleSync()
                Thread.sleep(250)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "sdk-limits-$name.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            runOnMainSync {
                state.useSimulation(false)
                field("selected").set(activity, "설정")
                field("light").setBoolean(activity, true)
                render()
            }
            await {
                adapter.diagnosticSnapshot().optBoolean("fresh_gear_received") && !ux.restricted
            }
            lateinit var live: JSONObject
            lateinit var actualUx: JSONObject
            runOnMainSync {
                live = adapter.diagnosticSnapshot()
                actualUx = ux.snapshot()
                check(live.getString("gear") == "GEAR_PARK")
                check(!state.restricted(SystemClock.elapsedRealtime()))
                check(!live.getBoolean("synthetic_fallback"))
            }
            frame("live-park")
            runOnMainSync {
                val timer = field("timer").get(activity) as Handler
                timer.removeCallbacks(field("feedPoll").get(activity) as Runnable)
                adapter.close()
            }
            await { "차량 상태 확인 중" in labels() }
            lateinit var closed: JSONObject
            runOnMainSync {
                closed = adapter.diagnosticSnapshot()
                check(
                    !closed.getBoolean("fresh_gear_received") && closed.isNull("gear") && closed.getString(
                        "initialization"
                    ) == "closed"
                )
                check(closed.getJSONObject("vehicle_speed").optBoolean("fresh") == false)
                check(closed.getJSONObject("vehicle_speed").isNull("kilometers_per_hour"))
                check(closed.getJSONObject("wipers").length() == 0)
                check(state.restricted(SystemClock.elapsedRealtime()))
                check(labels().none { it in setOf("정비 비교", "사건 근거") })
                adapter.start()
                check(adapter.diagnosticSnapshot().getString("initialization") == "closed")
            }
            frame("closed-unknown")
            runOnMainSync {
                state.useSimulation(true)
                state.setSimulatedMoving(true)
                render()
            }
            await { "주행 시연" in labels() }
            frame("simulated-drive")
            runOnMainSync {
                state.setSimulatedMoving(false)
                render()
            }
            await { "주행 시연" !in labels() && "차량 상태 확인 중" !in labels() }
            runOnMainSync { check(labels().none { it.contains("기어 응답: GEAR_PARK") || it.contains("Pleos 주행거리:") }) }
            frame("simulated-park")
            runOnMainSync {
                ux.close()
                render()
                check(ux.restricted && ux.videoRestricted)
                check(ux.snapshot().getString("status") == "closed")
                ux.start()
                check(ux.snapshot().getString("status") == "closed")
            }
            await { "화면 열람 제한" in labels() }
            frame("oem-closed")
            val videoResult = JSONObject().put("verified", false)
            if (eventId != null) {
                player = startActivitySync(
                    Intent(
                        targetContext,
                        VideoActivity::class.java
                    ).putExtra("event_id", eventId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                val screen = player!!
                fun vf(name: String) =
                    VideoActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

                val video = vf("video").get(screen) as android.widget.VideoView
                val mask = vf("privacyMask").get(screen) as View
                await { video.isPlaying && video.currentPosition > 1200 && mask.visibility == View.GONE }
                frame("video-playing")
                var position = 0
                runOnMainSync {
                    position = video.currentPosition;
                    (vf("adapter").get(screen) as PleosAdapter).close()
                }
                await { !video.isPlaying && mask.visibility == View.VISIBLE }
                frame("video-closed-masked")
                videoResult.put("verified", true).put("event_id", eventId)
                    .put("playing_position_ms", position)
                    .put("paused_and_masked_after_sdk_close", true)
                runOnMainSync { screen.finish() }
                player = null
            }
            File(
                targetContext.filesDir,
                "sdk-limits-result.json"
            ).writeText(
                JSONObject().put("pass", true).put("video_transition", videoResult)
                    .put("actual_emulator_sdk", live).put("actual_oem", actualUx)
                    .put("after_adapter_close", closed).put("oem_closed", ux.snapshot()).put(
                    "scope",
                    "Actual SDK/OEM emulator snapshots; production Main close/invalidation and separate debug simulated drive/park. No physical driving, no injected live SDK values, no direct touch. Optional video transition uses the named synthetic event."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: actual SDK/OEM, immediate close invalidation, Main restriction, separate debug simulation and OEM veto\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                player?.finish()
                main?.finish()
            }
        }
    }
}
