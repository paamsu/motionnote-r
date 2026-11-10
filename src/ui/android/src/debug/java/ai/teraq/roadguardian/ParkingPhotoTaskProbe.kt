package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Hold real import/OCR across a framework recreation, using isolated synthetic storage. */
class ParkingPhotoTaskProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val release = CountDownLatch(1)
        var screen: Activity? = null
        try {
            val directory = File(
                targetContext.cacheDir,
                "photo-task-probe-${java.util.UUID.randomUUID()}"
            ).apply { mkdirs() }
            val context = object : ContextWrapper(targetContext) {
                override fun getFilesDir() = directory
            }
            val source = File(directory, "fixture.png")
            Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                source.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            val real =
                ParkingPhotoTask.create(context, listOf(Uri.fromFile(source)), emptyList(), true)
            val entered = CountDownLatch(1)
            val held = FutureTask {
                entered.countDown()
                check(release.await(8, TimeUnit.SECONDS))
                real.run()
                real.get()
            }

            fun field(name: String) = PhotoParkingActivity::class.java.getDeclaredField(name)
                .apply { isAccessible = true }
            screen = startActivitySync(
                Intent(
                    targetContext,
                    PhotoParkingActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            runOnMainSync {
                field("photoTask").set(screen, held)
                field("busy").setBoolean(screen, true)
                field("floor").set(screen, "B2")
                field("landmark").set(screen, "24")
                field("floorEdited").setBoolean(screen, true)
                field("landmarkEdited").setBoolean(screen, true)
            }
            Thread(held, "photo-task-probe").start()
            check(entered.await(3, TimeUnit.SECONDS))
            val old = screen!!
            val monitor = addMonitor(PhotoParkingActivity::class.java.name, null, false)
            runOnMainSync { old.recreate() }
            screen = waitForMonitorWithTimeout(monitor, 5000) ?: error("Recreation timeout")
            removeMonitor(monitor)
            runOnMainSync {
                check(screen !== old)
                check(field("photoTask").get(screen) === held)
            }
            release.countDown()
            val processed = held.get(45, TimeUnit.SECONDS)
            check(processed.imported && processed.photos.size == 1 && processed.recognized.isBlank())
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (true) {
                var consumed = false
                runOnMainSync { consumed = field("photoTask").get(screen) == null }
                if (consumed) break
                check(SystemClock.elapsedRealtime() < deadline) { "Photo result not consumed" }
                SystemClock.sleep(20)
            }
            runOnMainSync {
                check(!field("busy").getBoolean(screen))
                check(field("floor").get(screen) == "B2" && field("landmark").get(screen) == "24")
                val photos = field("photos").get(screen) as List<*>
                check(photos.size == 1 && photos[0] == processed.photos[0])
                check(field("status").get(screen).toString().contains("직접 입력한 값은 유지"))
            }
            check(ParkingPhotoStore(context).history().records.isEmpty())
            result.putString(
                "stream",
                "PASS: real isolated image import and ML Kit OCR retained across Activity recreation; one photo consumed and manual B2/24 preserved; no parking record saved. Task injection, not camera UI or process-death test.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            release.countDown()
            runOnMainSync { screen?.finish() }
        }
    }
}
