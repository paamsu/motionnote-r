package ai.teraq.roadguardian

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Actual downloaded photo bundle plus independent semantic changes, including re-hashed manifests. */
object PhotoPackageChecks {
    fun run(
        context: Context,
        client: AuthenticatedEventsClient,
        id: String,
        photo: CasePhoto
    ): Int {
        val temporary = mutableListOf<File>()
        try {
            val exported = client.evidencePackage(id)
            temporary.add(exported)
            val original = ZipFile(exported).use { zip ->
                zip.entries().asSequence().associate { entry ->
                    entry.name to zip.getInputStream(entry).use { it.readBytes() }
                }
            }
            check(
                JSONObject(original.getValue("manifest.json").toString(Charsets.UTF_8)).getString(
                    "schema"
                ) == "road-guardian.owner-package.v2"
            )
            val image = "photos/${photo.id}.jpg"
            check(CasePhoto.hash(original.getValue(image)) == photo.sha256)
            check(EvidencePackageVerifier.verify(exported, id).files == 5)
            fun edit(
                files: MutableMap<String, ByteArray>,
                name: String,
                change: (JSONObject) -> Unit
            ) {
                val value = JSONObject(files.getValue(name).toString(Charsets.UTF_8))
                change(value)
                files[name] = value.toString().toByteArray(Charsets.UTF_8)
            }

            fun changed(name: String, change: (JSONObject) -> Unit) =
                original.toMutableMap().also { edit(it, name, change) }

            fun archive(files: MutableMap<String, ByteArray>): File {
                val manifest = JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
                val entries = JSONArray()
                files.filterKeys { it != "manifest.json" }.toSortedMap().forEach { (name, bytes) ->
                    entries.put(
                        JSONObject().put("path", name).put("bytes", bytes.size)
                            .put("sha256", CasePhoto.hash(bytes))
                    )
                }
                manifest.put("files", entries)
                files["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
                val file = File.createTempFile("photo-bundle-check-", ".zip", context.cacheDir)
                temporary.add(file)
                ZipOutputStream(file.outputStream()).use { zip ->
                    files.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                return file
            }

            var rejected = 0
            fun reject(files: MutableMap<String, ByteArray>, eventId: String = id) {
                val failure = runCatching {
                    EvidencePackageVerifier.verify(
                        archive(files),
                        eventId
                    )
                }.exceptionOrNull()
                check(failure is AccountAccessException && failure.reason == AccountAccessException.Reason.INTEGRITY)
                rejected++
            }
            reject(original.toMutableMap(), UUID.randomUUID().toString())
            reject(changed("photo-index.json") { it.put("event_id", UUID.randomUUID().toString()) })
            reject(changed("photo-index.json") { it.put("scope", "all_subjects") })
            reject(changed("photo-index.json") {
                it.getJSONArray("photos").getJSONObject(0).put("path", "../escape.jpg")
            })
            reject(changed("photo-index.json") {
                it.getJSONArray("photos").getJSONObject(0).put("sensor_original", true)
            })
            reject(changed("photo-index.json") {
                it.getJSONArray("photos").getJSONObject(0).put("width", 1)
            })
            reject(changed("owner-report.json") { it.put("photo_count", 0) })
            reject(changed("owner-report.json") {
                it.put(
                    "private_photo_status",
                    "feature_disabled"
                )
            })
            reject(changed("manifest.json") { it.put("schema", "road-guardian.owner-package.v1") })
            reject(original.toMutableMap().apply { remove(image) })
            reject(
                original.toMutableMap()
                    .apply { put("photos/${UUID.randomUUID()}.jpg", getValue(image)) })
            reject(original.toMutableMap().apply { put(image, "changed".toByteArray()) })
            val invalidImage =
                original.toMutableMap().apply { put(image, "not-jpeg".toByteArray()) }
            edit(invalidImage, "photo-index.json") {
                it.getJSONArray("photos").getJSONObject(0).put("bytes", 8)
                    .put("sha256", CasePhoto.hash("not-jpeg".toByteArray()))
            }
            reject(invalidImage)
            val duplicate = changed("photo-index.json") {
                it.getJSONArray("photos").put(it.getJSONArray("photos").getJSONObject(0))
            }
            edit(duplicate, "owner-report.json") { it.put("photo_count", 2) }
            reject(duplicate)
            fun withoutPhotos() =
                original.filterKeys { it != "photo-index.json" && !it.startsWith("photos/") }
                    .toMutableMap()

            val legacy = withoutPhotos()
            edit(legacy, "owner-report.json") {
                it.put("schema", "road-guardian.owner-export.v1")
                for (key in listOf(
                    "photo_count",
                    "photo_index_path",
                    "private_photo_status"
                )) it.remove(key)
            }
            edit(legacy, "manifest.json") { it.put("schema", "road-guardian.owner-package.v1") }
            check(EvidencePackageVerifier.verify(archive(legacy), id).files == 3)
            val disabled = withoutPhotos()
            edit(disabled, "owner-report.json") {
                it.put("private_photo_status", "feature_disabled").put("photo_count", 0)
                    .put("photo_index_path", JSONObject.NULL)
            }
            check(EvidencePackageVerifier.verify(archive(disabled), id).files == 3)
            val empty = original.toMutableMap().apply { remove(image) }
            edit(empty, "photo-index.json") { it.put("photos", JSONArray()) }
            edit(empty, "owner-report.json") { it.put("photo_count", 0) }
            check(EvidencePackageVerifier.verify(archive(empty), id).files == 4)
            exported.copyTo(
                File(context.filesDir, "photo-verified-package-v161.zip"),
                overwrite = true
            )
            return rejected
        } finally {
            temporary.forEach { it.delete() }
        }
    }
}
