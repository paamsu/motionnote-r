package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle

/** Emulator GPS input must be supplied externally; this does not inject callbacks. */
class VehicleLocationProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val monitor = VehicleLocationMonitor(targetContext)
        try {
            startActivitySync(
                Intent(
                    targetContext,
                    OwnerActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            runOnMainSync { monitor.start() }
            sendStatus(
                1,
                Bundle().apply {
                    putString(
                        "stream",
                        "READY: awaiting emulator GPS 37.5,127.0 then 37.51,127.01\n"
                    )
                })
            for ((lat, lon) in listOf(37.5 to 127.0, 37.51 to 127.01)) {
                var matched = false
                val deadline = android.os.SystemClock.elapsedRealtime() + 30000
                while (!matched && android.os.SystemClock.elapsedRealtime() < deadline) {
                    runOnMainSync {
                        monitor.current()?.let {
                            matched =
                                kotlin.math.abs(it.latitude - lat) < 0.00001 && kotlin.math.abs(it.longitude - lon) < 0.00001
                        }
                    }
                    if (!matched) Thread.sleep(100)
                }
                check(matched) { "GPS callback missing for $lat,$lon; ${monitor.status}" }
                sendStatus(
                    1,
                    Bundle().apply {
                        putString(
                            "stream",
                            "PASS: Android GPS callback $lat,$lon\n"
                        )
                    })
            }
            runOnMainSync {
                monitor.stop()
                check(monitor.current() == null)
            }
            result.putString(
                "stream",
                "PASS: two GPS updates and stop clears location. Synthetic emulator coordinates; map UI and real accuracy unverified.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            runOnMainSync { monitor.stop() }
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
