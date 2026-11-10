package ai.teraq.roadguardian

import org.json.JSONObject
import org.json.JSONArray
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.security.MessageDigest
import android.graphics.BitmapFactory

/** 저장된 합성 사진을 오프라인으로 분석한다 */
object ScenePhotoAnalysis {
    private val hash = Regex("[0-9a-f]{64}")
    fun present(event: JSONObject) = event.has("scene_analysis") && !event.isNull("scene_analysis")
    fun checked(event: JSONObject): JSONObject? {
        val report = event.optJSONObject("scene_analysis") ?: return null
        val photo = event.optJSONObject("video")?.optJSONObject("input_photo") ?: return null
        if (event.optJSONObject("video")?.opt("integrity_valid") != true) return null
        if (event.optString("source") != "synthetic" || event.opt("evidence_valid") != true || event.optString(
                "status"
            ) == "dismissed"
        ) return null
        if (report.optString("schema") != "road-guardian.local-scene-analysis.v1" || report.optString(
                "source"
            ) != "local_photo_inference" || report.opt("integrity_valid") != true || report.opt("camera_evidence") != false || report.opt(
                "visual_analysis_performed"
            ) != true
        ) return null
        if (report.optString("event_id") != event.optString("id") || report.optString("sensor_sha256") != event.optString(
                "hash"
            ) || photo.opt("integrity_valid") != true || photo.optString("event_id") != event.optString(
                "id"
            ) || photo.optString("sensor_sha256") != event.optString("hash") || report.optString("input_photo_sha256") != photo.optString(
                "sha256"
            )
        ) return null
        if (listOf(
                "sensor_sha256",
                "input_photo_sha256",
                "model_identity_sha256",
                "prompt_sha256",
                "model_input_sha256"
            ).any { !hash.matches(report.optString(it)) }
        ) return null
        val kind = report.optString("class")
        if (kind !in setOf(
                "pothole",
                "raised_bump",
                "other",
                "unclear"
            ) || report.optString("status") != if (kind == "unclear") "abstained" else "candidate"
        ) return null
        try {
            verifyMetadata(
                event.getString("id"),
                event.getString("hash"),
                photo.getString("sha256"),
                report
            )
        } catch (_: Exception) {
            return null
        }
        return report
    }

    fun summary(event: JSONObject): EventBriefState.Summary? {
        if (!present(event) || event.optJSONObject("assessment")
                ?.optString("kind") !in setOf("pothole", "observation")
        ) return null
        val report =
            checked(event) ?: return EventBriefState.Summary("사진 판독 확인 필요", "판독 근거를 확인해 주세요")
        val kind = report.getString("class")
        if (kind == "unclear") {
            val assessment = event.optJSONObject("assessment")
            val road = assessment?.optJSONObject("road_assessment")
            val peak = (assessment?.optJSONObject("metrics")
                ?.opt("peak_vertical_g") as? Number)?.toDouble()
            if (road?.optString("source") == "declared_surface_and_impact" && road.opt("visual_analysis_performed") == false &&
                peak != null && peak.isFinite() && peak > 1.5
            )
                return EventBriefState.Summary("도로 충격 기록", "사진 판독 보류 · 원인 확인 필요")
            return EventBriefState.Summary("사진 판독 보류", "노면을 확인하기 어렵습니다")
        }
        val surface = event.optJSONObject("assessment")?.optJSONObject("road_assessment")
            ?.optString("surface")
        if ((surface == "depression" && kind != "pothole") || (surface == "raised" && kind != "raised_bump")) return EventBriefState.Summary(
            "노면 확인 필요",
            "사진과 입력 정보가 다릅니다"
        )
        return when (kind) {
            "pothole" -> EventBriefState.Summary("포트홀 후보 확인", "사진 판독 기록 보관")
            "raised_bump" -> EventBriefState.Summary("방지턱 후보 확인", "사진 판독 기록 보관")
            else -> EventBriefState.Summary("사진 판독 완료", "파손·방지턱 후보 없음")
        }
    }

    fun detail(event: JSONObject): String {
        val report = checked(event) ?: return "사진 판독 근거 확인 필요"
        val label = when (report.getString("class")) {
            "pothole" -> "포트홀 후보"
            "raised_bump" -> "방지턱 후보"
            "other" -> "파손·방지턱 후보 없음"
            else -> "판독 보류"
        }
        return "사진 판독: $label"
    }

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

    private fun rawResult(raw: String): Pair<String, String>? = try {
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val values = mutableMapOf<String, String>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(
                    key in setOf(
                        "class",
                        "reason"
                    ) && key !in values && reader.peek() == JsonToken.STRING
                )
                values[key] = reader.nextString()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && values.size == 2)
            val kind = checkNotNull(values["class"])
            val reason = checkNotNull(values["reason"])
            require(
                kind in setOf(
                    "pothole",
                    "raised_bump",
                    "other",
                    "unclear"
                ) && reason.isNotBlank() && reason.length <= 1000
            )
            kind to reason
        }
    } catch (_: Exception) {
        null
    }

    fun verifyArchive(
        eventId: String,
        sensorHash: String,
        photoHash: String,
        reported: JSONObject,
        stored: JSONObject,
        modelInput: ByteArray
    ) {
        require(canonical(reported) == canonical(stored))
        verifyMetadata(eventId, sensorHash, photoHash, stored)
        require(modelInput.size in 1..4 * 1024 * 1024 && stored.getLong("model_input_bytes") == modelInput.size.toLong())
        require(digest(modelInput) == stored.getString("model_input_sha256"))
        val dims = stored.getJSONObject("result").getJSONArray("model_input_dimensions")
        require(dims.length() == 2)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(modelInput, 0, modelInput.size, options)
        require(
            options.outMimeType == "image/png" && options.outWidth in 1..768 && options.outHeight in 1..768 && dims.getInt(
                0
            ) == options.outWidth && dims.getInt(1) == options.outHeight
        )
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(modelInput, 0, modelInput.size))
        bitmap.recycle()
    }

    private fun verifyMetadata(
        eventId: String,
        sensorHash: String,
        photoHash: String,
        stored: JSONObject
    ) {
        require(
            stored.getString("schema") == "road-guardian.local-scene-analysis.v1" && stored.getString(
                "source"
            ) == "local_photo_inference"
        )
        require(
            stored.getString("event_id") == eventId && stored.getString("sensor_sha256") == sensorHash && stored.getString(
                "input_photo_sha256"
            ) == photoHash
        )
        require(
            stored.get("integrity_valid") == true && stored.get("camera_evidence") == false && stored.get(
                "visual_analysis_performed"
            ) == true
        )
        for (key in listOf(
            "model_identity_sha256",
            "prompt_sha256",
            "model_input_sha256",
            "raw_output_sha256"
        )) require(hash.matches(stored.getString(key)))
        val prompt = stored.getString("prompt")
        val raw = stored.getString("raw_output")
        require(prompt.toByteArray(Charsets.UTF_8).size in 1..16384 && raw.toByteArray(Charsets.UTF_8).size in 1..32768)
        require(
            digest(prompt.toByteArray(Charsets.UTF_8)) == stored.getString("prompt_sha256") && digest(
                raw.toByteArray(Charsets.UTF_8)
            ) == stored.getString("raw_output_sha256")
        )
        val parsed = rawResult(raw)
        val kind = stored.getString("class")
        val reason = stored.getString("reason")
        require(
            kind in setOf(
                "pothole",
                "raised_bump",
                "other",
                "unclear"
            ) && stored.getString("status") == if (kind == "unclear") "abstained" else "candidate"
        )
        if (stored.get("format_valid") == true) require(parsed == (kind to reason))
        else require(stored.get("format_valid") == false && parsed == null && kind == "unclear" && reason == "Model output did not satisfy the fixed JSON contract.")
        val result = stored.getJSONObject("result")
        for (key in listOf(
            "class",
            "status",
            "reason",
            "format_valid",
            "camera_evidence",
            "visual_analysis_performed",
            "model_identity_sha256",
            "prompt_sha256",
            "model_input_sha256",
            "transform"
        )) require(result.get(key) == stored.get(key))
        require(result.getString("input_sha256") == photoHash && result.get("max_tokens") == 256)
        val temperature = result.get("temperature")
        require(temperature is Number && temperature.toDouble() == 0.0)
        val elapsed = result.get("elapsed_seconds")
        require(
            elapsed is Number && elapsed.toDouble().isFinite() && elapsed.toDouble() in 0.0..3600.0
        )
        val chunks = result.get("stream_chunks")
        require(chunks is Int && chunks in 1..4096)
        val bytes = stored.get("model_input_bytes")
        require(bytes is Int && bytes in 1..4 * 1024 * 1024)
        for ((key, limit) in listOf(
            "original_dimensions" to 4096,
            "model_input_dimensions" to 768
        )) {
            val dims = result.getJSONArray(key)
            require(dims.length() == 2)
            for (i in 0..1) {
                val n = dims.get(i)
                require(n is Int && n in 1..limit)
            }
        }
        require(stored.getString("transform") == "EXIF display orientation; RGB; aspect-preserving LANCZOS thumbnail max768; metadata stripped")
    }
}
