package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Real framework recreation, isolated synthetic storage, no user photos or UI input automation. */
class ParkingComparisonLifecycleProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun field(name: String) =
        PhotoParkingActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    override fun onStart() {
        var current: Activity? = null
        val result = Bundle()
        try {
            current = startActivitySync(
                Intent(
                    targetContext,
                    ParkingEditPreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            fun awaitReference(activity: Activity) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (true) {
                    var loading = true
                    runOnMainSync { loading = field("referenceLoading").getBoolean(activity) }
                    if (!loading) return
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Reference read timeout" }
                    android.os.SystemClock.sleep(20)
                }
            }
            awaitReference(current!!)
            fun recreate(): Activity {
                val previous = current!!
                val monitor = addMonitor(ParkingEditPreviewActivity::class.java.name, null, false)
                runOnMainSync { previous.recreate() }
                val next = waitForMonitorWithTimeout(monitor, 5000) ?: error("Recreation timeout")
                removeMonitor(monitor)
                current = next
                waitForIdleSync()
                check(next !== previous)
                awaitReference(next)
                return next
            }

            val first = current!!
            val id = field("comparisonId").get(first) as String
            val recordFile = File(first.filesDir, "parking-photos/$id.json")
            val original = recordFile.readText()
            val marker = "COMPARISON_RESULT_FROM_SYNTHETIC_FIXTURE"
            fun seedConfirmed(activity: Activity) {
                val reference = ParkingPhotoStore(activity).comparisonRecord(id).record
                    ?: error("Missing fixture")
                runOnMainSync {
                    field("comparisonShown").setBoolean(activity, true)
                    field("comparedReference").set(activity, reference.toString())
                    field("status").set(activity, marker)
                }
            }
            seedConfirmed(first)
            var next = recreate()
            runOnMainSync {
                check(field("comparisonShown").getBoolean(next)) { "Unchanged reference lost result" }
                check(marker in texts(next.window.decorView)) { "Unchanged result not rendered" }
            }
            recordFile.writeText(JSONObject(original).put("landmark", "25").toString())
            next = recreate()
            runOnMainSync {
                check(!field("comparisonShown").getBoolean(next)) { "Changed reference kept result" }
                val visible = texts(next.window.decorView)
                check(marker !in visible) { "Stale result remains in view tree" }
                check(visible.any { it.contains("주차 기준 기록이 바뀌었습니다") })
            }
            recordFile.writeText(original)
            seedConfirmed(next)
            val photo = File(JSONObject(original).getJSONArray("photos").getString(0))
            val photoBytes = photo.readBytes()
            try {
                photo.appendBytes(byteArrayOf(42))
                next = recreate()
                runOnMainSync {
                    check(!field("comparisonShown").getBoolean(next)) { "Corrupt reference kept result" }
                    val visible = texts(next.window.decorView)
                    check(marker !in visible) { "Corrupt reference still shows success" }
                    check(visible.any { it.contains("선택한 주차 기록 또는 사진을 확인하지 못했습니다") })
                }
            } finally {
                photo.writeBytes(photoBytes)
            }
            result.putString(
                "stream",
                "PASS: actual recreation preserves unchanged comparison; changed reference and corrupt photo clear result before display. Isolated synthetic state; no camera/OCR/user-input flow tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { current?.finish() }
        }
    }
}
