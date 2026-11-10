package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import org.json.JSONObject
import java.io.File

class EventNotificationProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val out = Bundle()
        try {
            val event = JSONObject(File(targetContext.filesDir, "popup-fixture.json").readText())
            var posted = false
            fun capture(name: String) {
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "notification-$name.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            capture("before")
            runOnMainSync { posted = DriverEventNotification.show(targetContext, event) }
            check(posted) { "Notification permission/channel unavailable" }
            Thread.sleep(1500)
            capture("visible")
            val manager =
                targetContext.getSystemService(android.app.NotificationManager::class.java)
            check(manager.activeNotifications.any { it.tag == DriverEventNotification.identity(event) })
            Thread.sleep(1500)
            manager.cancel(
                DriverEventNotification.identity(event),
                DriverEventNotification.NOTIFICATION_ID
            )
            Thread.sleep(500)
            capture("returned")
            out.putString(
                "stream",
                "PASS: system notification posted and removed; visual overlay must be reviewed separately\n"
            )
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, out)
        }
    }
}
