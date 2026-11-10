package ai.teraq.roadguardian

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 문서 저장소에 쓰기 전에 앱 전용 저장소에서 검사한다. ZIP 경로는 풀지 않는다 */
object EvidencePackageVerifier {
    private const val MAX_EXPANDED = 80L * 1024 * 1024
    private const val MAX_JSON = 2 * 1024 * 1024
    private val inputPhotoNames = setOf(
        "input-photo.png",
        "input-photo.jpg",
        "follow-up-input-photo.png",
        "follow-up-input-photo.jpg"
    )
    private val syntheticNames = setOf(
        "event-map.json",
        "event-map.png",
        "manifest.json",
        "sensor-original.json",
        "event-report.json",
        "README.txt",
        "evidence-index.json",
        "private-review-note.json",
        "repair-follow-up-original.json",
        "follow-up-original.json",
        "test-pattern-video.mp4",
        "follow-up-video.mp4",
        "surround-original.png",
        "plate-video-analysis.json",
        "plate-analysis.json",
        "plate-model-input.png",
        "scene-analysis.json",
        "scene-model-input.png",
        "video-visibility-analysis.json",
        "video-road-analysis.json",
        "video-collision-analysis.json",
        "video-lane-analysis.json"
    ) + VideoVisibilityAnalysis.paths + VideoRoadAnalysis.paths + VideoCollisionAnalysis.paths + VideoLaneAnalysis.paths + inputPhotoNames + SyntheticSurroundViews.archivePaths
    private val ownerNames = setOf(
        "manifest.json",
        "authored-original.json",
        "owner-report.json",
        "README.txt",
        "private-review-note.json",
        "private-sdk-observations.json"
    )
    private val names = syntheticNames + ownerNames + "photo-index.json"
    private fun photoName(name: String) =
        name.matches(Regex("photos/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg"))

    data class Verified(val eventId: String, val files: Int, val expandedBytes: Long)
    private data class Entry(val size: Long, val hash: String, val raw: ByteArray?)

    fun verify(file: File, eventId: String): Verified {
        try {
            require(AccountEventRoute.checked(eventId) == eventId)
            require(file.length() in 4..AuthenticatedExportTransport.MAX_BYTES.toLong())
            ZipFile(file).use { zip ->
                val found = linkedMapOf<String, Entry>()
                var total = 0L
                var photos = 0
                val started = System.nanoTime()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val item = entries.nextElement()
                    val name = item.name
                    val photo = photoName(name)
                    require((name in names || photo || PlateVideoAnalysis.resource(name)) && name !in found && !item.isDirectory)
                    if (photo) {
                        photos++
                        require(photos <= 4)
                    }
                    require(item.size in 0..MAX_EXPANDED)
                    val surround =
                        name == "surround-original.png" || name in SyntheticSurroundViews.archivePaths
                    val inputPhoto =
                        name == "event-map.png" || PlateVideoAnalysis.resource(name) || name in inputPhotoNames || name == "scene-model-input.png" || name == "plate-model-input.png" || name in VideoVisibilityAnalysis.paths || name in VideoRoadAnalysis.paths || name in VideoCollisionAnalysis.paths || name in VideoLaneAnalysis.paths
                    val collect = name.endsWith(".json") || photo || surround || inputPhoto
                    val limit =
                        if (surround || inputPhoto) 8L * 1024 * 1024 else if (photo) CasePhoto.MAX_BYTES.toLong() else if (name == "manifest.json") 256L * 1024 else if (collect) MAX_JSON.toLong() else MAX_EXPANDED
                    require(item.size <= limit)
                    val saved = if (collect) ByteArrayOutputStream() else null
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.getInputStream(item).use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (Thread.currentThread().isInterrupted || System.nanoTime() - started > 30_000_000_000L) throw java.io.InterruptedIOException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(count <= limit - size && count <= MAX_EXPANDED - total)
                            size += count
                            total += count
                            digest.update(buffer, 0, count)
                            saved?.write(buffer, 0, count)
                        }
                    }
                    require(size == item.size)
                    found[name] = Entry(
                        size,
                        digest.digest().joinToString("") { "%02x".format(it) },
                        saved?.toByteArray()
                    )
                }
                fun json(name: String) =
                    JSONObject(checkNotNull(found[name]?.raw).toString(Charsets.UTF_8))

                val manifest = json("manifest.json")
                val photoPackage = manifest.getString("schema") == "road-guardian.owner-package.v2"
                val authored =
                    photoPackage || manifest.getString("schema") == "road-guardian.owner-package.v1"
                require(authored || manifest.getString("schema") == "road-guardian.package.v1")
                require(manifest.getString("event_id") == eventId && manifest.get("external_submission") == false)
                require(manifest.getString("source") == if (authored) "owner_reported" else "synthetic")
                fun allowed(name: String) =
                    if (photoPackage) name in ownerNames || name == "photo-index.json" || photoName(
                        name
                    ) else if (authored) name in ownerNames else name in syntheticNames || PlateVideoAnalysis.resource(
                        name
                    )
                require(found.keys.all { allowed(it) })
                val listed = mutableSetOf("manifest.json")
                val files = manifest.getJSONArray("files")
                require(files.length() in 3..if (photoPackage) 10 else if (authored) 5 else syntheticNames.size - 1)
                for (index in 0 until files.length()) {
                    val expected = files.getJSONObject(index)
                    val name = expected.getString("path")
                    require(allowed(name) && listed.add(name))
                    val actual = checkNotNull(found[name])
                    val size = expected.get("bytes")
                    require((size is Int || size is Long) && (size as Number).toLong() == actual.size)
                    require(expected.getString("sha256") == actual.hash)
                }
                require(found.keys == listed)
                if (authored) {
                    require(
                        listed.containsAll(
                            setOf(
                                "authored-original.json",
                                "owner-report.json",
                                "README.txt"
                            )
                        )
                    )
                    require(manifest.get("sensor_original") == false)
                    val report = json("owner-report.json")
                    val content = json("authored-original.json")
                    require(report.getString("schema") == if (photoPackage) "road-guardian.owner-export.v2" else "road-guardian.owner-export.v1")
                    require(report.getString("id") == eventId)
                    require(report.getString("source") == "owner_reported" && report.getString("status") == "owner_recorded")
                    require(
                        report.getString("content_path") == "authored-original.json" && report.getString(
                            "content_sha256"
                        ) == found["authored-original.json"]?.hash
                    )
                    for (flag in listOf(
                        "sensor_original",
                        "automatic_assessment",
                        "responsibility_determined",
                        "external_submission"
                    )) require(report.get(flag) == false)
                    val created = report.get("created")
                    require(
                        created is Number && created.toDouble().isFinite() && created.toDouble() > 0
                    )
                    require(
                        content.getString("schema") == "road-guardian.owner-record.v1" && content.getString(
                            "source"
                        ) == "owner_reported"
                    )
                    fun text(
                        obj: JSONObject,
                        key: String,
                        limit: Int,
                        required: Boolean = false
                    ): String {
                        val value = obj.get(key)
                        require(value is String && value.length <= limit && (!required || value.isNotBlank()))
                        return value
                    }
                    text(content, "vehicle_id", 128, true)
                    val title = text(content, "title", 200, true)
                    text(content, "description", 8000)
                    val category = content.getString("category")
                    require(category in setOf("road", "maintenance", "incident"))
                    val reported = content.get("reported_at")
                    require(
                        reported == JSONObject.NULL || (reported is Number && reported.toDouble()
                            .isFinite() && reported.toDouble() > 0)
                    )
                    val context = content.getJSONObject("context")
                    require(
                        context.getString("source") == "owner_reported" && context.getJSONArray(
                            "actors"
                        ).length() == 0
                    )
                    val own = context.getJSONObject("own_vehicle")
                    require(own.getString("source") == "owner_reported")
                    text(own, "plate", 80)
                    text(own, "model", 120)
                    text(own, "color", 60)
                    val assessment = report.getJSONObject("assessment")
                    require(assessment.getString("kind") == "owner_record" && assessment.getString("status") == "unassessed")
                    require(assessment.getString("category") == category && assessment.getString("title") == title)
                    for (flag in listOf(
                        "automatic_assessment",
                        "responsibility_determined",
                        "external_submission"
                    )) require(assessment.get(flag) == false)
                    val noteRevision = report.get("private_note_revision")
                    require(noteRevision is Int && noteRevision >= 0)
                    val noteStatus = report.getString("private_note_status")
                    require(noteStatus in setOf("included", "not_present", "feature_disabled"))
                    require((noteStatus == "included") == ("private-review-note.json" in found) && (noteRevision > 0) == (noteStatus == "included"))
                    if (noteRevision > 0) {
                        val note = json("private-review-note.json")
                        require(note.get("evidence_statement") == false)
                        require(
                            PrivateReviewNote.parse(
                                note.toString(),
                                eventId
                            ).revision == noteRevision
                        )
                    }
                    val observationStatus = report.getString("sdk_observation_status")
                    require(observationStatus in setOf("included", "feature_disabled"))
                    require((observationStatus == "included") == ("private-sdk-observations.json" in found))
                    val count = report.get("sdk_observation_count")
                    require(count is Int && count in 0..64)
                    if (observationStatus == "included") require(
                        SdkObservationRecord.parse(
                            json("private-sdk-observations.json"),
                            eventId
                        ).size == count
                    )
                    else require(count == 0)
                    if (photoPackage) {
                        val photoStatus = report.getString("private_photo_status")
                        require(photoStatus in setOf("included", "feature_disabled"))
                        val photoCount = report.get("photo_count")
                        require(photoCount is Int && photoCount in 0..4)
                        val paths = mutableSetOf<String>()
                        if (photoStatus == "included") {
                            require(report.getString("photo_index_path") == "photo-index.json")
                            val index = json("photo-index.json")
                            require(
                                index.getString("schema") == "road-guardian.case-photo-index.v1" && index.getString(
                                    "event_id"
                                ) == eventId && index.getString("scope") == "current_subject_only"
                            )
                            val entries = index.getJSONArray("photos")
                            require(entries.length() == photoCount)
                            val ids = mutableSetOf<String>()
                            for (i in 0 until entries.length()) {
                                val metadata = entries.getJSONObject(i)
                                val photo = CasePhoto.parse(metadata, eventId)
                                val path = "photos/${photo.id}.jpg"
                                require(
                                    metadata.getString("path") == path && paths.add(path) && ids.add(
                                        photo.id
                                    )
                                )
                                val stored = checkNotNull(found[path])
                                require(stored.size == photo.bytes.toLong() && stored.hash == photo.sha256)
                                photo.verifiedBytes(checkNotNull(stored.raw))
                            }
                        } else require(photoCount == 0 && report.get("photo_index_path") == JSONObject.NULL && "photo-index.json" !in found)
                        require(found.keys.filter { photoName(it) }.toSet() == paths)
                    }
                    return Verified(eventId, files.length(), total)
                }
                require(
                    listed.containsAll(
                        setOf(
                            "sensor-original.json",
                            "event-report.json",
                            "README.txt"
                        )
                    )
                )
                val report = json("event-report.json")
                val sensor = checkNotNull(found["sensor-original.json"])
                require(
                    report.getString("schema") == "road-guardian.export.v1" && report.getString(
                        "id"
                    ) == eventId
                )
                require(
                    report.get("external_submission") == false && report.getString("source") == manifest.getString(
                        "source"
                    )
                )
                require(report.getString("sensor_sha256") == sensor.hash)
                require(("event-map.json" in found) == ("event-map.png" in found))
                if ("event-map.json" in found) {
                    val map = json("event-map.json")
                    require(map.getString("image_path") == "event-map.png" && !map.has("png"))
                    require(map.get("oem_navigation_capture") == false && map.get("captured_at_event_time") == false)
                    require(map.getString("attribution") == "© OpenFreeMap · OpenStreetMap")
                    EventMapExport.validate(
                        map,
                        checkNotNull(found["event-map.png"]?.raw),
                        eventId,
                        sensor.hash,
                        json("sensor-original.json").getJSONObject("location")
                    )
                }
                val surround =
                    if (report.has("surround") && !report.isNull("surround")) report.getJSONObject("surround") else null
                if (surround == null) require("surround-original.png" !in found)
                else {
                    require(surround.getString("schema") == "road-guardian.synthetic-surround.v1")
                    require(surround.getString("event_id") == eventId && surround.getString("sensor_sha256") == sensor.hash)
                    require(
                        surround.getString("source") == "synthetic" && surround.get("camera_evidence") == false && surround.get(
                            "integrity_valid"
                        ) == true
                    )
                    require(surround.getString("mime") == "image/png" && surround.getString("path") == "surround-original.png")
                    require(surround.getString("url") == "/api/events/$eventId/surround")
                    val directions = surround.getJSONArray("directions")
                    require(
                        directions.length() == 4 && listOf(
                            "front",
                            "rear",
                            "left",
                            "right"
                        ).withIndex().all { directions.get(it.index) == it.value })
                    val regions = surround.getJSONObject("regions")
                    val expectedRegions = linkedMapOf(
                        "front" to listOf(0.0, 0.0, 0.5, 0.5), "rear" to listOf(0.5, 0.0, 0.5, 0.5),
                        "left" to listOf(0.0, 0.5, 0.5, 0.5), "right" to listOf(0.5, 0.5, 0.5, 0.5)
                    )
                    require(regions.keys().asSequence().toSet() == expectedRegions.keys)
                    expectedRegions.forEach { (direction, values) ->
                        val region = regions.getJSONArray(direction)
                        require(region.length() == 4)
                        values.forEachIndexed { index, value ->
                            val coordinate = region.get(index)
                            require(coordinate is Number && coordinate.toDouble() == value)
                        }
                    }
                    val stored = checkNotNull(found["surround-original.png"])
                    val bytes = surround.get("bytes")
                    require((bytes is Int || bytes is Long) && (bytes as Number).toLong() == stored.size && stored.size in 1..8L * 1024 * 1024)
                    require(surround.getString("sha256") == stored.hash)
                    val width = surround.get("width")
                    val height = surround.get("height")
                    require(width is Int && height is Int && width in 2..4096 && height in 2..4096)
                    val raw = checkNotNull(stored.raw)
                    val signature =
                        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                    require(raw.size >= signature.size && signature.indices.all { raw[it] == signature[it] })
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                    require(bounds.outMimeType == "image/png" && bounds.outWidth == width && bounds.outHeight == height)
                    val decoded = checkNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size))
                    try {
                        require(decoded.width == width && decoded.height == height)
                    } finally {
                        decoded.recycle()
                    }
                }
                val surroundViews = SyntheticSurroundViews.metadata(report)
                if (surroundViews == null) require(found.keys.none { it in SyntheticSurroundViews.archivePaths })
                else {
                    val rows = SyntheticSurroundViews.validate(surroundViews, eventId, sensor.hash)
                    val paths = rows.map { row ->
                        val path = "surround/${row.getString("direction")}.png"
                        require(row.getString("path") == path)
                        val stored = checkNotNull(found[path])
                        require(
                            stored.hash == row.getString("sha256") && stored.size == row.getLong(
                                "bytes"
                            )
                        )
                        SyntheticSurroundViews.decode(row, checkNotNull(stored.raw)).recycle()
                        path
                    }.toSet()
                    require(found.keys.filter { it in SyntheticSurroundViews.archivePaths }
                        .toSet() == paths)
                }
                if ("evidence-index.json" in found) {
                    val dossier = json("evidence-index.json")
                    require(
                        dossier.getString("schema") == "road-guardian.evidence-dossier.v1" && dossier.getString(
                            "event_id"
                        ) == eventId
                    )
                    require(
                        dossier.getString("sensor_sha256") == sensor.hash && dossier.getString("original_path") == "sensor-original.json" && dossier.getString(
                            "report_path"
                        ) == "event-report.json"
                    )
                }
                fun bind(name: String, expected: String?) {
                    require(if (expected == null) name !in found else found[name]?.hash == expected)
                }

                fun verifyInputPhoto(
                    video: JSONObject?,
                    prefix: String,
                    boundId: String,
                    boundHash: String
                ) {
                    val paths = setOf("$prefix.png", "$prefix.jpg")
                    val attached = found.keys.filter { it in paths }.toSet()
                    val descriptor =
                        if (video != null && video.has("input_photo") && !video.isNull("input_photo"))
                            video.getJSONObject("input_photo") else null
                    if (descriptor == null) {
                        require(attached.isEmpty())
                        return
                    }
                    // 예전 내보내기 파일은 메타데이터만 있었다. 새 연결 필드가 하나라도 있으면 첨부 규칙 전체를 적용한다
                    val bindingFields = listOf(
                        "path",
                        "source",
                        "camera_evidence",
                        "integrity_valid",
                        "event_id",
                        "sensor_sha256"
                    )
                    if (bindingFields.none { descriptor.has(it) }) {
                        require(attached.isEmpty())
                        return
                    }
                    fun string(key: String): String {
                        val value = descriptor.get(key)
                        require(value is String)
                        return value
                    }
                    require(
                        string("source") == "synthetic_scene_fixture" && descriptor.get("camera_evidence") == false && descriptor.get(
                            "integrity_valid"
                        ) == true
                    )
                    require(string("event_id") == boundId && string("sensor_sha256") == boundHash)
                    val mime = string("mime")
                    require(mime in setOf("image/png", "image/jpeg"))
                    val path = string("path")
                    require(
                        path == "$prefix.${if (mime == "image/png") "png" else "jpg"}" && attached == setOf(
                            path
                        )
                    )
                    val stored = checkNotNull(found[path])
                    val bytes = descriptor.get("bytes")
                    require((bytes is Int || bytes is Long) && (bytes as Number).toLong() == stored.size && stored.size in 1..8L * 1024 * 1024)
                    require(string("sha256") == stored.hash)
                    val width = descriptor.get("width")
                    val height = descriptor.get("height")
                    require(width is Int && height is Int && width in 1..4096 && height in 1..4096)
                    val raw = checkNotNull(stored.raw)
                    if (mime == "image/png") {
                        val signature =
                            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                        require(raw.size >= signature.size && signature.indices.all { raw[it] == signature[it] })
                    } else require(raw.size >= 3 && raw[0] == 0xff.toByte() && raw[1] == 0xd8.toByte() && raw[2] == 0xff.toByte())
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                    require(bounds.outMimeType == mime && bounds.outWidth == width && bounds.outHeight == height)
                    val decoded = checkNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size))
                    try {
                        require(decoded.width == width && decoded.height == height)
                    } finally {
                        decoded.recycle()
                    }
                }

                val road = report.optJSONObject("road_followup_case")
                val hasRoad = road?.optString("stage") in setOf("repeat_signal", "no_repeat_signal")
                bind(
                    "follow-up-original.json",
                    if (hasRoad) road!!.getString("followup_sha256") else null
                )
                // 예전 패키지에는 출처 정보가 없다. 새 영상은 출처 정보와 연결된 센서 원본과의 관계가 꼭 있어야 한다
                val roadSource =
                    if (report.has("road_followup_source") && !report.isNull("road_followup_source"))
                        report.getJSONObject("road_followup_source") else null
                if (roadSource != null) {
                    require(hasRoad)
                    val sourceId = roadSource.getString("event_id")
                    require(AccountEventRoute.checked(sourceId) == sourceId && sourceId != eventId)
                    require(sourceId == road!!.getString("followup_id"))
                    require(roadSource.getString("sensor_path") == "follow-up-original.json")
                    require(
                        roadSource.getString("sensor_sha256") == road.getString("followup_sha256") &&
                                roadSource.getString("sensor_sha256") == found["follow-up-original.json"]?.hash
                    )
                    val sourceVideo = roadSource.get("video")
                    verifyInputPhoto(
                        if (sourceVideo == JSONObject.NULL) null else sourceVideo as JSONObject,
                        "follow-up-input-photo",
                        sourceId,
                        roadSource.getString("sensor_sha256")
                    )
                    if (sourceVideo == JSONObject.NULL) {
                        require(roadSource.get("video_path") == JSONObject.NULL && "follow-up-video.mp4" !in found)
                    } else {
                        require(sourceVideo is JSONObject)
                        require(roadSource.getString("video_path") == "follow-up-video.mp4")
                        val stored = checkNotNull(found["follow-up-video.mp4"])
                        val bytes = sourceVideo.get("bytes")
                        require(bytes is Int || bytes is Long)
                        require((bytes as Number).toLong() > 0 && bytes.toLong() == stored.size)
                        require(sourceVideo.getString("sha256") == stored.hash)
                    }
                } else {
                    require("follow-up-video.mp4" !in found)
                    verifyInputPhoto(null, "follow-up-input-photo", eventId, sensor.hash)
                }
                val repair = report.optJSONObject("repair")?.optJSONObject("post_repair")
                bind("repair-follow-up-original.json", repair?.getString("hash"))
                if (repair != null) {
                    require(repair.getString("raw_path") == "repair-follow-up-original.json")
                }

                val video =
                    if (report.has("video") && !report.isNull("video")) report.getJSONObject("video") else null
                verifyInputPhoto(video, "input-photo", eventId, sensor.hash)
                val plateVideoResources = found.filterKeys { PlateVideoAnalysis.resource(it) }
                    .mapValues { checkNotNull(it.value.raw) }
                if (report.has("plate_video_analysis") && !report.isNull("plate_video_analysis")) {
                    PlateVideoAnalysis.verifyArchive(
                        eventId,
                        sensor.hash,
                        checkNotNull(video),
                        report.getJSONObject("plate_video_analysis"),
                        json("plate-video-analysis.json"),
                        plateVideoResources
                    )
                    val original =
                        File.createTempFile("plate-video-verify-", ".mp4", file.parentFile)
                    try {
                        zip.getInputStream(checkNotNull(zip.getEntry("test-pattern-video.mp4")))
                            .use { input -> original.outputStream().use { input.copyTo(it) } }
                        PlateVideoAnalysis.verifyVideoTimeline(
                            report.getJSONObject("plate_video_analysis"),
                            original
                        )
                    } finally {
                        original.delete()
                    }
                } else require("plate-video-analysis.json" !in found && plateVideoResources.isEmpty())
                if (report.has("plate_analysis") && !report.isNull("plate_analysis")) {
                    PlateAnalysis.verifyArchive(
                        eventId,
                        sensor.hash,
                        checkNotNull(video).getJSONObject("input_photo").getString("sha256"),
                        report.getJSONObject("plate_analysis"),
                        json("plate-analysis.json"),
                        checkNotNull(found["plate-model-input.png"]?.raw)
                    )
                } else require("plate-analysis.json" !in found && "plate-model-input.png" !in found)
                if (report.has("scene_analysis") && !report.isNull("scene_analysis")) {
                    ScenePhotoAnalysis.verifyArchive(
                        eventId,
                        sensor.hash,
                        checkNotNull(video).getJSONObject("input_photo").getString("sha256"),
                        report.getJSONObject("scene_analysis"),
                        json("scene-analysis.json"),
                        checkNotNull(found["scene-model-input.png"]?.raw)
                    )
                } else require("scene-analysis.json" !in found && "scene-model-input.png" !in found)
                if (report.has("video_visibility_analysis") && !report.isNull("video_visibility_analysis")) {
                    VideoVisibilityAnalysis.verifyArchive(
                        eventId,
                        sensor.hash,
                        checkNotNull(video).getString("sha256"),
                        report.getJSONObject("video_visibility_analysis"),
                        json("video-visibility-analysis.json"),
                        VideoVisibilityAnalysis.paths.associateWith { checkNotNull(found[it]?.raw) })
                } else require("video-visibility-analysis.json" !in found && VideoVisibilityAnalysis.paths.none { it in found })
                if (report.has("video_road_analysis") && !report.isNull("video_road_analysis")) {
                    VideoRoadAnalysis.verifyArchive(
                        eventId,
                        sensor.hash,
                        checkNotNull(video).getString("sha256"),
                        report.getJSONObject("video_road_analysis"),
                        json("video-road-analysis.json"),
                        VideoRoadAnalysis.paths.associateWith { checkNotNull(found[it]?.raw) })
                } else require("video-road-analysis.json" !in found && VideoRoadAnalysis.paths.none { it in found })
                if (report.has("video_collision_analysis") && !report.isNull("video_collision_analysis")) {
                    VideoCollisionAnalysis.verifyArchive(
                        eventId,
                        sensor.hash,
                        checkNotNull(video).getString("sha256"),
                        report.getJSONObject("video_collision_analysis"),
                        json("video-collision-analysis.json"),
                        VideoCollisionAnalysis.paths.associateWith { checkNotNull(found[it]?.raw) })
                } else require("video-collision-analysis.json" !in found && VideoCollisionAnalysis.paths.none { it in found })
                if (report.has("video_lane_analysis") && !report.isNull("video_lane_analysis")) {
                    VideoLaneAnalysis.verifyArchive(
                        eventId,
                        sensor.hash,
                        checkNotNull(video).getString("sha256"),
                        report.getJSONObject("video_lane_analysis"),
                        json("video-lane-analysis.json"),
                        VideoLaneAnalysis.paths.associateWith { checkNotNull(found[it]?.raw) })
                } else require("video-lane-analysis.json" !in found && VideoLaneAnalysis.paths.none { it in found })
                bind("test-pattern-video.mp4", video?.getString("sha256"))
                if (video != null) require(video.getLong("bytes") == found["test-pattern-video.mp4"]?.size)
                if ("private-review-note.json" in found) require(
                    PrivateReviewNote.parse(
                        json("private-review-note.json").toString(),
                        eventId
                    ).revision > 0
                )
                return Verified(eventId, files.length(), total)
            }
        } catch (failure: java.io.InterruptedIOException) {
            throw failure
        } catch (_: Exception) {
            throw AccountAccessException(AccountAccessException.Reason.INTEGRITY)
        }
    }
}
