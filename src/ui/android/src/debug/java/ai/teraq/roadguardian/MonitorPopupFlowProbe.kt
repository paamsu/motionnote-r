package ai.teraq.roadguardian

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import java.io.File

class MonitorPopupFlowProbe : Instrumentation() {
    private var expectedTitle = "포트홀 감지"
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        args?.getString("title_base64")?.let {
            expectedTitle =
                String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }
        start()
    }

    override fun onStart() {
        val out = Bundle()
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        fun capture(stage: String) {
            val b = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "monitor-$stage.png").outputStream()
                .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        try {
            check(BuildConfig.PLEOS_DEMO_HEADS_UP && DriverEventNotification.available(targetContext))
            capture("before")
            runOnMainSync {
                targetContext.startForegroundService(
                    Intent(
                        targetContext,
                        DemoEventMonitorService::class.java
                    )
                )
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (manager.activeNotifications.none { it.id == DriverEventNotification.NOTIFICATION_ID } && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(
                200
            )
            val notice =
                checkNotNull(manager.activeNotifications.firstOrNull { it.id == DriverEventNotification.NOTIFICATION_ID }) { "Monitor did not publish" }
            check(notice.notification.category == "connect.system")
            check(
                notice.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                    .toString() == expectedTitle
            ) { "Unexpected notification title" }
            Thread.sleep(1500)
            capture("visible")
            Thread.sleep(2000)
            capture("later")
            runOnMainSync {
                targetContext.stopService(
                    Intent(
                        targetContext,
                        DemoEventMonitorService::class.java
                    )
                )
            }
            manager.cancel(notice.tag, notice.id)
            Thread.sleep(500)
            capture("returned")
            out.putString(
                "stream",
                "PASS: service HTTP polling published actual DriverEventNotification; inspect captures\n"
            )
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, out)
        } finally {
            targetContext.stopService(Intent(targetContext, DemoEventMonitorService::class.java))
            manager.activeNotifications.filter { it.id == DriverEventNotification.NOTIFICATION_ID }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }
}
