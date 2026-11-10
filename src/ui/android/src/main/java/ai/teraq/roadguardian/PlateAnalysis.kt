package ai.teraq.roadguardian

import org.json.JSONObject
import org.json.JSONArray
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import android.graphics.BitmapFactory
import java.security.MessageDigest

/** 저장된 합성 관측만 다룬다. 실시간 번호판 인식은 하지 않는다 */
object PlateAnalysis {
    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }

        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun strictJson(raw: String): JSONObject {
        fun read(r: JsonReader, depth: Int = 0): Any {
            require(depth <= 16)
            return when (r.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val o = JSONObject()
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        require(!o.has(k))
                        o.put(k, read(r, depth + 1))
                    }
                    r.endObject()
                    o
                }

                JsonToken.BEGIN_ARRAY -> {
                    val a = JSONArray()
                    r.beginArray()
                    while (r.hasNext()) {
                        require(a.length() < 256)
                        a.put(read(r, depth + 1))
                    }
                    r.endArray()
                    a
                }

                JsonToken.STRING -> r.nextString()
                JsonToken.NUMBER -> {
                    val n = r.nextString()
                    require(n.length < 32)
                    n.toLongOrNull() ?: n.toDouble().also { require(it.isFinite()) }
                }

                JsonToken.BOOLEAN -> r.nextBoolean()
                JsonToken.NULL -> {
                    r.nextNull()
                    JSONObject.NULL
                }

                else -> error("Invalid JSON")
            }
        }
        return JsonReader(StringReader(raw)).use { r ->
            r.isLenient = false
            val obj = read(r) as JSONObject
            require(r.peek() == JsonToken.END_DOCUMENT)
            obj
        }
    }

    private fun observationHash(v: JSONObject): String {
        val ascii = buildString {
            for (c in canonical(v)) {
                if (c.code > 127) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        return digest(ascii.toByteArray(Charsets.UTF_8))
    }

    fun present(event: JSONObject) = event.has("plate_analysis") && !event.isNull("plate_analysis")
    fun checked(event: JSONObject): JSONObject? = try {
        val p = event.getJSONObject("plate_analysis")
        val photo = event.getJSONObject("video").getJSONObject("input_photo")
        require(
            event.getString("source") == "synthetic" && event.get("evidence_valid") == true && event.getJSONObject(
                "video"
            ).get("integrity_valid") == true && photo.get("integrity_valid") == true
        )
        require(
            photo.getString("event_id") == event.getString("id") && photo.getString("sensor_sha256") == event.getString(
                "hash"
            )
        )
        metadata(event.getString("id"), event.getString("hash"), photo.getString("sha256"), p)
        p
    } catch (_: Exception) {
        null
    }

    fun candidateTexts(event: JSONObject): List<String> {
        val rows =
            checked(event)?.getJSONObject("agreement")?.getJSONArray("rows") ?: return emptyList()
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.getString("status") == "corroborated_text_candidate" }
            .map { it.getString("observed_text") }
    }

    fun detail(event: JSONObject): String {
        val p = checked(event) ?: return "번호판 판독 근거 확인 필요"
        val rows = p.getJSONObject("agreement").getJSONArray("rows")
        val count = (0 until rows.length()).count {
            rows.getJSONObject(it).getString("status") == "corroborated_text_candidate"
        }
        val held = rows.length() - count
        return when {
            count > 0 && held > 0 -> "문자 일치 ${count}건 · 판독 보류 ${held}건"
            count > 0 -> "번호 문자 $count 건 일치 · 검토 필요"
            else -> "번호판 판독 보류"
        }
    }

    internal fun metadata(id: String, sensor: String, photo: String, p: JSONObject) {
        require(p.getString("schema") == "road-guardian.plate-analysis.v1" && p.getString("source") == "local_synthetic_plate_analysis")
        require(
            p.getString("event_id") == id && p.getString("sensor_sha256") == sensor && p.getString(
                "input_photo_sha256"
            ) == photo
        )
        require(
            p.get("integrity_valid") == true && p.get("requires_review") == true && p.get("camera_evidence") == false && p.has(
                "recipient"
            ) && p.isNull("recipient")
        )
        val b = p.getJSONObject("bundle")
        val loc = b.getJSONObject("localization")
        val ocr = b.getJSONObject("ocr")
        val a = p.getJSONObject("agreement")
        require(
            loc.getString("input_sha256") == photo && ocr.getString("input_sha256") == photo && a.getString(
                "input_sha256"
            ) == photo
        )
        require(loc.get("visual_analysis_performed") == true && loc.get("camera_evidence") == false)
        val korean = b.has("agreement_profile")
        if (korean) require(
            b.getString("agreement_profile") == "synthetic-korean-text-v2" && a.getString(
                "profile"
            ) == "synthetic-korean-text-v2"
        )
        else require(!a.has("profile"))
        require(a.getString("schema") == if (korean) "road-guardian.plate-text-agreement.v2" else "road-guardian.plate-text-agreement.v1")
        require(
            a.get("requires_review") == true && a.get("vehicle_identification_performed") == false && a.get(
                "real_watchlist_accessed"
            ) == false && a.get("camera_evidence") == false && a.has("recipient") && a.isNull("recipient")
        )
        for (key in listOf("model_identity_sha256", "prompt_sha256", "model_input_sha256")) require(
            loc.getString(key).matches(Regex("[0-9a-f]{64}"))
        )
        require(
            a.getString("localization_sha256") == observationHash(loc) && a.getString("ocr_sha256") == observationHash(
                ocr
            )
        )
        val prompt = b.getString("prompt")
        val raw = b.getString("raw_output")
        require(prompt.toByteArray().size in 1..16384 && raw.toByteArray().size in 1..32768)
        require(digest(prompt.toByteArray()) == loc.getString("prompt_sha256"))
        require(p.getString("model_input_sha256") == loc.getString("model_input_sha256"))
        val plates = loc.getJSONArray("plates")
        val rows = a.getJSONArray("rows")
        require(plates.length() in 0..12 && rows.length() == plates.length())
        if (loc.get("format_valid") == true) {
            var clean = raw.trim()
            if (clean.startsWith("```json\n") && clean.endsWith("\n```")) clean =
                clean.substring(8, clean.length - 4)
            val parsed = strictJson(clean)
            require(
                parsed.length() == 1 && canonical(parsed.getJSONArray("plates")) == canonical(
                    plates
                )
            )
        } else require(plates.length() == 0)
        val dims = loc.getJSONArray("original_dimensions")
        require(dims.length() == 2)
        require((0..1).all { dims.get(it) is Int })
        val width = dims.getInt(0)
        val height = dims.getInt(1)
        require(width in 1..4096 && height in 1..4096)
        fun box(a: JSONArray, maxX: Int, maxY: Int): List<Double> {
            require(a.length() == 4)
            val n = (0..3).map {
                val v = a.get(it)
                require(v is Int || v is Long);
                (v as Number).toDouble()
            }
            require(n[0] >= 0 && n[1] >= 0 && n[0] < n[2] && n[1] < n[3] && n[2] <= maxX && n[3] <= maxY)
            return n
        }

        val boxes = (0 until plates.length()).map { i ->
            val b = box(plates.getJSONObject(i).getJSONArray("box"), 1000, 1000)
            listOf(
                b[0] * width / 1000,
                b[1] * height / 1000,
                b[2] * width / 1000,
                b[3] * height / 1000
            )
        }

        fun overlap(a: List<Double>, b: List<Double>) =
            a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

        val lines = ocr.getJSONArray("lines")
        require(lines.length() in 0..128)
        val checkedLines = (0 until lines.length()).map { j ->
            val line = lines.getJSONObject(j)
            val text = line.get("text")
            require(text is String && text.length in 1..1000)
            text to if (!line.has("bounding_box") || line.isNull("bounding_box")) null else box(
                line.getJSONArray(
                    "bounding_box"
                ), width, height
            )
        }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.get("index") is Int && row.getInt("index") == i)
            val plate = plates.getJSONObject(i)
            val text = if (plate.isNull("text")) null else plate.get("text")
                .also { require(it is String && it.length <= 80) } as String
            require(row.get("observed_text") == plate.get("text"))
            val region = boxes[i]
            val declared = row.getJSONArray("box_original")
            require(declared.length() == 4 && (0..3).all { k ->
                declared.get(k) is Number && kotlin.math.abs(
                    declared.getDouble(k) - region[k]
                ) < 0.000001
            })
            val selected = mutableListOf<String>()
            var reason: String? = when {
                loc.get("format_valid") != true || loc.get("visual_analysis_performed") != true -> "localization_unavailable"
                checkedLines.any { it.second == null } -> "ocr_geometry_missing"
                boxes.indices.any { j ->
                    j != i && overlap(
                        region,
                        boxes[j]
                    )
                } -> "overlapping_plate_regions"

                text == null -> "model_text_unreadable"
                else -> null
            }
            if (reason == null) {
                for ((value, bounds) in checkedLines) {
                    val line = checkNotNull(bounds)
                    if (!overlap(region, line)) continue
                    if (!(region[0] <= line[0] && region[1] <= line[1] && line[2] <= region[2] && line[3] <= region[3])) {
                        reason = "ocr_crosses_plate_boundary"
                        break
                    }
                    selected.add(value)
                }
                if (reason == null) reason = when {
                    selected.size != 1 -> "single_ocr_line_required"
                    selected[0] != text -> "text_disagreement"
                    !(selected[0].matches(Regex("DEMO-[0-9]{3,4}")) ||
                            (korean && selected[0].matches(Regex("[0-9]{2,3}[가-힣] ?[0-9]{4}")))) ->
                        if (korean) "incomplete_or_unsupported_synthetic_text" else "synthetic_token_required"

                    (0 until plates.length()).count {
                        plates.getJSONObject(it).optString("text") == text
                    } != 1 -> "duplicate_plate_text"

                    else -> null
                }
            }
            require(row.getString("status") == if (reason == null) "corroborated_text_candidate" else "inconclusive")
            require(row.getString("reason") == (reason ?: "same_region_exact_text_agreement"))
        }
    }

    fun verifyArchive(
        id: String,
        sensor: String,
        photo: String,
        reported: JSONObject,
        stored: JSONObject,
        input: ByteArray
    ) {
        require(canonical(reported) == canonical(stored))
        metadata(id, sensor, photo, stored)
        require(
            input.size in 1..8 * 1024 * 1024 && stored.getLong("model_input_bytes") == input.size.toLong() && digest(
                input
            ) == stored.getString("model_input_sha256")
        )
        val dims = stored.getJSONObject("bundle").getJSONObject("localization")
            .getJSONArray("model_input_dimensions")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, options)
        require(
            options.outMimeType == "image/png" && options.outWidth in 1..1536 && options.outHeight in 1..1536 && dims.length() == 2 && dims.getInt(
                0
            ) == options.outWidth && dims.getInt(1) == options.outHeight
        )
        checkNotNull(BitmapFactory.decodeByteArray(input, 0, input.size)).recycle()
    }
}
