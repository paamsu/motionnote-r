package ai.teraq.roadguardian

import android.graphics.BitmapFactory
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.security.MessageDigest

/** 저장된 영상 관측값. 작성된 센서 판정과 섞지 않는다 */
object VideoLaneAnalysis {
    val paths = (0..5).map { "video-lane/frame-%04d.png".format(it) }.toSet()
    private val states = setOf("repeated_lane_changes", "lane_stable", "unclear")
    private val hash = Regex("[0-9a-f]{64}")
    fun present(event: JSONObject) =
        event.has("video_lane_analysis") && !event.isNull("video_lane_analysis")

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }

        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
            canonical(
                value.get(
                    it
                )
            )
        }

        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun keys(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected)
    }

    private fun dimensions(value: JSONArray, width: Int, height: Int) {
        require(value.length() == 2 && value.get(0) is Int && value.get(1) is Int)
        require(value.getInt(0) in 1..width && value.getInt(1) in 1..height)
    }

    private fun number(value: Any): Double {
        require(value is Number)
        return value.toDouble().also { require(it.isFinite()) }
    }

    private fun rawResult(raw: String): Map<String, Any>? = try {
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val values = linkedMapOf<String, Any>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(key in setOf("observation", "visible_changes", "reason") && key !in values)
                values[key] = if (key == "visible_changes") {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        JSONObject.NULL
                    } else {
                        require(reader.peek() == JsonToken.NUMBER)
                        reader.nextString().toInt()
                    }
                } else {
                    require(reader.peek() == JsonToken.STRING)
                    reader.nextString()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && values.size == 3)
            require(values["observation"] in states && (values["reason"] as String).trim().length in 1..1000)
            val n = values["visible_changes"]
            require(n == JSONObject.NULL || (n is Int && n in 0..5))
            require(values["observation"] != "lane_stable" || n == 0)
            require(values["observation"] != "repeated_lane_changes" || (n is Int && n >= 3))
            values
        }
    } catch (_: Exception) {
        null
    }

    private fun verify(eventId: String, sensorHash: String, videoHash: String, report: JSONObject) {
        require(
            report.getString("schema") == "road-guardian.video-lane-analysis.v1" && report.getString(
                "source"
            ) == "local_video_lane_inference"
        )
        require(
            report.getString("event_id") == eventId && report.getString("sensor_sha256") == sensorHash && report.getString(
                "input_video_sha256"
            ) == videoHash
        )
        for (key in listOf(
            "integrity_valid",
            "visual_analysis_performed",
            "temporal_analysis_performed"
        )) require(report.get(key) == true)
        require(report.get("camera_evidence") == false && report.get("reckless_intent_confirmed") == false)
        for (key in listOf(
            "sensor_sha256",
            "input_video_sha256",
            "model_identity_sha256",
            "prompt_sha256",
            "raw_output_sha256"
        )) require(hash.matches(report.getString(key)))
        val raw = report.getString("raw_output")
        val prompt = report.getString("prompt")
        require(raw.toByteArray().size in 1..32768 && prompt.toByteArray().size in 1..16384)
        require(
            digest(raw.toByteArray()) == report.getString("raw_output_sha256") && digest(prompt.toByteArray()) == report.getString(
                "prompt_sha256"
            )
        )
        val state = report.getString("observation")
        val count = report.get("visible_changes")
        require(state in states && report.get("requires_review") == true)
        require(count == JSONObject.NULL || (count is Int && count in 0..5))
        require(state != "lane_stable" || count == 0)
        require(state != "repeated_lane_changes" || (count is Int && count >= 3))
        require(report.getString("status") == if (state == "unclear") "abstained" else "candidate")
        val parsed = rawResult(raw)
        if (report.get("format_valid") == true) require(
            parsed == mapOf(
                "observation" to state,
                "visible_changes" to count,
                "reason" to report.getString("reason")
            )
        )
        else require(
            report.get("format_valid") == false && parsed == null && state == "unclear" && count == JSONObject.NULL && report.getString(
                "reason"
            ) == "Output failed the fixed JSON contract."
        )
        val result = report.getJSONObject("result")
        keys(
            result,
            setOf(
                "id",
                "input_sha256",
                "visual_analysis_performed",
                "temporal_analysis_performed",
                "camera_evidence",
                "model_identity_sha256",
                "prompt_sha256",
                "max_tokens",
                "temperature",
                "video_frames",
                "raw_output_sha256",
                "status",
                "observation",
                "visible_changes",
                "reason",
                "formatted_prompt_sha256",
                "format_valid",
                "stream_chunks",
                "elapsed_seconds"
            )
        )
        require(Regex("[0-9a-f]{32}").matches(result.getString("id")))
        for (key in listOf(
            "observation",
            "visible_changes",
            "formatted_prompt_sha256",
            "reason",
            "status",
            "format_valid",
            "camera_evidence",
            "visual_analysis_performed",
            "temporal_analysis_performed",
            "model_identity_sha256",
            "prompt_sha256",
            "raw_output_sha256"
        )) require(result.get(key) == report.get(key))
        require(hash.matches(report.getString("formatted_prompt_sha256")))
        require(result.getString("input_sha256") == videoHash && result.get("max_tokens") == 256)
        require((result.get("temperature") as? Number)?.toDouble() == 0.0)
        val elapsed = result.get("elapsed_seconds")
        require(
            elapsed is Number && elapsed.toDouble().isFinite() && elapsed.toDouble() in 0.0..3600.0
        )
        require(result.get("stream_chunks") is Int && result.getInt("stream_chunks") in 1..4096)
        val source = result.getJSONObject("video_frames")
        val sourceFrames = source.getJSONArray("frames")
        val frames = report.getJSONArray("frames")
        keys(
            source,
            setOf(
                "all_frame_pts_seconds",
                "frame_count",
                "original_dimensions",
                "full_decode_verified",
                "selection",
                "transform",
                "frames"
            )
        )
        dimensions(source.getJSONArray("original_dimensions"), 1920, 1080)
        require(source.get("frame_count") is Int)
        require(
            source.getString("transform") == "decoded RGB; LANCZOS aspect thumbnail max384; metadata stripped" && source.getString(
                "selection"
            ) == "round(i*(N-1)/5), i=0..5; chronological"
        )
        require(sourceFrames.length() == 6 && frames.length() == 6 && source.get("full_decode_verified") == true)
        val pts = source.getJSONArray("all_frame_pts_seconds")
        require(pts.length() in 6..1024 && source.getInt("frame_count") == pts.length())
        var prior = -1.0
        for (i in 0 until pts.length()) {
            val t = number(pts.get(i))
            require(t in 0.0..16.0 && t > prior)
            prior = t
        }
        require(pts.getDouble(0) == 0.0)
        require(pts.getDouble(pts.length() - 1) - pts.getDouble(0) in 0.000001..16.0)
        for (i in 0..5) {
            val frame = frames.getJSONObject(i)
            val original = sourceFrames.getJSONObject(i)
            keys(original, setOf("index", "pts_seconds", "path", "sha256", "dimensions"))
            keys(frame, setOf("index", "pts_seconds", "path", "sha256", "bytes", "dimensions"))
            dimensions(original.getJSONArray("dimensions"), 384, 384)
            dimensions(frame.getJSONArray("dimensions"), 384, 384)
            require(frame.get("index") is Int && original.get("index") is Int && frame.get("bytes") is Int)
            val index = Math.rint(i * (pts.length() - 1) / 5.0).toInt()
            require(
                frame.getString("path") == "video-lane/frame-%04d.png".format(i) && frame.getInt(
                    "index"
                ) == index && original.getInt("index") == index
            )
            require(
                original.getString("path") == "${result.getString("id")}-frame-%04d.png".format(
                    index
                )
            )
            require(
                number(frame.get("pts_seconds")) == pts.getDouble(index) && number(frame.get("pts_seconds")) == number(
                    original.get("pts_seconds")
                )
            )
            require(
                hash.matches(frame.getString("sha256")) && frame.getString("sha256") == original.getString(
                    "sha256"
                )
            )
            require(
                frame.getInt("bytes") in 1..1024 * 1024 && canonical(frame.getJSONArray("dimensions")) == canonical(
                    original.getJSONArray("dimensions")
                )
            )
        }
    }

    fun checked(event: JSONObject): JSONObject? = try {
        require(
            event.getString("source") == "synthetic" && event.get("evidence_valid") == true && event.optString(
                "status"
            ) != "dismissed"
        )
        val video = event.getJSONObject("video")
        require(
            video.get("integrity_valid") == true && video.getString("input_kind") == "authored_lane_scene" && video.get(
                "camera_evidence"
            ) == false && video.getString("sha256") == video.getString("original_sha256")
        )
        val report = event.getJSONObject("video_lane_analysis")
        verify(event.getString("id"), event.getString("hash"), video.getString("sha256"), report)
        report
    } catch (_: Exception) {
        null
    }

    fun repeatedCandidate(event: JSONObject): Boolean =
        event.optJSONObject("assessment")?.optString("kind") == "observation" &&
                checked(event)?.optString("observation") == "repeated_lane_changes"

    fun detail(event: JSONObject): String {
        val report = checked(event) ?: return "영상 판독 기록 확인 실패"
        return when (report.getString("observation")) {
            "repeated_lane_changes" -> "차선 이동 ${report.getInt("visible_changes")}회 후보"
            "lane_stable" -> "선택 시점에서 차선 이동 없음"
            else -> "차선 판독 보류"
        }
    }

    fun summary(event: JSONObject): EventBriefState.Summary? {
        if (!present(event) || event.optJSONObject("assessment")
                ?.optString("kind") != "observation"
        ) return null
        val report =
            checked(event) ?: return EventBriefState.Summary("차선 판독 확인 필요", "기록 근거를 확인해 주세요")
        return when (report.getString("observation")) {
            "repeated_lane_changes" -> EventBriefState.Summary("차선 변경 기록 확인", "영상 기록 보관")
            "lane_stable" -> EventBriefState.Summary("주행 관측 기록", "선택 시점에서 반복 이동 없음")
            else -> EventBriefState.Summary("차선 판독 보류", "기록 확보 · 추가 확인 필요")
        }
    }

    fun verifyArchive(
        eventId: String,
        sensorHash: String,
        videoHash: String,
        reported: JSONObject,
        stored: JSONObject,
        blobs: Map<String, ByteArray>
    ) {
        require(canonical(reported) == canonical(stored) && blobs.keys == paths)
        verify(eventId, sensorHash, videoHash, stored)
        for (i in 0..5) {
            val f = stored.getJSONArray("frames").getJSONObject(i)
            val bytes = blobs.getValue(f.getString("path"))
            require(bytes.size == f.getInt("bytes") && digest(bytes) == f.getString("sha256"))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val dimensions = f.getJSONArray("dimensions")
            require(
                dimensions.length() == 2 && bounds.outMimeType == "image/png" && bounds.outWidth in 1..384 && bounds.outHeight in 1..384 && dimensions.getInt(
                    0
                ) == bounds.outWidth && dimensions.getInt(1) == bounds.outHeight
            )
            checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)).recycle()
        }
    }
}
