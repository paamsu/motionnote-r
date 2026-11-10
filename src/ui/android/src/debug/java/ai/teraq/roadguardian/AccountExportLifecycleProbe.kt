package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises framework recreation with a delayed synthetic job; no login or storage is faked as real. */
class AccountExportLifecycleProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val message = "Synthetic export completed"
        val task = FutureTask<String> {
            calls.incrementAndGet()
            check(release.await(10, TimeUnit.SECONDS))
            message
        }
        var current: Activity? = null
        val taskField = AccountEventsActivity::class.java.getDeclaredField("exportTask")
            .apply { isAccessible = true }
        val busyField = AccountEventsActivity::class.java.getDeclaredField("exporting")
            .apply { isAccessible = true }
        val messageField = AccountEventsActivity::class.java.getDeclaredField("exportMessage")
            .apply { isAccessible = true }
        try {
            val first = startActivitySync(
                Intent(
                    targetContext,
                    AccountEventsActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            current = first
            Thread(task, "export-lifecycle-fixture").start()
            runOnMainSync {
                taskField.set(first, task)
                busyField.setBoolean(first, true)
            }
            val monitor = addMonitor(AccountEventsActivity::class.java.name, null, false)
            runOnMainSync { first.recreate() }
            val second =
                waitForMonitorWithTimeout(monitor, 5000) ?: error("Recreated activity not observed")
            removeMonitor(monitor)
            current = second
            waitForIdleSync()
            check(first !== second) { "Activity was not replaced" }
            runOnMainSync {
                check(taskField.get(second) === task) { "Export task not retained" }
                check(busyField.getBoolean(second)) { "Running state lost" }
            }
            check(!task.isCancelled) { "Recreation cancelled export" }
            release.countDown()
            check(task.get(3, TimeUnit.SECONDS) == message)
            Thread.sleep(500)
            waitForIdleSync()
            runOnMainSync {
                check(taskField.get(second) == null) { "Completed task not consumed" }
                check(!busyField.getBoolean(second)) { "Busy state stuck" }
                check(messageField.get(second) == message) { "Completion message lost" }
            }
            check(calls.get() == 1) { "Export duplicated" }
            result.putString(
                "stream",
                "PASS: real Activity recreation retained one delayed synthetic export, did not cancel or duplicate it, and consumed completion. No authenticated network or document-provider write tested.\n"
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
