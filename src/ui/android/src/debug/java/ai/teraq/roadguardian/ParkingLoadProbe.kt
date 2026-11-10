package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated slow reader: never opens or modifies the user's parking files. */
class ParkingLoadPreviewActivity : ParkingActivity() {
    companion object {
        var entered = CountDownLatch(1)
        var release = CountDownLatch(1)
        var completed = CountDownLatch(1)
    }

    override fun readSavedRecord(id: String?): ParkingPhotoStore.ReadResult {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        entered.countDown()
        try {
            // Model an I/O operation that completes after cancellation.
            while (release.count > 0) try {
                release.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            return ParkingPhotoStore.ReadResult(null, "STALE_TEST_RESULT")
        } finally {
            completed.countDown()
        }
    }
}

class ParkingLoadProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            ParkingLoadPreviewActivity.entered = CountDownLatch(1)
            ParkingLoadPreviewActivity.release = CountDownLatch(1)
            ParkingLoadPreviewActivity.completed = CountDownLatch(1)
            activity = startActivitySync(
                Intent(
                    targetContext,
                    ParkingLoadPreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            check(ParkingLoadPreviewActivity.entered.await(3, TimeUnit.SECONDS))
            // This executes while the reader is blocked, proving the main thread is available.
            runOnMainSync { callActivityOnPause(activity) }
            ParkingLoadPreviewActivity.release.countDown()
            check(ParkingLoadPreviewActivity.completed.await(3, TimeUnit.SECONDS))
            val field =
                ParkingActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
            val worker = field.get(activity) as java.util.concurrent.ExecutorService
            worker.submit {}.get(3, TimeUnit.SECONDS)
            waitForIdleSync()
            runOnMainSync {
                fun text(view: android.view.View): String = when (view) {
                    is android.widget.TextView -> view.text.toString()
                    is android.view.ViewGroup -> (0 until view.childCount).joinToString {
                        text(
                            view.getChildAt(
                                it
                            )
                        )
                    }

                    else -> ""
                }
                check(!text(activity.findViewById(android.R.id.content)).contains("STALE_TEST_RESULT"))
                activity.finish()
            }
            result.putString(
                "stream",
                "PASS: blocked parking reader leaves main thread responsive; completion after explicit pause is discarded; isolated reader, no saved photo changes.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            ParkingLoadPreviewActivity.release.countDown()
            activity?.let { runOnMainSync { it.finish() } }
        }
    }
}
