package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A blocked reader that completes after cancellation; never reads saved user photos. */
class ParkingCueReadService : ParkingMonitorService() {
    companion object {
        @Volatile
        var instance: ParkingCueReadService? = null
        var entered = CountDownLatch(1)
        var release = CountDownLatch(1)
        var completed = CountDownLatch(1)
        var destroyed = CountDownLatch(1)
        val reads = AtomicInteger()
        val published = AtomicInteger()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun readParkingRecord(): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        val unblock = release
        val done = completed
        reads.incrementAndGet()
        entered.countDown()
        try {
            while (unblock.count > 0) try {
                unblock.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            return JSONObject().put("floor", "B2").put("landmark", "24")
                .put("source", "synthetic_fixture")
        } finally {
            done.countDown()
        }
    }

    override fun publishParkingCue(record: JSONObject) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(record.getString("floor") == "B2")
        published.incrementAndGet()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed.countDown()
    }
}

class ParkingCueReadProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val intent = Intent(targetContext, ParkingCueReadService::class.java)
        try {
            check(!ParkingMonitorService.running) { "Existing monitoring session must not be disturbed" }
            ParkingCueReadService.entered = CountDownLatch(1)
            ParkingCueReadService.release = CountDownLatch(1)
            ParkingCueReadService.completed = CountDownLatch(1)
            ParkingCueReadService.destroyed = CountDownLatch(1)
            ParkingCueReadService.reads.set(0)
            ParkingCueReadService.published.set(0)
            targetContext.startForegroundService(intent)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!ParkingMonitorService.running) {
                check(SystemClock.elapsedRealtime() < deadline) { ParkingMonitorService.status }
                SystemClock.sleep(20)
            }
            val service = ParkingCueReadService.instance ?: error("Missing service")
            val cue = ParkingMonitorService::class.java.getDeclaredMethod("showParkingCue")
                .apply { isAccessible = true }
            runOnMainSync { repeat(3) { cue.invoke(service) } }
            check(ParkingCueReadService.entered.await(3, TimeUnit.SECONDS))
            runOnMainSync {
                check(ParkingCueReadService.reads.get() == 1)
                check(ParkingCueReadService.published.get() == 0)
            }
            ParkingCueReadService.release.countDown()
            check(ParkingCueReadService.completed.await(3, TimeUnit.SECONDS))
            val publishedDeadline = SystemClock.elapsedRealtime() + 3000
            while (ParkingCueReadService.published.get() != 1) {
                check(SystemClock.elapsedRealtime() < publishedDeadline)
                SystemClock.sleep(20)
            }
            ParkingCueReadService.entered = CountDownLatch(1)
            ParkingCueReadService.release = CountDownLatch(1)
            ParkingCueReadService.completed = CountDownLatch(1)
            runOnMainSync { cue.invoke(service) }
            check(ParkingCueReadService.entered.await(3, TimeUnit.SECONDS))
            targetContext.startService(
                Intent(
                    targetContext,
                    ParkingCueReadService::class.java
                ).setAction(ParkingMonitorService.STOP)
            )
            check(ParkingCueReadService.destroyed.await(3, TimeUnit.SECONDS))
            ParkingCueReadService.release.countDown()
            check(ParkingCueReadService.completed.await(3, TimeUnit.SECONDS))
            val readerField = ParkingMonitorService::class.java.getDeclaredField("reader")
                .apply { isAccessible = true }
            check(
                (readerField.get(service) as java.util.concurrent.ExecutorService).awaitTermination(
                    3,
                    TimeUnit.SECONDS
                )
            )
            waitForIdleSync()
            check(!ParkingMonitorService.running && ParkingCueReadService.published.get() == 1)
            result.putString(
                "stream",
                "PASS: blocked parking cue read leaves Android main thread responsive; three cues coalesce to one worker read and one main-thread publication; second read completing after real service STOP/destruction publishes nothing. Synthetic reader/publication override; actual photo hashing and location notification not exercised.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            ParkingCueReadService.release.countDown()
            targetContext.stopService(intent)
        }
    }
}
