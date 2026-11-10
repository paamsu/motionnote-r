package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import org.json.JSONObject

/** Contest-debug only. Injected app gear state; never actual VHAL/vehicle evidence. */
class HelpVideoRestrictionProbe : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: VideoActivity? = null
        try {
            activity = startActivitySync(
                Intent(targetContext, VideoActivity::class.java)
                    .putExtra("event_id", args.getString("event_id"))
                    .putExtra("help_video_session", args.getString("session_id"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as VideoActivity
            val a = activity
            fun field(n: String) =
                VideoActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            val video = field("video").get(a) as VideoView
            val mask = field("privacyMask").get(a) as View
            val state = field("drivingState").get(a) as DrivingState
            fun waitFor(test: () -> Boolean) {
                repeat(200) {
                    var pass = false
                    runOnMainSync { pass = test() }
                    if (pass) return
                    Thread.sleep(50)
                }
                error("state timeout")
            }

            fun capture(name: String) {
                waitForIdleSync()
                Thread.sleep(500)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "video-restriction-$name.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }

            fun labels(): List<String> {
                val all = mutableListOf<String>()
                fun walk(v: View) {
                    if (v.isShown && v is TextView) all.add(v.text.toString())
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(a.window.decorView)
                return all
            }
            waitFor { video.isPlaying && mask.visibility == View.GONE }
            capture("parked")
            runOnMainSync {
                (field("adapter").get(a) as? PleosAdapter)?.close()
                state.observe("GEAR_DRIVE", SystemClock.elapsedRealtime())
            }
            waitFor {
                mask.visibility == View.VISIBLE && !video.isPlaying && labels().any {
                    it.contains(
                        "영상 정지"
                    )
                }
            }
            capture("restricted")
            val stoppedAt = video.currentPosition
            Thread.sleep(650)
            check(kotlin.math.abs(video.currentPosition - stoppedAt) < 100)
            runOnMainSync { state.observe("GEAR_PARK", SystemClock.elapsedRealtime()) }
            waitFor { mask.visibility == View.GONE }
            check(!video.isPlaying)
            capture("parked-again")
            File(targetContext.filesDir, "video-restriction-result.json").writeText(
                JSONObject()
                    .put("passed", true).put("injected_app_gear", true)
                    .put("actual_vhal_changed", false)
                    .put("mask_visible_and_playback_stopped", true)
                    .put("resume_requires_user", true)
                    .put(
                        "scope",
                        "Contest-debug VideoActivity on parked Pleos emulator with injected app gear state. Not actual driving or OEM gear transition."
                    ).toString(2)
            )
            result.putString(
                "result",
                "PASS app gear injection masks and pauses help video; no automatic restart"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("error", t.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
