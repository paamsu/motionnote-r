package ai.teraq.roadguardian

import android.graphics.BitmapFactory
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.security.MessageDigest

/** 저장된 영상 관측값. 작성된 센서 판정과 섞지 않는다 */
object VideoVisibilityAnalysis {
    val paths = (0..5).map { "video-visibility/frame-%04d.png".format(it) }.toSet()
    private val states = setOf(
        "obscuration_increasing",
        "obscuration_clearing",
        "obscuration_then_clearing",
        "stable",
        "illumination_change",
        "unclear"
    )
    private val hash = Regex("[0-9a-f]{64}")
    fun present(event: JSONObject) =
        event.has("video_visibility_analysis") && !event.isNull("video_visibility_analysis")

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

    private fun rawResult(raw: String): Map<String, String>? = try {
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val fields = linkedMapOf<String, String>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(
                    key in setOf(
                        "visibility_change",
                        "reason"
                    ) && key !in fields && reader.peek() == JsonToken.STRING
                )
                fields[key] = reader.nextString()
            }
            reader.endObject()
            require(
                reader.peek() == JsonToken.END_DOCUMENT && fields.keys == setOf(
                    "visibility_change",
                    "reason"
                ) && fields["visibility_change"] in states && fields.getValue("reason")
                    .trim().length in 1..1000
            )
            fields
        }
    } catch (_: Exception) {
        null
    }

    private fun verify(eventId: String, sensorHash: String, videoHash: String, report: JSONObject) {
        require(
            report.getString("schema") == "road-guardian.video-visibility-analysis.v1" && report.getString(
                "source"
            ) == "local_video_visibility_inference"
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
        require(report.get("camera_evidence") == false && report.get("weather_cause_inferred") == false)
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
        val state = report.getString("visibility_change")
        require(state in states)
        require(report.getString("status") == if (state == "unclear") "abstained" else "visual_observation")
        val parsed = rawResult(raw)
        if (report.get("format_valid") == true) require(
            parsed == mapOf(
                "visibility_change" to state,
                "reason" to report.getString("reason")
            )
        )
        else require(
            report.get("format_valid") == false && parsed == null && state == "unclear" && report.getString(
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
                "visibility_change",
                "reason",
                "format_valid",
                "weather_cause_inferred",
                "stream_chunks",
                "elapsed_seconds"
            )
        )
        require(Regex("[0-9a-f]{32}").matches(result.getString("id")))
        for (key in listOf(
            "visibility_change",
            "reason",
            "status",
            "format_valid",
            "camera_evidence",
            "weather_cause_inferred",
            "visual_analysis_performed",
            "temporal_analysis_performed",
            "model_identity_sha256",
            "prompt_sha256",
            "raw_output_sha256"
        )) require(result.get(key) == report.get(key))
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
                frame.getString("path") == "video-visibility/frame-%04d.png".format(i) && frame.getInt(
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
        require(video.get("integrity_valid") == true)
        val report = event.getJSONObject("video_visibility_analysis")
        verify(event.getString("id"), event.getString("hash"), video.getString("sha256"), report)
        report
    } catch (_: Exception) {
        null
    }

    fun detail(event: JSONObject): String {
        val report = checked(event) ?: return "영상 판독 기록 확인 실패"
        val label = when (report.getString("visibility_change")) {
            "obscuration_increasing" -> "가림 증가로 판독"
            "obscuration_clearing" -> "가림 감소로 판독"
            "obscuration_then_clearing" -> "가림 발생 후 감소로 판독"
            "stable" -> "가림 변화 없음으로 판독"
            "illumination_change" -> "조명 변화로 판독"
            else -> "영상 판독 보류"
        }
        return label
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
