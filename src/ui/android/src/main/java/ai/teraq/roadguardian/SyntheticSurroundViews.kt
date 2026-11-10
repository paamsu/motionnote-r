package ai.teraq.roadguardian

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.security.MessageDigest

/** 작성된 PNG 네 장. 카메라 촬영이나 방향 추정은 하지 않는다 */
object SyntheticSurroundViews {
    fun metadata(container: JSONObject): JSONObject? =
        if (container.has("surround_views") && !container.isNull("surround_views")) container.getJSONObject(
            "surround_views"
        ) else null

    val directions = listOf("front", "rear", "left", "right")
    val archivePaths = directions.map { "surround/$it.png" }.toSet()
    fun validate(
        meta: JSONObject,
        eventId: String,
        sensorHash: String,
        complete: Boolean = false
    ): List<JSONObject> {
        require(meta.getString("schema") == "road-guardian.synthetic-surround-views.v1")
        require(meta.getString("event_id") == eventId && meta.getString("sensor_sha256") == sensorHash)
        require(meta.getString("source") == "synthetic" && meta.get("camera_evidence") == false)
        val ordered = meta.getJSONArray("directions")
        require(ordered.length() == 4 && directions.indices.all { ordered.get(it) == directions[it] })
        val array = meta.getJSONArray("views")
        val rows = (0 until array.length()).map { array.getJSONObject(it) }
        val names = rows.map { it.getString("direction") }
        require(names == directions.filter { it in names } && names.size == names.toSet().size)
        val missing = meta.getJSONArray("missing_directions")
        require((0 until missing.length()).map { missing.get(it) } == directions.filter { it !in names })
        when (meta.getString("status")) {
            "complete" -> require(rows.size == 4 && meta.get("integrity_valid") == true)
            "partial" -> require(rows.size in 1..3 && meta.get("integrity_valid") == true && !complete)
            "unavailable" -> require(rows.isEmpty() && meta.get("integrity_valid") == false && !complete)
            else -> error("Unknown surround status")
        }
        if (complete) require(meta.getString("status") == "complete")
        require(rows.map { it.getString("sha256") }.toSet().size == rows.size)
        rows.forEach { row ->
            val direction = row.getString("direction")
            require(
                row.getString("url") == "/api/events/$eventId/surround-views/$direction" && row.getString(
                    "mime"
                ) == "image/png"
            )
            require(row.getString("sha256").matches(Regex("[0-9a-f]{64}")))
            for (key in listOf("bytes", "width", "height")) {
                val n = row.get(key)
                require(n is Int || n is Long)
                val value = (n as Number).toLong()
                require(value in if (key == "bytes") 1L..8L * 1024 * 1024 else 2L..4096L)
            }
        }
        return rows
    }

    fun decode(row: JSONObject, bytes: ByteArray, maxDimension: Int = 4096): Bitmap {
        require(
            bytes.size == row.getInt("bytes") && MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) } == row.getString("sha256")
        )
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        require(bytes.size >= 8 && signature.indices.all { bytes[it] == signature[it] })
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(
            bounds.outMimeType == "image/png" && bounds.outWidth == row.getInt("width") && bounds.outHeight == row.getInt(
                "height"
            )
        )
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return requireNotNull(
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                options
            )
        ).also { bitmap ->
            if (sample == 1 && (bitmap.width != row.getInt("width") || bitmap.height != row.getInt("height"))) {
                bitmap.recycle()
                error("Decoded image dimensions differ")
            }
        }
    }

    fun same(first: List<JSONObject>, second: List<JSONObject>) =
        first.size == second.size && first.indices.all { i ->
            listOf(
                "direction",
                "sha256",
                "bytes",
                "mime",
                "width",
                "height",
                "url"
            ).all { key -> first[i].get(key) == second[i].get(key) }
        }
}
