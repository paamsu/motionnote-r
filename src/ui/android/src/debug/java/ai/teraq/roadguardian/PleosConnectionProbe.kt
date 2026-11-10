package ai.teraq.roadguardian

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle

/** Read-only probe of actual SDK access. A failed connection is recorded, not hidden. */
class PleosConnectionProbe : Instrumentation() {
    private var traceWipers = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        traceWipers = arguments?.getString("trace_wipers") == "true"
        start()
    }

    override fun onStart() {
        var adapter: PleosAdapter? = null
        var ux: VehicleUxMonitor? = null
        val result = Bundle()
        try {
            runOnMainSync {
                adapter = PleosAdapter(targetContext, {}, { _, _ -> })
                adapter!!.start()
                adapter!!.startTireMonitoring()
                ux = VehicleUxMonitor(targetContext) {}
                ux!!.start()
            }
            val trace = org.json.JSONArray()
            if (traceWipers) repeat(30) {
                Thread.sleep(1000)
                runOnMainSync { trace.put(adapter!!.diagnosticSnapshot().getJSONObject("wipers")) }
            } else Thread.sleep(6000)
            runOnMainSync {
                val snapshot = adapter!!.diagnosticSnapshot().put("platform_ux", ux!!.snapshot())
                if (traceWipers) snapshot.put("wiper_trace", trace)
                check(snapshot.getString("sdk_version") == BuildConfig.PLEOS_SDK_VERSION)
                check(!snapshot.getBoolean("synthetic_fallback"))
                adapter!!.stopTireMonitoring()
                val stopped = adapter!!.tireSnapshot()
                check(!stopped.getBoolean("running"))
                for (wheel in PleosTireMonitor.WHEELS) check(
                    !stopped.getJSONObject("wheels").getJSONObject(wheel).getJSONObject("pressure")
                        .getBoolean("fresh")
                )
                snapshot.put("tires_after_stop", stopped)
                result.putString("stream", snapshot.toString(2) + "\n")
                adapter!!.close()
                ux!!.close()
            }
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", e.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
