package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import org.json.JSONObject

/** Actual Main audio control with synthetic driving states; no taps or OEM changes. */
class ParkedSpeechProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = screen
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            val update = MainActivity::class.java.getDeclaredMethod("updateComparisonSpeech")
                .apply { isAccessible = true }

            fun await(test: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < end) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(40)
                }
                error("Main audio state timeout")
            }
            await {
                (field("uxMonitor").get(a) as? VehicleUxMonitor)?.restricted == false && !field(
                    "comparisonLoading"
                ).getBoolean(a)
            }
            val speech = field("comparisonSpeech").get(a) as ComparisonSpeech
            val state = field("drivingState").get(a) as DrivingState
            val notice = field("comparisonNotice").get(a) as ComparisonNoticeState
            runOnMainSync {
                // Stop background polling so the authored test IDs cannot be replaced by live records.
                val timer = field("timer").get(a) as android.os.Handler
                timer.removeCallbacks(field("feedPoll").get(a) as Runnable)
                state.useSimulation(true)
                state.setSimulatedMoving(false)
                field("comparisonSpeechEnabled").setBoolean(a, false)
                val now = SystemClock.elapsedRealtime()
                notice.invalidate()
                notice.accept(emptySet(), now)
                notice.accept(setOf("parked-speech-test"), now)
                update.invoke(a)
                check(speech.starts == 0)
                field("comparisonSpeechEnabled").setBoolean(a, true)
                state.setSimulatedMoving(true)
                update.invoke(a)
                check(speech.starts == 0)
                state.setSimulatedMoving(false)
                update.invoke(a)
                check(speech.starts == 1 && speech.playing)
                state.setSimulatedMoving(true)
                update.invoke(a)
                check(!speech.playing)
                state.setSimulatedMoving(false)
                update.invoke(a)
                check(speech.starts == 1 && !speech.playing)
                field("comparisonSpeechEnabled").setBoolean(a, false)
                callActivityOnPause(a)
                check(!speech.playing)
            }
            File(
                targetContext.filesDir,
                "parked-speech-v208.json"
            ).writeText(
                JSONObject().put("default_enabled", ComparisonSpeechPolicy.DEFAULT_ENABLED)
                    .put("muted_start_blocked", true).put("simulated_drive_start_blocked", true)
                    .put("opted_in_park_start", true).put("drive_transition_stops", true)
                    .put("no_replay_on_return", true).put("saved_preferences_unchanged", true).put(
                    "scope",
                    "actual Main controller with authored debug states; no real driving or direct UI taps"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: muted and simulated D starts blocked; enabled parked start; D transition stops; no replay after returning to P. Saved preferences unchanged.\n"
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
