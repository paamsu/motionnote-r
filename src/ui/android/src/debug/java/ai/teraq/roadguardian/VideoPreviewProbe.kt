package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle

/** Opens a preview for manual inspection; no UI actions. */
class VideoPreviewProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        startActivitySync(
            Intent(
                targetContext,
                VideoPreviewActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitForIdleSync()
        Thread.sleep(120000) // Keep the instrumentation process alive for manual inspection.
        finish(
            Activity.RESULT_OK,
            Bundle().apply {
                putString(
                    "stream",
                    "OPENED synthetic video panel. Manual inspection pending.\n"
                )
            })
    }
}
