package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Tests actual synthetic download and normal VideoActivity against isolated media faults. No input events. */
class SyntheticIntegrityProbe : Instrumentation() {
    private lateinit var fixture: JSONObject
    private var nightUi = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        nightUi = arguments?.getString("night") == "true"
        fixture = JSONObject(requireNotNull(arguments?.getString("fixture")))
        require(fixture.getString("schema") == "road-guardian.synthetic-integrity-test.v1")
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var host: Activity? = null
        try {
            data class PixelAnchor(val color: Int, val points: List<Pair<Int, Int>>)

            fun cacheFiles() = targetContext.cacheDir.listFiles()
                ?.filter { it.name.startsWith("synthetic-video-") }?.map { it.name }?.toSet()
                ?: emptySet()

            fun views(): List<View> {
                val found = mutableListOf<View>()
                runOnMainSync {
                    fun visit(v: View) {
                        found.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(activity!!.window.decorView)
                }
                return found
            }
            host = startActivitySync(
                Intent(
                    targetContext,
                    AccountDetailPreviewActivity::class.java
                ).putExtra("dossier_fixture", "road").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            waitForIdleSync()
            val rows = JSONArray()
            val cases = fixture.getJSONArray("cases")
            check(cases.length() == 11)
            for (index in (0 until cases.length()).filter {
                !nightUi || cases.getJSONObject(it).getString("mode") in setOf(
                    "normal",
                    "unknown_source"
                )
            }) {
                val case = cases.getJSONObject(index)
                val mode = case.getString("mode")
                val id = case.getString("event_id")
                require(mode.matches(Regex("[a-z_]{1,32}")))
                val baseline = cacheFiles()
                val expected =
                    case.optString("expected_reason").takeIf { it != "null" && it.isNotEmpty() }
                if (expected == null) {
                    val file = SyntheticVideoInput.download(id, targetContext.cacheDir)
                    try {
                        val hash = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(file.readBytes())
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        check(hash == case.getJSONObject("video").getString("sha256"))
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            for ((i, time) in listOf(0L, 5000000L, 9000000L).withIndex()) {
                                val bitmap = checkNotNull(
                                    retriever.getFrameAtTime(
                                        time,
                                        MediaMetadataRetriever.OPTION_CLOSEST
                                    )
                                )
                                File(
                                    targetContext.filesDir,
                                    "integrity-input-$i-v196.png"
                                ).outputStream()
                                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                bitmap.recycle()
                            }
                        } finally {
                            retriever.release()
                        }
                    } finally {
                        file.delete()
                    }
                } else repeat(2) {
                    val failure = runCatching {
                        SyntheticVideoInput.download(
                            id,
                            targetContext.cacheDir
                        )
                    }.exceptionOrNull()
                    check(failure is SyntheticVideoException && failure.reason.name == expected) { "$mode: $failure" }
                    check(cacheFiles() == baseline) { "Partial download retained for $mode" }
                }
                activity = startActivitySync(
                    Intent(targetContext, VideoActivity::class.java).putExtra("event_id", id)
                        .putExtra("light", !nightUi)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                val deadline = android.os.SystemClock.elapsedRealtime() + 15000
                var matched = false
                var label: TextView? = null
                var position = 0
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val all = views()
                    runOnMainSync {
                        val video = all.filterIsInstance<VideoView>().single()
                        if (expected == null) {
                            position = video.currentPosition
                            matched =
                                video.isPlaying && video.visibility == View.VISIBLE && position >= 1500
                        } else {
                            label = all.filterIsInstance<TextView>().firstOrNull {
                                it.text.toString() == "영상을 열지 못했습니다 · " + SyntheticVideoException(
                                    SyntheticVideoException.Reason.valueOf(expected)
                                ).display()
                            }
                            matched =
                                label != null && !video.isPlaying && video.visibility != View.VISIBLE && (video.parent as View).visibility == View.GONE
                        }
                    }
                    var focused = false
                    runOnMainSync { focused = activity!!.hasWindowFocus() }
                    if (matched && focused) break
                    matched = false
                    Thread.sleep(80)
                }
                check(matched) { "VideoActivity outcome missing for $mode" }
                var native: Bitmap? = null
                val frameDeadline = android.os.SystemClock.elapsedRealtime() + 6000
                while (android.os.SystemClock.elapsedRealtime() < frameDeadline) {
                    val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                    var ready = expected == null
                    if (expected != null) {
                        val targets = views().filterIsInstance<TextView>()
                            .filter { it === label || it.text.toString() == "기록으로 돌아가기" }
                        check(targets.size == 2)
                        val anchors = mutableListOf<PixelAnchor>()
                        runOnMainSync {
                            for (v in targets) {
                                val at = IntArray(2)
                                v.getLocationOnScreen(at)
                                val color = v.currentTextColor
                                val points = mutableListOf<Pair<Int, Int>>()
                                val reference =
                                    Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                                v.draw(android.graphics.Canvas(reference))
                                for (y in 0 until reference.height step 2) for (x in 0 until reference.width step 2) if (reference.getPixel(
                                        x,
                                        y
                                    ) == color
                                ) points.add(Pair(at[0] + x, at[1] + y))
                                reference.recycle()
                                anchors.add(PixelAnchor(color, points))
                            }
                        }
                        ready = anchors.all { (color, points) ->
                            val ink = points.count { (x, y) ->
                                x in 0 until bitmap.width && y in 0 until bitmap.height && bitmap.getPixel(
                                    x,
                                    y
                                ) == color
                            }
                            points.size > 10 && ink >= points.size * .98
                        }
                    }
                    if (!targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
                        var darkClock = 0
                        for (y in 45 until 90) for (x in 75 until 225) {
                            val c = bitmap.getPixel(x, y)
                            if (if (nightUi) android.graphics.Color.red(c) > 220 && android.graphics.Color.green(
                                    c
                                ) > 220 && android.graphics.Color.blue(c) > 220 else android.graphics.Color.red(
                                    c
                                ) < 150 && android.graphics.Color.green(c) < 150 && android.graphics.Color.blue(
                                    c
                                ) < 150
                            ) darkClock++
                        }
                        ready = ready && darkClock > 50
                    }
                    if (ready) {
                        native = bitmap
                        break
                    }
                    bitmap.recycle()
                    Thread.sleep(80)
                }
                val bitmap = checkNotNull(native) { "Native error text pixels missing for $mode" }
                File(
                    targetContext.filesDir,
                    "integrity-$mode-${if (nightUi) "night-" else ""}reviewed-v196.png"
                ).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                runOnMainSync { activity!!.finish() }
                activity = null
                waitForIdleSync()
                val cacheDeadline = android.os.SystemClock.elapsedRealtime() + 3000
                while (cacheFiles() != baseline && android.os.SystemClock.elapsedRealtime() < cacheDeadline) Thread.sleep(
                    50
                )
                check(cacheFiles() == baseline) { "VideoActivity retained a new download for $mode" }
                rows.put(
                    JSONObject().put("mode", mode).put("event_id", id)
                        .put("expected_reason", expected).put(
                        "status",
                        if (expected == null) "actual_playback" else "refused_and_frame_hidden"
                    )
                        .put("position_ms", position).put("new_cache_files", 0)
                        .put("direct_taps", false)
                )
            }
            File(
                targetContext.filesDir,
                "integrity-results-${if (nightUi) "night-" else ""}reviewed-v196.json"
            ).writeText(
                JSONObject().put("rows", rows).put(
                    "scope",
                    "actual synthetic input hash/decode/play and ten fault refusals; no general vision or production identity claim"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: ${rows.length()} actual video outcomes; failures downloaded twice; native text/button pixels, clock contrast and cache cleanup checked. No taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            uiAutomation.takeScreenshot()?.let { b ->
                File(targetContext.filesDir, "integrity-failed-reviewed-v196.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                activity?.finish()
                host?.finish()
            }
        }
    }
}
