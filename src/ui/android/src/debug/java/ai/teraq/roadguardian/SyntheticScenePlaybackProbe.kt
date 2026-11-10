package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Actual local HTTP/download/hash/Android decode/VideoActivity presentation; never injects UI input. */
class SyntheticScenePlaybackProbe : Instrumentation() {
    private var ids = emptyList<String>()
    private var corruptId: String? = null
    private var tag = "v191"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        ids = arguments?.getString("event_ids")?.split(",") ?: emptyList()
        corruptId = arguments?.getString("corrupt_id")
        tag = arguments?.getString("evidence_tag") ?: "v191"
        require(tag in setOf("v191", "v192", "v199"))
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            check(ids.size in 1..2 && ids.all { it.matches(Regex("[0-9a-f-]{36}")) })
            val reports = JSONArray()
            for (id in ids) {
                val event = JSONObject(BackendClient().request("/api/events/$id"))
                val video = event.getJSONObject("video")
                check(event.getBoolean("evidence_valid") && video.getBoolean("integrity_valid"))
                check(
                    video.getString("source") == "synthetic_scene_fixture" && !video.getBoolean("visual_analysis_performed") && !video.getBoolean(
                        "camera_evidence"
                    )
                )
                val url =
                    BackendEndpoint.resolve(BuildConfig.BACKEND_URL, "/api/events/$id/video", true)
                val connection = URL(url).openConnection() as HttpURLConnection
                val file = File(targetContext.cacheDir, "scene-$id-$tag.mp4")
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 10000
                    check(connection.responseCode == 200 && connection.contentType.startsWith("video/mp4"))
                    file.outputStream().use { output ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(65536)
                            var total = 0
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                check(total <= 16 * 1024 * 1024)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                check(hash == video.getString("sha256") && file.length() == video.getLong("bytes"))
                val scene = video.getString("scene")
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    check(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toInt() in 9500..10500
                    )
                    for ((index, at) in listOf(0L, 5000000L, 9000000L).withIndex()) {
                        val bitmap = checkNotNull(
                            retriever.getFrameAtTime(
                                at,
                                MediaMetadataRetriever.OPTION_CLOSEST
                            )
                        )
                        check(
                            bitmap.width in setOf(
                                640,
                                1280
                            ) && bitmap.width * 9 == bitmap.height * 16
                        )
                        File(
                            targetContext.filesDir,
                            "scene-$scene-frame-$index-$tag.png"
                        ).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                } finally {
                    retriever.release()
                }
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        VideoActivity::class.java
                    ).putExtra("event_id", id)
                        .putExtra("light", video.getString("scene") != "speed_bump")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                var position = -1
                var bounds = Rect()
                var failure: Throwable? = null
                var diagnostic = ""
                val deadline = android.os.SystemClock.elapsedRealtime() + 12000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    runOnMainSync {
                        try {
                            fun find(v: View): VideoView? {
                                if (v is VideoView) return v
                                if (v is ViewGroup) for (i in 0 until v.childCount) find(
                                    v.getChildAt(
                                        i
                                    )
                                )?.let { return it }
                                return null
                            }

                            val actual = checkNotNull(find(screen!!.window.decorView))
                            diagnostic =
                                "playing=${actual.isPlaying}, position=${actual.currentPosition}, visible=${actual.visibility}, size=${actual.width}x${actual.height}"
                            if (actual.isPlaying && actual.visibility == View.VISIBLE && actual.currentPosition > 3500) {
                                position = actual.currentPosition
                                check(actual.getGlobalVisibleRect(bounds))
                                val onScreen = IntArray(2)
                                val inWindow = IntArray(2)
                                actual.getLocationOnScreen(onScreen)
                                actual.getLocationInWindow(inWindow)
                                bounds.offset(onScreen[0] - inWindow[0], onScreen[1] - inWindow[1])
                            }
                        } catch (t: Throwable) {
                            failure = t
                        }
                    }
                    failure?.let { throw it }
                    if (position > 3500) break
                    Thread.sleep(100)
                }
                check(position > 3500) { "Actual VideoActivity did not play under current SDK/UX state: $diagnostic" }
                val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "scene-$scene-playing-$tag.png").outputStream()
                    .use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
                reports.put(
                    JSONObject().put("scene", scene).put("event_id", id).put("clip_sha256", hash)
                        .put(
                            "input_photo_sha256",
                            video.getJSONObject("input_photo").getString("sha256")
                        )
                        .put("position_ms", position).put(
                            "video_bounds",
                            JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
                        )
                        .put("actual_VideoActivity", true).put("camera_or_vision_verified", false)
                        .put("taps", false)
                )
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            corruptId?.let { id ->
                check(id.matches(Regex("[0-9a-f-]{36}")))
                val connection = URL(
                    BackendEndpoint.resolve(
                        BuildConfig.BACKEND_URL,
                        "/api/events/$id/video",
                        true
                    )
                ).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    check(connection.responseCode == 400)
                    connection.errorStream?.close()
                } finally {
                    connection.disconnect()
                }
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        VideoActivity::class.java
                    ).putExtra("event_id", id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                var blocked = false
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    runOnMainSync {
                        var errorLabel = false
                        var visibleVideo = false
                        fun collect(v: View) {
                            if (v is android.widget.TextView && v.text.toString()
                                    .contains("영상을 열지 못했습니다")
                            ) errorLabel = true
                            if (v is VideoView && (v.isPlaying || v.visibility == View.VISIBLE)) visibleVideo =
                                true
                            if (v is ViewGroup) for (i in 0 until v.childCount) collect(
                                v.getChildAt(
                                    i
                                )
                            )
                        }
                        collect(screen!!.window.decorView)
                        blocked = errorLabel && !visibleVideo
                    }
                    if (blocked) break
                    Thread.sleep(100)
                }
                check(blocked) { "Corrupt input did not hide video and show an error" }
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "scene-corrupt-$tag.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                reports.put(
                    JSONObject().put("scene", "corrupt").put("http_status", 400)
                        .put("visible_playback", false).put("error_label", true).put("taps", false)
                )
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            File(
                targetContext.filesDir,
                "scene-playback-result-$tag.json"
            ).writeText(reports.toString(2))
            result.putString(
                "stream",
                "PASS: declared scene clips downloaded/hash checked, Android decoded at 0/5/9 seconds and actual VideoActivity played; optional corrupt source checked. Automotive uses real SDK/UX. Playback only, no vision/temporal inference or taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.filesDir, "scene-playback-failure-$tag.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
