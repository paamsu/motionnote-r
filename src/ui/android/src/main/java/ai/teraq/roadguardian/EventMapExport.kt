package ai.teraq.roadguardian

import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 확인된 로컬 참고 지도를 추가한다. 원래 근거 항목은 다시 쓰지 않는다 */
object EventMapExport {
    fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun validate(
        record: JSONObject,
        png: ByteArray,
        id: String,
        hash: String,
        location: JSONObject
    ) {
        require(record.getString("schema") == "road-guardian.event-map.v1")
        require(record.getString("source") == "app_map_render_at_event_coordinates")
        require(record.getString("event_id") == id && record.getString("event_hash") == hash)
        for (key in listOf("lat", "lon")) {
            val value = (record.get(key) as Number).toDouble()
            require(value.isFinite() && value == location.getDouble(key))
            require(value in if (key == "lat") -90.0..90.0 else -180.0..180.0)
        }
        require(record.getDouble("snapshot_created").let { it.isFinite() && it > 0 })
        require(record.getString("address").length in 1..512)
        require(
            record.getString("address_source") in setOf(
                "scenario_fixture",
                "scenario_catalog",
                "android_geocoder",
                "unavailable"
            )
        )
        require(png.size in 8..8 * 1024 * 1024 && sha(png) == record.getString("image_sha256"))
        require(png.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() })
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000)
        requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)).recycle()
    }

    fun bundle(original: File, id: String, filesDir: File): ByteArray {
        EvidencePackageVerifier.verify(original, id)
        ZipFile(original).use { zip ->
            val reportEntry = zip.getEntry("event-report.json") ?: return original.readBytes()
            val report = JSONObject(zip.getInputStream(reportEntry).use { it.readBytes() }
                .toString(Charsets.UTF_8))
            val hash = report.getString("sensor_sha256")
            val target = File(filesDir, "event-map/$id-$hash.json")
            if (!target.exists()) return original.readBytes()
            require(target.length() in 1..12 * 1024 * 1024)
            val record = JSONObject(AtomicFile(target).readFully().toString(Charsets.UTF_8))
            val png = Base64.decode(record.getString("png"), Base64.NO_WRAP)
            val sensor = JSONObject(
                zip.getInputStream(zip.getEntry("sensor-original.json")).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
            )
            validate(record, png, id, hash, sensor.getJSONObject("location"))
            require(zip.getEntry("event-map.json") == null && zip.getEntry("event-map.png") == null)
            record.remove("png")
            record.put("image_path", "event-map.png").put("oem_navigation_capture", false)
                .put("captured_at_event_time", false)
                .put("attribution", "© OpenFreeMap · OpenStreetMap")
            val additions = linkedMapOf(
                "event-map.json" to record.toString().toByteArray(Charsets.UTF_8),
                "event-map.png" to png
            )
            val manifest =
                JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).use { it.readBytes() }
                    .toString(Charsets.UTF_8))
            additions.forEach { (name, bytes) ->
                manifest.getJSONArray("files").put(
                    JSONObject().put("path", name).put("bytes", bytes.size)
                        .put("sha256", sha(bytes))
                )
            }
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { out ->
                zip.entries().asSequence().filter { it.name != "manifest.json" }.forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
                (additions + mapOf(
                    "manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8)
                )).forEach { (name, bytes) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
            val payload = output.toByteArray()
            val checked = File.createTempFile("map-export-", ".zip", original.parentFile)
            try {
                checked.writeBytes(payload)
                EvidencePackageVerifier.verify(checked, id)
            } finally {
                checked.delete()
            }
            return payload
        }
    }
}
