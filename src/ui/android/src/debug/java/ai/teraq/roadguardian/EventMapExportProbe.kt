package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EventMapExportProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val root =
            File(targetContext.cacheDir, "map-export-probe-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val saved = JSONObject(File(targetContext.filesDir, "event-map-record.json").readText())
            val id = UUID.randomUUID().toString()
            val sensor = JSONObject().put("source", "synthetic").put(
                "location",
                JSONObject().put("lat", saved.getDouble("lat")).put("lon", saved.getDouble("lon"))
            ).toString().toByteArray()
            val hash = EventMapExport.sha(sensor)
            val report = JSONObject().put("schema", "road-guardian.export.v1").put("id", id)
                .put("source", "synthetic").put("external_submission", false)
                .put("sensor_sha256", hash).toString().toByteArray()
            val originalEntries = linkedMapOf(
                "sensor-original.json" to sensor,
                "event-report.json" to report,
                "README.txt" to "Synthetic export probe".toByteArray()
            )
            val manifest =
                JSONObject().put("schema", "road-guardian.package.v1").put("event_id", id)
                    .put("source", "synthetic").put("external_submission", false).put(
                    "files",
                    JSONArray().apply {
                        originalEntries.forEach { (name, bytes) ->
                            put(
                                JSONObject().put(
                                    "path",
                                    name
                                ).put("bytes", bytes.size).put("sha256", EventMapExport.sha(bytes))
                            )
                        }
                    })
            val original = File(root, "original.zip")
            ZipOutputStream(original.outputStream()).use { out ->
                (originalEntries + mapOf(
                    "manifest.json" to manifest.toString().toByteArray()
                )).forEach { (name, bytes) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
            var checks = 0
            fun verify(value: Boolean, label: String) {
                check(value) { label }
                checks++
            }
            verify(
                EventMapExport.bundle(original, id, root).contentEquals(original.readBytes()),
                "absent map preserves original ZIP bytes"
            )
            val record = JSONObject(saved.toString()).put("event_id", id).put("event_hash", hash)
            val local = File(root, "event-map/$id-$hash.json").apply {
                parentFile!!.mkdirs()
                writeText(record.toString())
            }
            val localBytes = local.readBytes()
            val originalBytes = original.readBytes()
            val bundled = EventMapExport.bundle(original, id, root)
            val output = File(
                targetContext.filesDir,
                "event-map-export-v355.zip"
            ).apply { writeBytes(bundled) }
            verify(
                EvidencePackageVerifier.verify(output, id).files == 5,
                "extended package verifies"
            )
            ZipFile(output).use { zip ->
                originalEntries.forEach { (name, bytes) ->
                    verify(
                        zip.getInputStream(
                            zip.getEntry(
                                name
                            )
                        ).use { it.readBytes() }.contentEquals(bytes), "original $name preserved"
                    )
                }
                val metadata = JSONObject(
                    zip.getInputStream(zip.getEntry("event-map.json")).use { it.readBytes() }
                        .toString(Charsets.UTF_8)
                )
                verify(
                    metadata.get("oem_navigation_capture") == false && metadata.get("captured_at_event_time") == false,
                    "capture provenance"
                )
                verify(
                    EventMapExport.sha(
                        zip.getInputStream(zip.getEntry("event-map.png"))
                            .use { it.readBytes() }) == record.getString("image_sha256"),
                    "map PNG unchanged"
                )
            }
            verify(
                original.readBytes().contentEquals(originalBytes) && local.readBytes()
                    .contentEquals(localBytes), "source files unchanged"
            )
            for ((field, value) in listOf(
                "event_id" to UUID.randomUUID().toString(),
                "event_hash" to "a".repeat(64),
                "lat" to 0.0,
                "lon" to 0.0,
                "image_sha256" to "b".repeat(64),
                "source" to "oem_navigation_capture",
                "snapshot_created" to -1,
                "address_source" to "live_sensor",
                "png" to "AAAA"
            )) {
                local.writeText(JSONObject(record.toString()).put(field, value).toString())
                verify(
                    runCatching { EventMapExport.bundle(original, id, root) }.isFailure,
                    "reject $field"
                )
            }
            val evidence = JSONObject().put("passed", checks).put(
                "scope",
                "Isolated synthetic archive and saved emulator map; no SAF document picker or external submission"
            ).put("archive", output.name)
            File(
                targetContext.filesDir,
                "event-map-export-result.json"
            ).writeText(evidence.toString(2))
            result.putString(
                "stream",
                "PASS: $checks map export preservation and rejection checks\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            root.deleteRecursively()
        }
    }
}
