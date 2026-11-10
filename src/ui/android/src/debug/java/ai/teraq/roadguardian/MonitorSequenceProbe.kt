package ai.teraq.roadguardian

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MonitorSequenceProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val out = Bundle()
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        val results = JSONArray()
        fun capture(stage: String) {
            val b = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "sequence-$stage.png").outputStream()
                .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        try {
            val cases = JSONArray(File(targetContext.filesDir, "sequence-cases.json").readText())
            check(BuildConfig.PLEOS_DEMO_HEADS_UP && DriverEventNotification.available(targetContext))
            runOnMainSync {
                targetContext.startForegroundService(
                    Intent(
                        targetContext,
                        DemoEventMonitorService::class.java
                    )
                )
            }
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val key = checkNotNull(DriverEventNotification.identity(c.getJSONObject("event")))
                val deadline = android.os.SystemClock.elapsedRealtime() + 25000
                while (manager.activeNotifications.none { it.tag == key } && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(
                    100
                )
                val notice =
                    checkNotNull(manager.activeNotifications.firstOrNull { it.tag == key }) { "Missing event $i" }
                check(
                    notice.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                        .toString() == c.getString("title")
                )
                check(DemoEventMonitorService.running)
                Thread.sleep(1200)
                capture("$i-visible")
                manager.cancel(key, DriverEventNotification.NOTIFICATION_ID)
                Thread.sleep(5500)
                check(manager.activeNotifications.none { it.tag == key }) { "Duplicate replay $i" }
                capture("$i-no-replay")
                results.put(
                    JSONObject().put("index", i).put("title", c.getString("title"))
                        .put("same_service_running", true).put("duplicate_suppressed", true)
                )
            }
            File(targetContext.filesDir, "sequence-result.json").writeText(results.toString(2))
            runOnMainSync {
                targetContext.stopService(
                    Intent(
                        targetContext,
                        DemoEventMonitorService::class.java
                    )
                )
            }
            out.putString(
                "stream",
                "PASS: same-session sequential events and repeated-feed suppression\n"
            )
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, out)
        } finally {
            File(targetContext.filesDir, "sequence-result.json").writeText(results.toString(2))
            targetContext.stopService(Intent(targetContext, DemoEventMonitorService::class.java))
            manager.activeNotifications.filter { it.id == DriverEventNotification.NOTIFICATION_ID }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }
}
