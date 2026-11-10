package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import java.io.File

/** Render the existing parking record without clicking controls or changing stored data. */
class ParkingScreenProbe : Instrumentation() {
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
                    ParkingActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            waitForIdleSync()
            // Allow the existing asynchronous record request and first layout to finish.
            Thread.sleep(2500)
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            val output = File(targetContext.cacheDir, "parking-screen-v50.png")
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            result.putString(
                "stream",
                "CAPTURED: ${output.absolutePath}; rendering only, no button interactions tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
