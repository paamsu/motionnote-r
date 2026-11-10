package ai.teraq.roadguardian

import android.graphics.BitmapFactory
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.security.MessageDigest

/** 저장된 영상 관측값. 작성된 센서 판정과 섞지 않는다 */
object VideoCollisionAnalysis {
    val paths = (0..5).map { "video-collision/frame-%04d.png".format(it) }.toSet()
    private val states = setOf("contact", "separated", "unclear")
    private val motions =
        setOf("approach_contact", "separating", "pass_without_contact", "static", "unclear")
    private val hash = Regex("[0-9a-f]{64}")
    fun present(event: JSONObject) =
        event.has("video_collision_analysis") && !event.isNull("video_collision_analysis")

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
                        "class",
                        "reason",
                        "motion",
                        "motion_reason"
                    ) && key !in fields && reader.peek() == JsonToken.STRING
                )
                fields[key] = reader.nextString()
            }
            reader.endObject()
            require(
                reader.peek() == JsonToken.END_DOCUMENT && fields.keys == setOf(
                    "class",
                    "reason",
                    "motion",
                    "motion_reason"
                ) && fields["class"] in states && fields["motion"] in motions && fields.getValue("motion_reason")
                    .trim().length in 1..1000 && fields.getValue("reason").trim().length in 1..1000
            )
            fields
        }
    } catch (_: Exception) {
        null
    }

    private fun verify(eventId: String, sensorHash: String, videoHash: String, report: JSONObject) {
        require(
            report.getString("schema") == "road-guardian.video-collision-analysis.v1" && report.getString(
                "source"
            ) == "local_video_collision_inference"
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
        require(report.get("camera_evidence") == false && report.get("physical_contact_confirmed") == false)
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
        val state = report.getString("contact_class")
        val motion = report.getString("motion")
        require(state in states && motion in motions && report.get("requires_review") == true)
        require(report.getString("status") == if (state == "unclear" || motion == "unclear") "abstained" else "candidate")
        val parsed = rawResult(raw)
        if (report.get("format_valid") == true) require(
            parsed == mapOf(
                "class" to state,
                "reason" to report.getString("reason"),
                "motion" to motion,
                "motion_reason" to report.getString("motion_reason")
            )
        )
        else require(
            report.get("format_valid") == false && parsed == null && state == "unclear" && motion == "unclear" && report.getString(
                "reason"
            ) == "Model output did not satisfy the fixed JSON contract." && report.getString("motion_reason") == "Invalid output contract."
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
                "class",
                "reason",
                "motion",
                "motion_reason",
                "format_valid",
                "stream_chunks",
                "elapsed_seconds"
            )
        )
        require(Regex("[0-9a-f]{32}").matches(result.getString("id")))
        for (key in listOf(
            "motion",
            "motion_reason",
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
        require(result.getString("class") == state)
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
                frame.getString("path") == "video-collision/frame-%04d.png".format(i) && frame.getInt(
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
            video.get("integrity_valid") == true && video.getString("input_kind") == "authored_collision_scene" && video.get(
                "camera_evidence"
            ) == false && video.getString("sha256") == video.getString("original_sha256")
        )
        val report = event.getJSONObject("video_collision_analysis")
        verify(event.getString("id"), event.getString("hash"), video.getString("sha256"), report)
        report
    } catch (_: Exception) {
        null
    }

    fun detail(event: JSONObject): String {
        val report = checked(event) ?: return "영상 판독 기록 확인 실패"
        val label = when (report.getString("contact_class")) {
            "contact" -> "접촉으로 판독"
            "separated" -> "비접촉으로 판독"
            else -> "접촉 판독 보류"
        }
        val motion = when (report.getString("motion")) {
            "approach_contact" -> "접근 후 접촉 후보"
            "separating" -> "떨어짐 후보"
            "pass_without_contact" -> "옆으로 통과 후보"
            "static" -> "변화 없음으로 판독"
            else -> "움직임 판독 보류"
        }
        return "$label · $motion"
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
