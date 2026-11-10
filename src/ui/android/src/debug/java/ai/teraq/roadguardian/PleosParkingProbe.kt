package ai.teraq.roadguardian

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses actual SDK callbacks; change D then P in the official console during this probe. */
class PleosParkingProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val events = JSONArray()
        val completed = CountDownLatch(1)
        val state = DrivingState(true)
        val arrival = ParkingArrival()
        var sawDrive = false
        var adapter: PleosAdapter? = null
        val result = Bundle()
        try {
            runOnMainSync {
                adapter = PleosAdapter(targetContext, {}, { gear, observedAt ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    state.observe(gear, observedAt)
                    if (gear == "GEAR_DRIVE" && state.restricted(now)) sawDrive = true
                    val cue = arrival.observe(gear, observedAt)
                    events.put(
                        JSONObject().put("elapsed_ms", now).put("observed_at_ms", observedAt)
                            .put("gear", gear ?: JSONObject.NULL)
                            .put("restricted", state.restricted(now)).put("parking_cue", cue)
                    )
                    if (cue && sawDrive && !state.restricted(now)) completed.countDown()
                })
                adapter!!.start()
            }
            val passed = completed.await(45, TimeUnit.SECONDS)
            runOnMainSync { adapter!!.close() }
            check(passed) { "Did not observe D restricted then stable P parking cue within 45 seconds" }
            result.putString(
                "stream",
                "PASS: actual Pleos SDK D restriction and stable P parking cue. No injected gear.\n" + events.toString(
                    2
                ) + "\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            runOnMainSync { adapter?.close() }
            result.putString("stream", "FAIL: $e\n" + events.toString(2))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
