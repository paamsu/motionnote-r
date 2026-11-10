package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/** Executes the real service lifecycle, without synthesizing a descent event. */
class ParkingServiceProbe : Instrumentation() {
    private var expectDenied = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectDenied = arguments?.getString("expect_denied") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val intent = Intent(targetContext, ParkingMonitorService::class.java)
        try {
            check(!ParkingMonitorService.running) { "An existing monitoring session must not be disturbed" }
            targetContext.startForegroundService(intent)
            if (expectDenied) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (!ParkingMonitorService.status.contains("알림을 허용") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(
                    100
                )
                check(!ParkingMonitorService.running && ParkingMonitorService.status.contains("알림을 허용")) { "Denied permission did not block monitoring" }
                check(targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 41 })
                result.putString(
                    "stream",
                    "PASS: denied notification permission blocked sensor monitoring and foreground notification.\n"
                )
                finish(Activity.RESULT_OK, result)
                return
            }
            val until = SystemClock.elapsedRealtime() + 5000
            while (!ParkingMonitorService.running && SystemClock.elapsedRealtime() < until) Thread.sleep(
                100
            )
            check(ParkingMonitorService.running) { ParkingMonitorService.status }
            val manager = targetContext.getSystemService(NotificationManager::class.java)
            val notification = manager.activeNotifications.firstOrNull { it.id == 41 }
            check(notification != null) { "Missing visible service notification" }
            check(notification.notification.actions?.any { it.title == "감지 끄기" } == true) { "Missing stop action" }
            // Exercise the notification's actual PendingIntent, not a separate stop helper.
            notification.notification.actions.first { it.title == "감지 끄기" }.actionIntent.send()
            val stopped = SystemClock.elapsedRealtime() + 5000
            while (ParkingMonitorService.running && SystemClock.elapsedRealtime() < stopped) Thread.sleep(
                100
            )
            check(!ParkingMonitorService.running) { "Service did not stop" }
            check(manager.activeNotifications.none { it.id == 41 }) { "Foreground notification remained" }
            result.putString(
                "stream",
                "PASS: Android foreground service started; required sensors registered; status notification and stop PendingIntent worked; sensor service stopped. Descent-triggered location notification not exercised.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
