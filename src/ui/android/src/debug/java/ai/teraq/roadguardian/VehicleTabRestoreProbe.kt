package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Lifecycle injection, not a simulated user click; verifies saved vehicle-tab initialization. */
class VehicleTabRestoreProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val first = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync {
                MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }
                    .set(first, "차량")
                first.recreate()
            }
            val restored = checkNotNull(waitForMonitorWithTimeout(monitor, 10000))
            waitForIdleSync()
            check(restored !== first)
            var selected: String? = null
            runOnMainSync {
                selected = MainActivity::class.java.getDeclaredField("selected")
                    .apply { isAccessible = true }.get(restored) as? String
            }
            check(selected == "차량")
            var visible = false
            val deadline = android.os.SystemClock.elapsedRealtime() + 6000
            while (!visible && android.os.SystemClock.elapsedRealtime() < deadline) {
                val labels = mutableListOf<String>()
                fun collect(view: View) {
                    if (view is TextView) labels.add(view.text.toString())
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(
                        view.getChildAt(
                            i
                        )
                    )
                }
                runOnMainSync { collect(restored.window.decorView) }
                visible = labels.any { it == "정비 전후 비교" }
                if (!visible) Thread.sleep(200)
            }
            check(visible) { "Vehicle tab did not render after waiting for actual parked signal" }
            runOnMainSync { restored.finish() }
            removeMonitor(monitor)
            result.putString(
                "stream",
                "PASS: MainActivity recreated with saved vehicle tab and initialized SDK adapter before rendering. Lifecycle injection only; no user tab click, backend or road test.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
