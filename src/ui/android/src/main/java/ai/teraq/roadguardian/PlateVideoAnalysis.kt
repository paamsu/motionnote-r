package ai.teraq.roadguardian

import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

/** 띄엄띄엄 저장된 합성 관측. 차량 신원 확인이나 연속 추적은 하지 않는다 */
object PlateVideoAnalysis {
    fun resource(name: String) =
        name.matches(Regex("plate-video/(frame|model)-0[0-9]{3}\\.png")) && name.substringAfterLast(
            '-'
        ).substringBefore('.').toInt() < 960

    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }

        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun present(e: JSONObject) = e.has("plate_video_analysis") && !e.isNull("plate_video_analysis")
    private fun single(p: JSONObject, r: JSONObject) =
        JSONObject().put("schema", "road-guardian.plate-analysis.v1")
            .put("source", "local_synthetic_plate_analysis")
            .put("event_id", p.getString("event_id"))
            .put("sensor_sha256", p.getString("sensor_sha256"))
            .put("input_photo_sha256", r.getJSONObject("frame").getString("sha256"))
            .put("bundle", r.getJSONObject("bundle")).put("agreement", r.getJSONObject("agreement"))
            .put("model_input_sha256", r.getJSONObject("model_input").getString("sha256"))
            .put("model_input_bytes", r.getJSONObject("model_input").getLong("bytes"))
            .put("integrity_valid", true).put("requires_review", true).put("camera_evidence", false)
            .put("recipient", JSONObject.NULL)

    fun metadata(id: String, sensor: String, video: JSONObject, p: JSONObject): Set<String> {
        require(p.getString("schema") == "road-guardian.plate-video-analysis.v1" && p.getString("source") == "local_synthetic_plate_video_analysis")
        require(p.getString("event_id") == id && p.getString("sensor_sha256") == sensor)
        require(
            p.get("integrity_valid") == true && p.get("requires_review") == true && p.get("camera_evidence") == false && p.has(
                "recipient"
            ) && p.isNull("recipient")
        )
        require(video.getString("input_kind") == "authored_moving_plate_scene" && video.get("integrity_valid") == true)
        require(
            p.getString("input_video_sha256") == video.getString("sha256") && video.getString("original_sha256") == video.getString(
                "sha256"
            )
        )
        require(
            p.get("input_video_bytes") is Number && p.getLong("input_video_bytes") == video.getLong(
                "bytes"
            )
        )
        val timeline = p.getJSONObject("timeline")
        val rows = p.getJSONArray("frames")
        val observed = timeline.getJSONArray("observations")
        require(
            timeline.getString("schema") == "road-guardian.plate-video-review.v1" && timeline.getString(
                "video_sha256"
            ) == video.getString("sha256")
        )
        require(timeline.get("decoded_frame_count") is Int && timeline.getInt("decoded_frame_count") in 1..960)
        require(
            listOf(
                "vehicle_identity_verified",
                "cross_frame_text_fusion",
                "watchlist_accessed"
            ).all { timeline.get(it) == false } && timeline.get("requires_review") == true)
        require(rows.length() in 1..24 && rows.length() == observed.length())
        var previous = -1
        var previousTime = -1.0
        var profile: String? = null
        val paths = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val observation = observed.getJSONObject(i)
            require(row.get("index") is Int)
            val index = row.getInt("index")
            require(index > previous && index < timeline.getInt("decoded_frame_count"))
            previous = index
            require(row.get("t") is Number)
            val t = row.getDouble("t")
            require(t.isFinite() && t in 0.0..16.0 && t > previousTime)
            previousTime = t
            require(
                observation.get("index") is Int && observation.getInt("index") == index && observation.get(
                    "t"
                ) is Number && observation.getDouble("t") == t
            )
            require(
                canonical(observation.getJSONObject("agreement")) == canonical(
                    row.getJSONObject(
                        "agreement"
                    )
                )
            )
            val current = row.getJSONObject("bundle").optString("agreement_profile", "legacy")
            require(profile == null || profile == current)
            profile = current
            for ((key, prefix) in listOf("frame" to "frame", "model_input" to "model")) {
                val meta = row.getJSONObject(key)
                val name = "plate-video/$prefix-${index.toString().padStart(4, '0')}.png"
                require(
                    meta.getString("path") == name && meta.getString("sha256")
                        .matches(Regex("[0-9a-f]{64}"))
                )
                require(meta.get("bytes") is Int || meta.get("bytes") is Long)
                require(meta.getLong("bytes") in 1..8L * 1024 * 1024)
                require(paths.add(name))
            }
            PlateAnalysis.metadata(
                id,
                sensor,
                row.getJSONObject("frame").getString("sha256"),
                single(p, row)
            )
        }
        return paths
    }

    fun checked(e: JSONObject): JSONObject? = try {
        require(e.getString("source") == "synthetic" && e.get("evidence_valid") == true)
        e.getJSONObject("plate_video_analysis")
            .also { metadata(e.getString("id"), e.getString("hash"), e.getJSONObject("video"), it) }
    } catch (_: Exception) {
        null
    }

    fun detail(e: JSONObject): String {
        val p = checked(e) ?: return "영상 판독 근거 확인 필요"
        val frames = p.getJSONArray("frames")
        val held = (0 until frames.length()).count { i ->
            val rows = frames.getJSONObject(i).getJSONObject("agreement").getJSONArray("rows");
            (0 until rows.length()).any {
                rows.getJSONObject(it).getString("status") == "inconclusive"
            }
        }
        return "선택 ${frames.length()}시점 · 판독 보류 ${held}시점"
    }

    fun verifyArchive(
        id: String,
        sensor: String,
        video: JSONObject,
        reported: JSONObject,
        stored: JSONObject,
        resources: Map<String, ByteArray>
    ) {
        require(canonical(reported) == canonical(stored))
        require(metadata(id, sensor, video, stored) == resources.keys)
        val frames = stored.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val row = frames.getJSONObject(i)
            for (key in listOf("frame", "model_input")) {
                val meta = row.getJSONObject(key)
                val bytes = checkNotNull(resources[meta.getString("path")])
                require(
                    bytes.size.toLong() == meta.getLong("bytes") && sha(bytes) == meta.getString(
                        "sha256"
                    )
                )
                val bounds =
                    android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..1920 && bounds.outHeight in 1..1080)
                checkNotNull(
                    android.graphics.BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size
                    )
                ).recycle()
            }
            val photo = single(stored, row)
            PlateAnalysis.verifyArchive(
                id,
                sensor,
                row.getJSONObject("frame").getString("sha256"),
                photo,
                photo,
                checkNotNull(resources[row.getJSONObject("model_input").getString("path")])
            )
        }
    }

    fun verifyVideoTimeline(p: JSONObject, file: java.io.File) {
        val extractor = android.media.MediaExtractor()
        val times = mutableListOf<Long>()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it)
                    .getString(android.media.MediaFormat.KEY_MIME) == "video/avc"
            }
            val format = extractor.getTrackFormat(track)
            val width = format.getInteger(android.media.MediaFormat.KEY_WIDTH)
            val height = format.getInteger(android.media.MediaFormat.KEY_HEIGHT)
            require(width in 1..1920 && height in 1..1080)
            extractor.selectTrack(track)
            do {
                val at = extractor.sampleTime
                if (at < 0) break
                require(times.size < 960)
                times.add(at)
            } while (extractor.advance())
            times.sort()
            require(times.size == p.getJSONObject("timeline").getInt("decoded_frame_count"))
            require(times.all { it in 0..16_000_000L } && times.zipWithNext()
                .all { it.first < it.second })
            val rows = p.getJSONArray("frames")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                require(kotlin.math.abs(times[row.getInt("index")] - row.getDouble("t") * 1_000_000) <= 1.1)
                val dims = row.getJSONObject("bundle").getJSONObject("localization")
                    .getJSONArray("original_dimensions")
                require(dims.getInt(0) == width && dims.getInt(1) == height)
            }
        } finally {
            extractor.release()
        }
    }

}
