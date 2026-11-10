package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle

/** Launch only; direct interaction and layout are assessed separately. */
class ParkingEmptyProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            startActivitySync(
                Intent(
                    targetContext,
                    ParkingEmptyPreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            waitForIdleSync()
            sendStatus(
                1,
                Bundle().apply {
                    putString(
                        "stream",
                        "OPENED: isolated empty parking storage; inspect production screen with UI tools.\n"
                    )
                })
            Thread.sleep(120000)
            result.putString(
                "stream",
                "Preview ended; no automated layout or navigation assertion.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
