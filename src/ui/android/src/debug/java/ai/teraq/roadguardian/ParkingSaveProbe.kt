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

/** Real save in isolated cache storage, retained across actual framework recreation. */
class ParkingSaveProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val release = CountDownLatch(1)
        var current: Activity? = null
        try {
            val directory = File(
                targetContext.cacheDir,
                "save-probe-${java.util.UUID.randomUUID()}"
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
            val store = ParkingPhotoStore(context)
            val photo = store.importPhoto(Uri.fromFile(source))
            val save = ParkingSaveTask.create(context, "B2", "24", listOf(photo), "synthetic test")
            val entered = CountDownLatch(1)
            val held = FutureTask<String?> {
                entered.countDown()
                check(release.await(8, TimeUnit.SECONDS))
                save.run()
                save.get()
            }
            current = startActivitySync(
                Intent(
                    targetContext,
                    PhotoParkingActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val taskField = PhotoParkingActivity::class.java.getDeclaredField("saveTask")
                .apply { isAccessible = true }
            runOnMainSync {
                taskField.set(current, held)
                PhotoParkingActivity::class.java.getDeclaredField("busy")
                    .apply { isAccessible = true }.setBoolean(current, true)
            }
            Thread(held, "parking-save-probe").start()
            check(entered.await(3, TimeUnit.SECONDS))
            val previous = current!!
            val monitor = addMonitor(PhotoParkingActivity::class.java.name, null, false)
            runOnMainSync { previous.recreate() }
            current = waitForMonitorWithTimeout(monitor, 5000) ?: error("Recreation timeout")
            removeMonitor(monitor)
            runOnMainSync {
                check(current !== previous)
                check(taskField.get(current) === held)
            }
            release.countDown()
            check(held.get(5, TimeUnit.SECONDS) == null)
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (true) {
                var done = false
                runOnMainSync { done = current!!.isFinishing || current!!.isDestroyed }
                if (done) break
                check(SystemClock.elapsedRealtime() < deadline) { "Save result not consumed" }
                SystemClock.sleep(20)
            }
            val latest = store.latestResult().record ?: error("Missing saved record")
            check(latest.getString("floor") == "B2" && latest.getString("landmark") == "24")
            check(latest.getString("photo_integrity") == "verified")
            check(store.history().records.size == 1) { "Duplicate save" }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val barrier = java.util.concurrent.CyclicBarrier(4)
                val jobs = (0..2).map { writer ->
                    pool.submit {
                        barrier.await(3, TimeUnit.SECONDS)
                        repeat(8) { index ->
                            ParkingPhotoStore(context).save(
                                "B2",
                                "$writer-$index",
                                listOf(photo),
                                "concurrent synthetic"
                            )
                        }
                    }
                } + pool.submit {
                    barrier.await(3, TimeUnit.SECONDS)
                    repeat(40) {
                        val read = ParkingPhotoStore(context).latestResult()
                        check(read.error == null && read.record != null) { "Concurrent latest read failed" }
                        check(ParkingPhotoStore(context).record(read.record!!.getString("id")) != null)
                    }
                }
                jobs.forEach { it.get(10, TimeUnit.SECONDS) }
                val history = store.history()
                check(history.error == null && history.unreadable == 0 && history.records.size == 25)
                val ids = history.records.map { it.getString("id") }.toSet()
                check(ids.size == 25 && store.latestResult().record!!.getString("id") in ids)
                ids.forEach { check(store.record(it)?.getString("photo_integrity") == "verified") }
            } finally {
                pool.shutdownNow()
            }
            result.putString(
                "stream",
                "PASS: real isolated save survives Activity recreation with one retained task; 3 concurrent writers commit 24 more records alongside 40 latest reads; all 25 history records verify and latest points to history. No user photos or UI button flow tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            release.countDown()
            runOnMainSync { current?.finish() }
        }
    }
}
