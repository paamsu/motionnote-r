package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import org.maplibre.android.maps.MapLibreMap

/** Read-only view inspection; external emulator commands supply synthetic GPS coordinates. */
class VehicleMapProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun report(message: String) =
        sendStatus(1, Bundle().apply { putString("stream", "$message\n") })

    private fun awaitMain(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 40000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < end) {
            runOnMainSync { ready = predicate() }
            if (!ready) Thread.sleep(100)
        }
        check(ready) { "Timed out waiting for map state" }
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val activity = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            var first: NativeMapPanel? = null
            report("READY: inject GPS 37.5,127.0")
            awaitMain {
                val panel = field(activity, "activeMapPanel") as? NativeMapPanel
                val map = panel?.let { field(it, "nativeMap") as? MapLibreMap }
                val p = map?.cameraPosition?.target
                val ready =
                    p != null && kotlin.math.abs(p.latitude - 37.5) < 0.00001 && kotlin.math.abs(p.longitude - 127.0) < 0.00001 && field(
                        panel,
                        "positionSource"
                    ) != null
                if (ready) first = panel
                ready
            }
            report("PASS: first GPS map center and loaded position source. NEXT: inject 37.51,127.01")
            awaitMain {
                val panel = field(activity, "activeMapPanel") as? NativeMapPanel
                val p =
                    panel?.let { field(it, "nativeMap") as? MapLibreMap }?.cameraPosition?.target
                val ready =
                    p != null && kotlin.math.abs(p.latitude - 37.51) < 0.00001 && kotlin.math.abs(p.longitude - 127.01) < 0.00001
                if (ready) check(panel === first) { "Map panel was recreated between GPS updates" }
                ready
            }
            report("PASS: second GPS center on same map instance. Holding for visual inspection 15 seconds.")
            Thread.sleep(15000)
            // Exercise the production no-fix branch without changing system location settings.
            runOnMainSync { (field(activity, "locationMonitor") as VehicleLocationMonitor).stop() }
            awaitMain {
                field(activity, "activeMapPanel") == null && field(
                    activity,
                    "renderedLocationKey"
                ) == "unavailable"
            }
            report("PASS: loss of current fix removes map; holding placeholder 15 seconds.")
            Thread.sleep(15000)
            result.putString(
                "stream",
                "PASS: GPS map update preserves map instance; stopped subscription shows unavailable branch. Real GPS accuracy and timed stale-fix path unverified.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
