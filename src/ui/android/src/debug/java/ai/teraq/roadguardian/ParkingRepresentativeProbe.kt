package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.os.ParcelFileDescriptor
import android.os.Bundle
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Authored sequence using existing storage/reminder/activity. No OCR or descent detection claim. */
class ParkingRepresentativeProbe : Instrumentation() {
    private var source = "/data/local/tmp/parking-v634"
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        source = args?.getString("source") ?: source
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        val directory = File(targetContext.filesDir, "parking-photos")
        val latest = File(directory, "latest.json")
        val prior = if (latest.exists()) latest.readBytes() else null
        val output = File(
            targetContext.cacheDir,
            "parking-representative-v634-${UUID.randomUUID()}"
        ).apply { mkdirs() }

        fun shell(command: String) =
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }

        fun visibleLabels(): List<String> {
            val found = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (node.isVisibleToUser && !bounds.isEmpty) node.text?.toString()
                    ?.takeIf { it.isNotBlank() }?.let { found.add(it) }
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(uiAutomation.rootInActiveWindow)
            return found
        }

        fun capture(name: String) {
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(output, "$name.png").outputStream()
                .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        try {
            check(BuildConfig.DEBUG && !targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            val manager = targetContext.getSystemService(NotificationManager::class.java)
            check(manager.areNotificationsEnabled())
            directory.mkdirs()
            val photos = listOf("front", "rear", "left", "right").map { direction ->
                File(
                    source,
                    "$direction.png"
                ).copyTo(File(directory, "representative-${UUID.randomUUID()}-$direction.png"))
            }
            val store = ParkingPhotoStore(targetContext)
            val saved = store.save("B2", "24번 기둥", photos, "대표 장면 작성 표지: B2 · 24 (OCR 실행 아님)")
            val id = saved.getString("id")
            check(store.record(id)?.getJSONArray("photos")?.length() == 4)
            screen = startActivitySync(
                Intent(
                    targetContext,
                    ParkingActivity::class.java
                ).putExtra("parking_record_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            waitForIdleSync()
            Thread.sleep(2500)
            capture("saved-four-photo-record")
            runOnMainSync { screen?.finish() }
            screen = null
            ParkingReminder.show(targetContext, saved, 63442)
            // NotificationManager publishes the row asynchronously after notify().
            val postedDeadline = android.os.SystemClock.elapsedRealtime() + 5000
            var posted = manager.activeNotifications.firstOrNull { it.id == 63442 }
            while (posted == null && android.os.SystemClock.elapsedRealtime() < postedDeadline) {
                Thread.sleep(50)
                posted = manager.activeNotifications.firstOrNull { it.id == 63442 }
            }
            val notice =
                checkNotNull(posted) { "Parking reminder was not registered within 5 seconds" }.notification
            check(notice.extras.getString("android.text")?.contains("B2 · 24번 기둥") == true)
            // NotificationManager registration precedes SystemUI binding/animation. Await the visible row.
            Thread.sleep(1000)
            shell("cmd statusbar expand-notifications")
            val deadline = android.os.SystemClock.elapsedRealtime() + 12000
            var labels = emptyList<String>()
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                labels = visibleLabels()
                if (labels.any { it.contains("내 차를 찾으러 가시나요?") } && labels.any {
                        it.contains("B2") && it.contains(
                            "24"
                        )
                    }) break
                Thread.sleep(300)
            }
            File(output, "notification-visible-labels.json").writeText(
                org.json.JSONArray(labels).toString(2)
            )
            File(
                output,
                "notification-system.txt"
            ).writeText(shell("dumpsys notification --noredact"))
            capture("location-reminder")
            check(labels.any { it.contains("내 차를 찾으러 가시나요?") } && labels.any {
                it.contains("B2") && it.contains(
                    "24"
                )
            }) { "Registered notification did not become visibly readable in SystemUI" }
            Thread.sleep(700)
            capture("location-reminder-settled")
            fun notificationBounds(node: AccessibilityNodeInfo?): Rect? {
                if (node == null) return null
                if (node.isVisibleToUser && node.text?.toString()
                        ?.contains("내 차를 찾으러 가시나요?") == true
                )
                    return Rect().also { node.getBoundsInScreen(it) }
                for (i in 0 until node.childCount) notificationBounds(node.getChild(i))?.let { return it }
                return null
            }

            val bounds = checkNotNull(notificationBounds(uiAutomation.rootInActiveWindow))
            check(!bounds.isEmpty)
            val destination = addMonitor(ParkingActivity::class.java.name, null, false)
            try {
                // Open the visible SystemUI notification, as a user would, rather than
                // sending a PendingIntent from a background instrumentation process.
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                screen = checkNotNull(
                    waitForMonitorWithTimeout(
                        destination,
                        10000
                    )
                ) { "Notification did not open ParkingActivity" }
                check(screen!!.intent.getStringExtra("parking_record_id") == id) { "Notification opened a different parking record" }
            } finally {
                removeMonitor(destination)
            }
            val openedDeadline = android.os.SystemClock.elapsedRealtime() + 8000
            var openedLabels = visibleLabels()
            while (android.os.SystemClock.elapsedRealtime() < openedDeadline && !openedLabels.any {
                    it.contains(
                        "알림의 주차 위치"
                    )
                }) {
                Thread.sleep(200)
                openedLabels = visibleLabels()
            }
            check(openedLabels.any { it.contains("알림의 주차 위치") } && openedLabels.any { it.contains("B2") } && openedLabels.any {
                it.contains(
                    "24"
                )
            }) { "Same-record destination was not visibly rendered" }
            capture("same-location-opened")
            File(output, "destination-visible-labels.json").writeText(
                org.json.JSONArray(
                    openedLabels
                ).toString(2)
            )
            check(
                store.record(id)?.getJSONArray("photo_sha256")
                    ?.toString() == saved.getJSONArray("photo_sha256").toString()
            )
            File(output, "result.json").writeText(
                JSONObject().put("record_id", id).put("photo_count", 4)
                    .put("same_record_intent", true)
                    .put("source", "authored_representative_sequence").put("ocr_performed", false)
                    .put("descent_detection_tested", false)
                    .put("notification_title_location_visible", true)
                    .put("destination_visible", true).put(
                        "notification_open_method",
                        "ADB tap on visible SystemUI notification title"
                    )
                    .put("actual_phone_emulator_ui", true).put("real_device", false)
                    .put("external_submission", false).toString(2)
            )
            result.putString(
                "result",
                "PASS saved four photos → visible SystemUI reminder → same record Activity"
            )
            result.putString("output", output.absolutePath)
            finish(-1, result)
        } catch (t: Throwable) {
            result.putString("failure", t.stackTraceToString())
            result.putString("output", output.absolutePath)
            finish(0, result)
        } finally {
            runOnMainSync { screen?.finish() }
            targetContext.getSystemService(NotificationManager::class.java).cancel(63442)
            // Keep added representative history and image files; restore previous latest pointer only.
            if (prior != null) latest.writeBytes(prior)
        }
    }
}
