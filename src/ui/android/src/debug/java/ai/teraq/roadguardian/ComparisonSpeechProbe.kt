package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject
import java.io.File

/** Actual bundled media playback and controller boundaries, not physical speaker audibility proof. */
class ComparisonSpeechProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var speech: ComparisonSpeech? = null
        try {
            activity = startActivitySync(
                Intent(
                    targetContext,
                    DriverBriefPreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            var allowed = true
            runOnMainSync { speech = ComparisonSpeech(targetContext) { allowed } }
            val voice = speech!!
            check(voice.status == "한국어 안내 준비됨")
            fun waitUntil(test: () -> Boolean): Boolean {
                val end = System.currentTimeMillis() + 10000
                while (System.currentTimeMillis() < end) {
                    var done = false
                    runOnMainSync { done = test() }
                    if (done) return true
                    Thread.sleep(30)
                }
                return false
            }
            runOnMainSync {
                voice.update(1, true, true)
                check(voice.playing)
                voice.update(1, true, false)
                check(voice.playing) { "Fresh-start deadline cut off ongoing playback" }
            }
            check(waitUntil { voice.completions == 1 }) { "Bundled audio did not complete: ${voice.status}" }
            runOnMainSync {
                voice.update(1, true, true)
                voice.update(2, false, true)
                voice.update(2, true, false)
                check(voice.attempts == 1)
            }
            runOnMainSync {
                voice.update(2, true, true)
                check(voice.playing)
                voice.update(2, false, false)
                check(!voice.playing)
            }
            val focus = ComparisonSpeech::class.java.getDeclaredField("focused")
                .apply { isAccessible = true }
            runOnMainSync {
                check(!focus.getBoolean(voice))
                allowed = false
                voice.update(3, true, true)
                check(!voice.playing && voice.starts == 2)
                allowed = true
                voice.update(3, true, true)
                check(!voice.playing) { "Cancelled start replayed" }
            }
            val data = JSONObject().put("source", "bundled fixed synthetic Korean WAV")
                .put("duration_ms", 2191).put("completed_playbacks", voice.completions)
                .put("starts", voice.starts).put("duplicate_suppressed", true)
                .put("stale_start_suppressed", true)
                .put("fresh_start_deadline_does_not_cut_playback", true)
                .put("stop_releases_focus", true).put("late_permission_recheck", true).put(
                "scope",
                "actual Android MediaPlayer start/completion with system audio focus; no physical audibility or real driving claim"
            )
            runOnMainSync {
                voice.close()
                val before = voice.attempts
                voice.update(999, true, true)
                check(voice.attempts == before)
            }
            File(targetContext.filesDir, "comparison-speech-bundled-result-v206.json").writeText(
                data.toString(2)
            )
            result.putString(
                "stream",
                "PASS: bundled audio completed; repeat/stale/cancel/stop/close boundaries and continuing playback past start deadline.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                speech?.close()
                activity?.finish()
            }
        }
    }
}
