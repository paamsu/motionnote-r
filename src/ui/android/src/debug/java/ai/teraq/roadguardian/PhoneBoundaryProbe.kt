package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** Launches actual phone screens without changing vehicle state or UX restrictions. */
class PhoneBoundaryProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val automotive =
            targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        try {
            for (type in listOf(
                OwnerActivity::class.java, ParkingActivity::class.java,
                PhotoParkingActivity::class.java, ParkingHistoryActivity::class.java
            )) {
                val monitor = addMonitor(type.name, null, false)
                try {
                    targetContext.startActivity(
                        Intent(
                            targetContext,
                            type
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    val activity = waitForMonitorWithTimeout(monitor, 5000)
                        ?: error("No lifecycle observation for ${type.simpleName}")
                    waitForIdleSync()
                    runOnMainSync {
                        if (automotive) {
                            check(activity.isFinishing || activity.isDestroyed) { "Vehicle accepted ${type.simpleName}" }
                        } else {
                            check(!activity.isFinishing && !activity.isDestroyed) { "Phone rejected ${type.simpleName}" }
                            val content =
                                activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                            check(content.childCount > 0) { "Phone content missing for ${type.simpleName}" }
                            activity.finish()
                        }
                    }
                    waitForIdleSync()
                } finally {
                    removeMonitor(monitor)
                }
            }
            result.putString(
                "stream",
                "PASS: four actual phone activities ${if (automotive) "finish on automotive" else "render on phone"}; no vehicle state override, photo capture or account login.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
