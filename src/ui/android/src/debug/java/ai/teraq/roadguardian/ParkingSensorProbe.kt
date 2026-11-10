package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Receives emulator sensor events through the real SensorManager path. */
class ParkingSensorProbe : Instrumentation() {
    private var expectCue = true
    private var pressureQuality: String? = null
    private var notificationCheck = true
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectCue = arguments?.getString("expect_cue") != "false"
        pressureQuality = arguments?.getString("pressure_quality")
        notificationCheck = arguments?.getString("notification_check") != "false"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val cue = CountDownLatch(1)
        var monitor: PhoneDescentMonitor? = null
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        try {
            if (notificationCheck) check(manager.areNotificationsEnabled()) { "Notification permission required" }
            runOnMainSync {
                monitor = PhoneDescentMonitor(targetContext) {
                    if (notificationCheck) ParkingReminder.show(
                        targetContext,
                        JSONObject().put("floor", "B2").put("landmark", "24번 기둥")
                            .put("source", "synthetic_fixture"),
                        9042
                    )
                    cue.countDown()
                }
                check(monitor!!.start().contains("감지 중"))
            }
            Thread.sleep(1000) // Let initial sensor accuracy callbacks settle before the injected transition.
            runOnMainSync {
                if (pressureQuality != null) {
                    val sensors =
                        targetContext.getSystemService(android.hardware.SensorManager::class.java)
                    val pressure =
                        checkNotNull(sensors.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE))
                    monitor!!.onAccuracyChanged(
                        pressure,
                        android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE
                    )
                    if (pressureQuality == "recovered") monitor!!.onAccuracyChanged(
                        pressure,
                        android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                    )
                }
            }
            sendStatus(
                1,
                Bundle().apply {
                    putString(
                        "stream",
                        "READY: registered Android sensor listeners; inject emulator pressure and acceleration now.\n"
                    )
                })
            if (!expectCue) {
                check(
                    !cue.await(
                        17,
                        TimeUnit.SECONDS
                    )
                ) { "Unexpected descent notification in negative control" }
                if (notificationCheck) check(manager.activeNotifications.none { it.id == 9042 })
                result.putString(
                    "stream",
                    "PASS: negative control produced no descent callback. notification_check=$notificationCheck\n"
                )
                finish(Activity.RESULT_OK, result)
                return
            }
            check(
                cue.await(
                    40,
                    TimeUnit.SECONDS
                )
            ) { "No descent callback from actual sensor events" }
            if (!notificationCheck) {
                result.putString(
                    "stream",
                    "PASS: emulator sensor events produced descent callback. Notification not tested.\n"
                )
                finish(Activity.RESULT_OK, result)
                return
            }
            val notification =
                manager.activeNotifications.firstOrNull { it.id == 9042 }?.notification
            check(
                notification != null && notification.extras.getString(Notification.EXTRA_TEXT)
                    ?.contains("B2") == true
            ) { "Missing location notification" }
            check(notification.extras.getString(Notification.EXTRA_TITLE)?.contains("시험") == true)
            result.putString(
                "stream",
                "PASS: emulator sensor events -> gravity projection -> descent cue -> actual Android notification with fictional B2/24. No saved parking record modified.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { monitor?.stop() }
            manager.cancel(9042)
        }
    }
}
