package ai.teraq.roadguardian

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 기관 전달을 로컬에서 흉내 낸 기록. 개인 안전 신고와 분리한다 */
object RoadAgencyReceiptStore {
    private val lock = Any()
    private const val LIMIT = 8 * 1024 * 1024
    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }

        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> JSONObject.quote(v)
        is Number -> JSONObject.numberToString(v)
        is Boolean -> v.toString()
        else -> error("Unsupported value")
    }

    private fun digest(v: Any) = ContestValueModel.hash(canonical(v))

    /** 서버가 정한 묶음 토큰으로 모든 페이지를 모은다. 미리보기 일부만 쓰지 않는다 */
    fun collect(client: BackendClient, groupId: String): JSONObject {
        require(groupId.matches(Regex("[0-9a-f]{16}")))
        var page = JSONObject(client.request("/api/demo/road-summary/groups/$groupId"))
        val membership = page.getString("membership_sha256")
        require(membership.matches(Regex("[0-9a-f]{64}")))
        val total = page.getInt("total")
        require(total in 1..1000)
        val events = JSONArray()
        val ids = mutableSetOf<String>()
        while (true) {
            require(
                page.optString("schema") == "road-guardian.demo-road-group.v1" && page.optString(
                    "source"
                ) == "synthetic"
            )
            require(page.opt("external_submission") == false && page.opt("repair_verified") == false)
            require(
                page.getString("group_id") == groupId && page.getString("membership_sha256") == membership && page.getInt(
                    "total"
                ) == total && page.getInt("offset") == events.length()
            )
            val rows = page.getJSONArray("events")
            require(rows.length() in 1..10 && events.length() + rows.length() <= total)
            for (i in 0 until rows.length()) {
                val event = rows.getJSONObject(i)
                require(
                    RoadDemoReceiptStore.eligible(event) && event.optString("status") != "pending_confirmation" && ids.add(
                        event.getString("id")
                    )
                )
                events.put(event)
            }
            if (events.length() == total) {
                require(page.isNull("next_offset"))
                break
            }
            require(page.getInt("next_offset") == events.length())
            page =
                JSONObject(client.request("/api/demo/road-summary/groups/$groupId/${events.length()}/$membership"))
        }
        return snapshot(groupId, membership, events)
    }

    private fun photos(event: JSONObject): Int {
        val hashes = mutableSetOf<String>()
        fun add(photo: JSONObject?) {
            if (photo == null) return
            val sha = photo.optString("sha256")
            if (sha.matches(Regex("[0-9a-f]{64}")) && photo.optString("mime") in setOf(
                    "image/png",
                    "image/jpeg"
                ) && photo.optInt("bytes") > 0 && photo.optInt("width") > 0 && photo.optInt("height") > 0
            ) hashes.add(sha)
        }

        val video = event.optJSONObject("video")
        video?.optJSONObject("input_photo")?.let { p ->
            if (video.opt("integrity_valid") == true && p.opt("integrity_valid") == true && p.optString(
                    "event_id"
                ) == event.getString("id") && p.optString("sensor_sha256") == event.getString("hash")
            ) add(p)
        }
        for (key in listOf("surround_views", "surround")) event.optJSONObject(key)?.let { media ->
            if (media.opt("integrity_valid") == true && media.optString("event_id") == event.getString(
                    "id"
                ) && media.optString("sensor_sha256") == event.getString("hash")
            ) {
                if (key == "surround_views") {
                    val views = media.optJSONArray("views")
                    if (views != null) for (i in 0 until views.length()) add(views.optJSONObject(i))
                } else add(media)
            }
        }
        return hashes.size
    }

    fun snapshot(groupId: String, membership: String, events: JSONArray): JSONObject {
        require(groupId.matches(Regex("[0-9a-f]{16}")) && membership.matches(Regex("[0-9a-f]{64}")) && events.length() in 1..1000)
        val sources = JSONArray()
        val ids = mutableSetOf<String>()
        val vehicles = mutableSetOf<String>()
        var photoCount = 0
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            require(
                RoadDemoReceiptStore.eligible(event) && event.optString("status") != "pending_confirmation" && ids.add(
                    event.getString("id")
                )
            )
            val data = event.getJSONObject("data")
            require(data.getJSONObject("location").length() > 0)
            vehicles.add(data.getString("vehicle_id"))
            val source = JSONObject().put("event_id", event.getString("id"))
                .put("sensor_sha256", event.getString("hash"))
            for (key in listOf("data", "video", "surround_views", "surround")) source.put(
                key,
                event.opt(key) ?: JSONObject.NULL
            )
            sources.put(source)
            photoCount += photos(event)
        }
        return JSONObject().put("schema", "road-guardian.agency-bundle.v1")
            .put("source", "synthetic").put("external_submission", false)
            .put("group_id", groupId).put("membership_sha256", membership)
            .put("observation_count", sources.length()).put("vehicle_count", vehicles.size)
            .put("photo_count", photoCount).put("sources", sources)
    }

    private fun directory(context: Context) =
        File(context.noBackupFilesDir, "road-agency-receipts").apply { mkdirs() }

    private fun path(context: Context, bundle: JSONObject): File {
        val id = bundle.getString("group_id")
        require(id.matches(Regex("[0-9a-f]{16}")))
        return File(directory(context), "$id-${digest(bundle)}.json")
    }

    private fun present(f: File) =
        listOf(f, File(f.path + ".bak"), File(f.path + ".new")).any { it.exists() }

    fun exists(context: Context, bundle: JSONObject): Boolean = present(path(context, bundle))
    private fun readFile(file: File): JSONObject? = runCatching {
        val bytes = AtomicFile(file).openRead().use { input ->
            val b = ByteArray(LIMIT + 1)
            var n = 0
            while (n < b.size) {
                val k = input.read(b, n, b.size - n)
                if (k < 0) break
                n += k
            }
            b.copyOf(n)
        }
        require(bytes.size in 1..LIMIT)
        val record = JSONObject(String(bytes, Charsets.UTF_8))
        val copy = JSONObject(record.toString())
        val sha = copy.getString("record_sha256")
        copy.remove("record_sha256")
        require(
            digest(copy) == sha && record.getString("bundle_sha256") == digest(
                record.getJSONObject(
                    "bundle"
                )
            )
        )
        require(
            record.getString("schema") == "road-guardian.agency-receipt.v1" && record.getString(
                "source"
            ) == "local_contest_simulation" && record.get("external_submission") == false
        )
        require(
            record.getString("recipient") == "road-management-agency-mock" && record.getString("receipt")
                .matches(Regex("MOCK-AG-[0-9A-F]{8}"))
        )
        record
    }.getOrNull()

    fun read(context: Context, bundle: JSONObject): JSONObject? = synchronized(lock) {
        val current = path(context, bundle)
        // 현재 버전이 손상되면 다른 기록으로 대신해 완료처럼 보이게 하지 않는다
        val file = if (present(current)) current else File(
            directory(context),
            bundle.getString("group_id") + ".json"
        )
        readFile(file)?.takeIf { it.getString("bundle_sha256") == digest(bundle) }
    }

    /** 원래 groupId.json을 남긴다. 손상되었으면 사용할 수 없음 항목으로 보여 준다 */
    fun archive(context: Context, groupId: String): List<JSONObject> = synchronized(lock) {
        require(groupId.matches(Regex("[0-9a-f]{16}")))
        val names = directory(context).listFiles().orEmpty()
            .map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            .filter { it == "$groupId.json" || it.matches(Regex("$groupId-[0-9a-f]{64}\\.json")) }
            .distinct().sorted()
        names.map { name ->
            val record = readFile(File(directory(context), name))?.takeIf {
                it.getJSONObject("bundle").optString("group_id") == groupId
            }
            JSONObject().put("available", record != null).put("record", record ?: JSONObject.NULL)
        }
    }

    fun submit(context: Context, bundle: JSONObject): JSONObject = synchronized(lock) {
        require(
            BuildConfig.SYNTHETIC_BACKEND && bundle.optString("source") == "synthetic" && bundle.opt(
                "external_submission"
            ) == false
        )
        read(context, bundle)?.let { return@synchronized it }
        check(
            !exists(
                context,
                bundle
            )
        ) { "Stored receipt or its source bundle changed; preserve previous record" }
        val record = JSONObject().put("schema", "road-guardian.agency-receipt.v1")
            .put("source", "local_contest_simulation").put("external_submission", false)
            .put("recipient", "road-management-agency-mock")
            .put("receipt", "MOCK-AG-${UUID.randomUUID().toString().take(8).uppercase()}")
            .put("created", System.currentTimeMillis() / 1000.0)
            .put("bundle", JSONObject(bundle.toString())).put("bundle_sha256", digest(bundle))
        record.put("record_sha256", digest(record))
        val bytes = canonical(record).toByteArray(Charsets.UTF_8)
        require(bytes.size <= LIMIT)
        val file = AtomicFile(path(context, bundle))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (t: Throwable) {
            file.failWrite(stream)
            throw t
        }
        checkNotNull(read(context, bundle))
    }
}
