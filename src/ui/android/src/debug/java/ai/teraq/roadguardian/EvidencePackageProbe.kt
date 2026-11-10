package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises the production verifier against deliberately altered private test archives. */
class EvidencePackageProbe : Instrumentation() {
    private var fixtureDir: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fixtureDir = arguments?.getString("fixture_dir")
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val files = mutableListOf<File>()
        var rejected = 0
        var valid = 0
        try {
            val id = UUID.randomUUID().toString()
            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            fun data(): MutableMap<String, ByteArray> {
                val raw = "{\"source\":\"synthetic\",\"samples\":[1,2,3]}".toByteArray()
                val report = JSONObject().put("schema", "road-guardian.export.v1").put("id", id)
                    .put("source", "synthetic")
                    .put("external_submission", false).put("sensor_sha256", sha(raw))
                return linkedMapOf(
                    "sensor-original.json" to raw,
                    "event-report.json" to report.toString().toByteArray(),
                    "README.txt" to "Synthetic test only".toByteArray()
                )
            }

            fun edit(
                data: MutableMap<String, ByteArray>,
                name: String,
                change: (JSONObject) -> Unit
            ) {
                val json = JSONObject(checkNotNull(data[name]).toString(Charsets.UTF_8))
                change(json)
                data[name] = json.toString().toByteArray()
            }

            fun archive(
                data: MutableMap<String, ByteArray>,
                changeManifest: (JSONObject) -> Unit = {},
                afterManifest: (MutableMap<String, ByteArray>) -> Unit = {}
            ): File {
                val list = JSONArray()
                data.forEach { (name, bytes) ->
                    list.put(
                        JSONObject().put("path", name).put("bytes", bytes.size)
                            .put("sha256", sha(bytes))
                    )
                }
                val manifest =
                    JSONObject().put("schema", "road-guardian.package.v1").put("event_id", id)
                        .put("source", "synthetic")
                        .put("external_submission", false).put("files", list)
                changeManifest(manifest)
                data["manifest.json"] = manifest.toString().toByteArray()
                afterManifest(data)
                val file = File.createTempFile("verifier-probe-", ".zip", targetContext.cacheDir)
                files.add(file)
                ZipOutputStream(file.outputStream()).use { zip ->
                    data.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                return file
            }

            fun accept(data: MutableMap<String, ByteArray>) {
                check(EvidencePackageVerifier.verify(archive(data), id).eventId == id)
                valid++
            }

            fun reject(file: File, event: String = id) {
                val failure =
                    runCatching { EvidencePackageVerifier.verify(file, event) }.exceptionOrNull()
                check(failure is AccountAccessException && failure.reason == AccountAccessException.Reason.INTEGRITY)
                rejected++
            }
            accept(data())
            val rich = data()
            rich["repair-follow-up-original.json"] = "{\"remeasurement\":true}".toByteArray()
            rich["follow-up-original.json"] = "{\"followup\":true}".toByteArray()
            rich["test-pattern-video.mp4"] = byteArrayOf(1, 2, 3, 4)
            rich["private-review-note.json"] =
                JSONObject().put("schema", "road-guardian.private-review-note.v1")
                    .put("event_id", id)
                    .put("scope", "current_subject_only").put("evidence_statement", false)
                    .put("revision", 1).put("note", "시험 메모").toString().toByteArray()
            edit(rich, "event-report.json") {
                it.put(
                    "repair",
                    JSONObject().put(
                        "post_repair",
                        JSONObject().put(
                            "hash",
                            sha(rich.getValue("repair-follow-up-original.json"))
                        ).put("raw_path", "repair-follow-up-original.json")
                    )
                )
                it.put(
                    "road_followup_case",
                    JSONObject().put("stage", "no_repeat_signal")
                        .put("followup_sha256", sha(rich.getValue("follow-up-original.json")))
                )
                it.put(
                    "video",
                    JSONObject().put("sha256", sha(rich.getValue("test-pattern-video.mp4")))
                        .put("bytes", 4)
                )
            }
            rich["evidence-index.json"] =
                JSONObject().put("schema", "road-guardian.evidence-dossier.v1").put("event_id", id)
                    .put("sensor_sha256", sha(rich.getValue("sensor-original.json")))
                    .put("original_path", "sensor-original.json")
                    .put("report_path", "event-report.json").toString().toByteArray()
            accept(rich.toMutableMap())
            val atlasBitmap =
                android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            val atlasBytes = try {
                atlasBitmap.eraseColor(android.graphics.Color.GRAY)
                java.io.ByteArrayOutputStream().use {
                    check(atlasBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                atlasBitmap.recycle()
            }
            val surrounded = rich.toMutableMap().apply {
                this["surround-original.png"] = atlasBytes
                edit(this, "event-report.json") {
                    it.put(
                        "surround",
                        JSONObject().put("schema", "road-guardian.synthetic-surround.v1")
                            .put("event_id", id)
                            .put("sensor_sha256", sha(getValue("sensor-original.json")))
                            .put("source", "synthetic").put("camera_evidence", false)
                            .put("sha256", sha(atlasBytes)).put("bytes", atlasBytes.size)
                            .put("mime", "image/png").put("width", 4).put("height", 4)
                            .put("directions", JSONArray(listOf("front", "rear", "left", "right")))
                            .put(
                                "regions",
                                JSONObject().put("front", JSONArray(listOf(0, 0, .5, .5)))
                                    .put("rear", JSONArray(listOf(.5, 0, .5, .5)))
                                    .put("left", JSONArray(listOf(0, .5, .5, .5)))
                                    .put("right", JSONArray(listOf(.5, .5, .5, .5)))
                            )
                            .put("integrity_valid", true).put("url", "/api/events/$id/surround")
                            .put("path", "surround-original.png")
                    )
                }
            }
            accept(surrounded.toMutableMap())
            accept(data().apply {
                edit(this, "event-report.json") {
                    it.put(
                        "surround",
                        JSONObject.NULL
                    )
                }
            })
            fun rejectSurround(change: (JSONObject) -> Unit) {
                reject(
                    archive(
                        surrounded.toMutableMap().apply {
                            edit(
                                this,
                                "event-report.json"
                            ) { change(it.getJSONObject("surround")) }
                        })
                )
            }
            rejectSurround { it.put("event_id", UUID.randomUUID().toString()) }
            rejectSurround { it.put("sensor_sha256", "0".repeat(64)) }
            rejectSurround { it.put("schema", "road-guardian.synthetic-surround.v2") }
            rejectSurround { it.put("source", "camera") }
            rejectSurround { it.put("camera_evidence", true) }
            rejectSurround { it.put("integrity_valid", "true") }
            rejectSurround { it.put("mime", "image/jpeg") }
            rejectSurround { it.put("path", "other.png") }
            rejectSurround { it.put("url", "/api/events/${UUID.randomUUID()}/surround") }
            rejectSurround { it.put("sha256", "f".repeat(64)) }
            rejectSurround { it.put("bytes", atlasBytes.size + 1) }
            rejectSurround { it.put("bytes", atlasBytes.size.toString()) }
            rejectSurround { it.put("width", 4097) }
            rejectSurround { it.put("height", 3) }
            rejectSurround { it.put("width", "4") }
            rejectSurround {
                it.put(
                    "directions",
                    JSONArray(listOf("front", "front", "left", "right"))
                )
            }
            rejectSurround {
                it.put(
                    "directions",
                    JSONArray(listOf("rear", "front", "left", "right"))
                )
            }
            rejectSurround { it.getJSONObject("regions").getJSONArray("front").put(0, .5) }
            rejectSurround { it.getJSONObject("regions").getJSONArray("front").put(0, "0") }
            rejectSurround { it.getJSONObject("regions").remove("right") }
            rejectSurround {
                it.getJSONObject("regions").put("extra", JSONArray(listOf(0, 0, .5, .5)))
            }
            reject(archive(surrounded.toMutableMap().apply { remove("surround-original.png") }))
            reject(
                archive(
                    surrounded.toMutableMap()
                        .apply { edit(this, "event-report.json") { it.remove("surround") } })
            )
            reject(
                archive(
                    surrounded.toMutableMap().apply {
                        edit(this, "event-report.json") {
                            it.put(
                                "surround",
                                JSONObject.NULL
                            )
                        }
                    })
            )
            reject(
                archive(
                    surrounded.toMutableMap().apply {
                        edit(this, "event-report.json") {
                            it.put(
                                "surround",
                                "invalid"
                            )
                        }
                    })
            )
            // Both image and manifest hashes are updated: the image decoder must reject it.
            reject(archive(surrounded.toMutableMap().apply {
                val damaged =
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0)
                this["surround-original.png"] = damaged
                edit(this, "event-report.json") {
                    it.getJSONObject("surround").put("sha256", sha(damaged))
                        .put("bytes", damaged.size)
                }
            }))
            reject(archive(surrounded.toMutableMap().apply {
                val oversizedImage = ByteArray(8 * 1024 * 1024 + 1)
                this["surround-original.png"] = oversizedImage
                edit(this, "event-report.json") {
                    it.getJSONObject("surround").put("sha256", sha(oversizedImage))
                        .put("bytes", oversizedImage.size)
                }
            }))
            val linked = rich.toMutableMap()
            val followupId = UUID.randomUUID().toString()
            linked["follow-up-video.mp4"] = byteArrayOf(5, 6, 7, 8, 9)
            edit(linked, "event-report.json") {
                it.getJSONObject("road_followup_case").put("followup_id", followupId)
                it.put(
                    "road_followup_source", JSONObject().put("event_id", followupId)
                        .put("sensor_sha256", sha(linked.getValue("follow-up-original.json")))
                        .put("sensor_path", "follow-up-original.json")
                        .put(
                            "video",
                            JSONObject().put("sha256", sha(linked.getValue("follow-up-video.mp4")))
                                .put("bytes", 5)
                        )
                        .put("video_path", "follow-up-video.mp4")
                )
            }
            accept(linked.toMutableMap())
            accept(linked.toMutableMap().apply {
                this["surround-original.png"] = atlasBytes
                val descriptor = JSONObject(
                    surrounded.getValue("event-report.json").toString(Charsets.UTF_8)
                ).getJSONObject("surround")
                edit(this, "event-report.json") { it.put("surround", descriptor) }
            })
            val photoBitmap =
                android.graphics.Bitmap.createBitmap(3, 2, android.graphics.Bitmap.Config.ARGB_8888)
            val jpeg = try {
                java.io.ByteArrayOutputStream().also {
                    photoBitmap.compress(
                        android.graphics.Bitmap.CompressFormat.JPEG,
                        90,
                        it
                    )
                }.toByteArray()
            } finally {
                photoBitmap.recycle()
            }

            fun withPhotos(): MutableMap<String, ByteArray> = linked.toMutableMap().apply {
                this["input-photo.png"] = atlasBytes
                this["follow-up-input-photo.jpg"] = jpeg
                edit(this, "event-report.json") { report ->
                    fun descriptor(
                        path: String,
                        raw: ByteArray,
                        mime: String,
                        width: Int,
                        height: Int,
                        event: String,
                        sensor: String
                    ) = JSONObject()
                        .put("path", path).put("sha256", sha(raw)).put("bytes", raw.size)
                        .put("mime", mime).put("width", width).put("height", height)
                        .put("source", "synthetic_scene_fixture").put("camera_evidence", false)
                        .put("integrity_valid", true).put("event_id", event)
                        .put("sensor_sha256", sensor)
                    report.getJSONObject("video").put(
                        "input_photo",
                        descriptor(
                            "input-photo.png",
                            atlasBytes,
                            "image/png",
                            4,
                            4,
                            id,
                            sha(getValue("sensor-original.json"))
                        )
                    )
                    report.getJSONObject("road_followup_source").getJSONObject("video").put(
                        "input_photo",
                        descriptor(
                            "follow-up-input-photo.jpg",
                            jpeg,
                            "image/jpeg",
                            3,
                            2,
                            followupId,
                            sha(getValue("follow-up-original.json"))
                        )
                    )
                }
            }
            accept(withPhotos())
            // Full twelve-file package (manifest excluded) with both photos and atlas.
            accept(withPhotos().apply {
                this["surround-original.png"] = atlasBytes
                edit(this, "event-report.json") {
                    it.put(
                        "surround",
                        JSONObject(
                            surrounded.getValue("event-report.json").toString(Charsets.UTF_8)
                        ).getJSONObject("surround")
                    )
                }
            })
            for (followup in listOf(false, true)) {
                fun changePhoto(data: MutableMap<String, ByteArray>, change: (JSONObject) -> Unit) =
                    edit(data, "event-report.json") {
                        val video = if (followup) it.getJSONObject("road_followup_source")
                            .getJSONObject("video") else it.getJSONObject("video")
                        change(video.getJSONObject("input_photo"))
                    }

                val path = if (followup) "follow-up-input-photo.jpg" else "input-photo.png"
                for ((key, value) in listOf<Pair<String, Any>>(
                    "path" to "../input-photo.png",
                    "event_id" to UUID.randomUUID().toString(),
                    "sensor_sha256" to "0".repeat(64),
                    "source" to "synthetic",
                    "camera_evidence" to true,
                    "integrity_valid" to "true",
                    "sha256" to "0".repeat(64),
                    "bytes" to "20",
                    "width" to 0,
                    "height" to 4097,
                    "width" to "4",
                    "mime" to "image/webp"
                )) {
                    reject(archive(withPhotos().apply { changePhoto(this) { it.put(key, value) } }))
                }
                for (key in listOf(
                    "path",
                    "source",
                    "camera_evidence",
                    "integrity_valid",
                    "event_id",
                    "sensor_sha256",
                    "bytes",
                    "mime",
                    "width",
                    "height",
                    "sha256"
                ))
                    reject(archive(withPhotos().apply { changePhoto(this) { it.remove(key) } }))
                for (value in listOf<Any>(
                    JSONObject.NULL,
                    "invalid"
                )) reject(archive(withPhotos().apply {
                    edit(this, "event-report.json") {
                        val video = if (followup) it.getJSONObject("road_followup_source")
                            .getJSONObject("video") else it.getJSONObject("video")
                        video.put("input_photo", value)
                    }
                }))
                reject(archive(withPhotos().apply {
                    this[path] = if (followup) atlasBytes else jpeg
                    changePhoto(this) {
                        it.put("sha256", sha(getValue(path))).put("bytes", getValue(path).size)
                    }
                }))
                reject(archive(withPhotos().apply { remove(path) }))
                reject(archive(withPhotos().apply {
                    this[path] = byteArrayOf(1, 2, 3)
                    changePhoto(this) { it.put("bytes", 3).put("sha256", sha(getValue(path))) }
                }))
                reject(archive(withPhotos().apply {
                    this[path] = ByteArray(8 * 1024 * 1024 + 1)
                    changePhoto(this) {
                        it.put("bytes", getValue(path).size).put("sha256", sha(getValue(path)))
                    }
                }))
                reject(archive(withPhotos().apply {
                    this[if (followup) "follow-up-input-photo.png" else "input-photo.jpg"] =
                        atlasBytes
                }))
                val legacy = withPhotos().apply {
                    remove("input-photo.png")
                    remove("follow-up-input-photo.jpg")
                    edit(this, "event-report.json") { report ->
                        for (video in listOf(
                            report.getJSONObject("video"),
                            report.getJSONObject("road_followup_source").getJSONObject("video")
                        )) {
                            val photo = video.getJSONObject("input_photo")
                            for (key in listOf(
                                "path",
                                "source",
                                "camera_evidence",
                                "integrity_valid",
                                "event_id",
                                "sensor_sha256"
                            )) photo.remove(key)
                        }
                    }
                }
                accept(legacy.toMutableMap())
                reject(archive(legacy.apply { this[path] = if (followup) jpeg else atlasBytes }))
            }
            reject(archive(withPhotos().apply {
                edit(
                    this,
                    "event-report.json"
                ) { it.remove("video") }
            }))
            reject(archive(withPhotos().apply {
                edit(
                    this,
                    "event-report.json"
                ) { it.remove("road_followup_source") }
            }))
            val withoutVideo = linked.toMutableMap().apply {
                remove("follow-up-video.mp4")
                edit(this, "event-report.json") {
                    it.getJSONObject("road_followup_source").put("video", JSONObject.NULL)
                        .put("video_path", JSONObject.NULL)
                }
            }
            accept(withoutVideo.toMutableMap())
            accept(data().apply {
                edit(this, "event-report.json") {
                    it.put(
                        "road_followup_source",
                        JSONObject.NULL
                    )
                }
            })
            // Rehash every changed report/file into the manifest. These must fail on
            // source relationships, not merely a stale ZIP manifest checksum.
            fun rejectSource(change: (JSONObject) -> Unit) {
                reject(archive(linked.toMutableMap().apply {
                    edit(
                        this,
                        "event-report.json"
                    ) { change(it.getJSONObject("road_followup_source")) }
                }))
            }
            rejectSource { it.put("event_id", UUID.randomUUID().toString()) }
            rejectSource { it.put("event_id", id) }
            rejectSource { it.put("event_id", "../other") }
            rejectSource { it.put("sensor_sha256", "0".repeat(64)) }
            rejectSource { it.put("sensor_path", "sensor-original.json") }
            rejectSource { it.put("video_path", "test-pattern-video.mp4") }
            rejectSource { it.put("video_path", JSONObject.NULL) }
            rejectSource { it.getJSONObject("video").put("sha256", "f".repeat(64)) }
            rejectSource { it.getJSONObject("video").put("bytes", 4) }
            rejectSource { it.getJSONObject("video").put("bytes", "5") }
            rejectSource { it.put("video", JSONObject.NULL) }
            rejectSource { it.put("video", "invalid") }
            rejectSource { it.remove("video") }
            rejectSource { it.remove("video_path") }
            reject(archive(linked.toMutableMap().apply { remove("follow-up-video.mp4") }))
            reject(
                archive(
                    linked.toMutableMap()
                        .apply { this["follow-up-video.mp4"] = byteArrayOf(9, 8, 7, 6, 5) })
            )
            reject(archive(linked.toMutableMap().apply { remove("follow-up-original.json") }))
            reject(
                archive(
                    linked.toMutableMap().apply {
                        edit(
                            this,
                            "event-report.json"
                        ) { it.remove("road_followup_source") }
                    })
            )
            reject(
                archive(
                    linked.toMutableMap().apply {
                        edit(this, "event-report.json") {
                            it.put(
                                "road_followup_source",
                                JSONObject.NULL
                            )
                        }
                    })
            )
            reject(
                archive(
                    linked.toMutableMap().apply {
                        edit(this, "event-report.json") {
                            it.put(
                                "road_followup_source",
                                "invalid"
                            )
                        }
                    })
            )
            reject(
                archive(
                    linked.toMutableMap().apply {
                        edit(this, "event-report.json") {
                            it.getJSONObject("road_followup_case")
                                .put("followup_id", UUID.randomUUID().toString())
                        }
                    })
            )
            reject(
                archive(
                    withoutVideo.toMutableMap().apply {
                        edit(this, "event-report.json") {
                            it.getJSONObject("road_followup_source")
                                .put("video_path", "follow-up-video.mp4")
                        }
                    })
            )
            reject(archive(data().apply {
                edit(
                    this,
                    "event-report.json"
                ) { it.put("road_followup_source", JSONObject().put("event_id", followupId)) }
            }))
            reject(archive(data()), UUID.randomUUID().toString())
            reject(archive(data(), { it.put("schema", "road-guardian.package.v2") }))
            reject(archive(data(), { it.put("external_submission", "false") }))
            reject(archive(data(), { it.put("source", "vehicle") }))
            reject(archive(data(), { it.getJSONArray("files").getJSONObject(0).put("bytes", "3") }))
            reject(archive(data(),
                { it.getJSONArray("files").put(it.getJSONArray("files").getJSONObject(0)) }))
            reject(
                archive(
                    data(),
                    afterManifest = { it["sensor-original.json"] = "changed".toByteArray() })
            )
            reject(archive(data(), afterManifest = { it["unlisted.txt"] = "extra".toByteArray() }))
            reject(
                archive(
                    data(),
                    afterManifest = { it["../sensor-original.json"] = "unsafe".toByteArray() })
            )
            reject(archive(data().apply { remove("sensor-original.json") }))
            reject(archive(data().apply {
                edit(this, "event-report.json") {
                    it.put(
                        "id",
                        UUID.randomUUID().toString()
                    )
                }
            }))
            reject(archive(data().apply {
                edit(this, "event-report.json") {
                    it.put(
                        "sensor_sha256",
                        "0".repeat(64)
                    )
                }
            }))
            reject(archive(data().apply {
                edit(
                    this,
                    "event-report.json"
                ) { it.put("external_submission", true) }
            }))
            reject(
                archive(
                    rich.toMutableMap().apply {
                        edit(this, "evidence-index.json") {
                            it.put(
                                "original_path",
                                "../sensor-original.json"
                            )
                        }
                    })
            )
            reject(
                archive(
                    rich.toMutableMap().apply {
                        edit(this, "private-review-note.json") {
                            it.put(
                                "event_id",
                                UUID.randomUUID().toString()
                            )
                        }
                    })
            )
            reject(
                archive(
                    rich.toMutableMap().apply {
                        edit(this, "event-report.json") {
                            it.getJSONObject("repair").getJSONObject("post_repair")
                                .put("hash", "f".repeat(64))
                        }
                    })
            )
            reject(archive(rich.toMutableMap().apply { remove("follow-up-original.json") }))
            reject(
                archive(
                    rich.toMutableMap().apply {
                        edit(this, "event-report.json") {
                            it.getJSONObject("video").put("bytes", 5)
                        }
                    })
            )
            reject(archive(data().apply {
                this["event-report.json"] = ByteArray(2 * 1024 * 1024 + 1) { 32 }
            }))
            val truncated = archive(data())
            java.io.RandomAccessFile(truncated, "rw").use { it.setLength(20) }
            reject(truncated)
            val oversized = archive(data())
            java.io.RandomAccessFile(oversized, "rw")
                .use { it.setLength(AuthenticatedExportTransport.MAX_BYTES.toLong() + 1) }
            reject(oversized)
            // ZIP writers prohibit duplicate names; alter equal-length headers to exercise a received duplicate.
            val duplicate = archive(data().apply { this["OTHER_.txt"] = "duplicate".toByteArray() })
            val encoded = duplicate.readBytes()
            val before = "OTHER_.txt".toByteArray()
            val after = "README.txt".toByteArray()
            for (offset in 0..encoded.size - before.size) if (before.indices.all { encoded[offset + it] == before[it] })
                after.copyInto(encoded, offset)
            duplicate.writeBytes(encoded)
            reject(duplicate)
            val expanded = File.createTempFile("verifier-expanded-", ".zip", targetContext.cacheDir)
            files.add(expanded)
            ZipOutputStream(expanded.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("test-pattern-video.mp4"))
                val zeros = ByteArray(65536)
                repeat(1280) { zip.write(zeros) }
                zip.write(0)
                zip.closeEntry()
            }
            reject(expanded)
            fixtureDir?.let { directory ->
                val root = File(directory)
                fun fixtureId(file: File): String = java.util.zip.ZipFile(file).use { zip ->
                    JSONObject(
                        zip.getInputStream(checkNotNull(zip.getEntry("manifest.json")))
                            .bufferedReader().use { it.readText() }).getString("event_id")
                }
                for (name in listOf("clear.zip", "repeat.zip")) {
                    val file = File(root, name)
                    val expected = fixtureId(file)
                    check(EvidencePackageVerifier.verify(file, expected).eventId == expected)
                    valid++
                }
                val negatives = checkNotNull(
                    File(
                        root,
                        "negative-packages"
                    ).listFiles()
                ).filter { it.extension == "zip" }.sortedBy { it.name }
                check(negatives.isNotEmpty())
                for (file in negatives) {
                    try {
                        reject(file, fixtureId(file))
                    } catch (failure: Exception) {
                        throw IllegalStateException("fixture ${file.name}", failure)
                    }
                }
            }
            result.putString(
                "stream",
                "PASS: $valid valid packages; $rejected altered, foreign, unsafe or oversized packages rejected by production verifier. No external document written.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString(
                "stream",
                "FAIL: ${failure.javaClass.simpleName}: ${failure.message}, valid=$valid rejected=$rejected\n"
            )
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            files.forEach { it.delete() }
        }
    }
}
