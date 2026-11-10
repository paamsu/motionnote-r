package ai.teraq.roadguardian

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 사건별 로컬 시연 접수 기록. 기관이나 보상 서비스에 연락하지 않는다 */
object RoadDemoReceiptStore {
    private const val SCHEMA = "road-guardian.road-demo-receipt.v1"
    private const val LIMIT = 1_048_576
    private val lock = Any()
    private val actions = setOf("report", "compensation")
    private val fields = setOf(
        "schema",
        "source",
        "external_submission",
        "event_id",
        "sensor_sha256",
        "action",
        "recipient",
        "receipt",
        "created",
        "evidence_bundle",
        "bundle_sha256",
        "record_sha256"
    )

    private fun hash(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }

        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
            canonical(
                value.get(
                    it
                )
            )
        }

        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> JSONObject.numberToString(value)
        else -> error("Unsupported receipt value")
    }

    fun eligible(event: JSONObject): Boolean = runCatching {
        require(BuildConfig.SYNTHETIC_BACKEND && event.opt("source") == "synthetic" && event.opt("evidence_valid") == true)
        require(event.optString("status") !in setOf("dismissed", "input_review_required"))
        require(event.getJSONObject("assessment").getString("kind") == "pothole")
        val id = event.getString("id")
        require(UUID.fromString(id).toString() == id)
        require(Regex("[0-9a-f]{64}").matches(event.getString("hash")))
        require(event.getJSONObject("data").optString("source") == "synthetic")
        for (field in listOf("video", "surround_views", "surround")) event.optJSONObject(field)
            ?.let {
                require(it.opt("integrity_valid") == true)
                if (it.has("event_id")) require(it.optString("event_id") == id)
                if (it.has("sensor_sha256")) require(
                    it.optString("sensor_sha256") == event.getString(
                        "hash"
                    )
                )
            }
        true
    }.getOrDefault(false)

    private fun bundle(event: JSONObject): JSONObject {
        require(eligible(event))
        val result = JSONObject().put("event_id", event.getString("id"))
            .put("sensor_sha256", event.getString("hash"))
        // 표시 상태 대신 근거 목록(주소, 미디어별 해시)을 보존한다
        for (field in listOf("data", "video", "surround_views", "surround")) result.put(
            field,
            event.opt(field) ?: JSONObject.NULL
        )
        return JSONObject(result.toString())
    }

    fun createRecord(event: JSONObject, action: String): JSONObject {
        require(action in actions)
        val evidence = bundle(event)
        val result = JSONObject().put("schema", SCHEMA).put("source", "local_contest_simulation")
            .put("external_submission", false)
            .put("event_id", event.getString("id")).put("sensor_sha256", event.getString("hash"))
            .put("action", action)
            .put(
                "recipient",
                if (action == "report") "safety-report-mock" else "road-compensation-mock"
            )
            .put(
                "receipt",
                "MOCK-${if (action == "report") "SR" else "RC"}-${
                    UUID.randomUUID().toString().take(8).uppercase()
                }"
            )
            .put("created", System.currentTimeMillis() / 1000.0).put("evidence_bundle", evidence)
            .put("bundle_sha256", hash(canonical(evidence)))
        return result.put("record_sha256", hash(canonical(result)))
    }

    fun checked(record: JSONObject, event: JSONObject, action: String): JSONObject? = runCatching {
        require(action in actions && eligible(event))
        require(record.keys().asSequence().toSet() == fields)
        require(
            record.opt("schema") == SCHEMA && record.opt("source") == "local_contest_simulation" && record.opt(
                "external_submission"
            ) == false
        )
        require(
            record.opt("event_id") == event.getString("id") && record.opt("sensor_sha256") == event.getString(
                "hash"
            ) && record.opt("action") == action
        )
        require(record.opt("recipient") == if (action == "report") "safety-report-mock" else "road-compensation-mock")
        require(
            Regex("MOCK-${if (action == "report") "SR" else "RC"}-[0-9A-F]{8}").matches(
                record.getString(
                    "receipt"
                )
            )
        )
        val created = record.get("created")
        require(created is Number && created.toDouble().isFinite() && created.toDouble() > 0)
        val expected = hash(canonical(bundle(event)))
        require(
            record.getString("bundle_sha256") == expected && hash(
                canonical(
                    record.getJSONObject(
                        "evidence_bundle"
                    )
                )
            ) == expected
        )
        val copy = JSONObject(record.toString())
        val digest = copy.getString("record_sha256")
        copy.remove("record_sha256")
        require(hash(canonical(copy)) == digest)
        JSONObject(record.toString())
    }.getOrNull()

    private fun path(context: Context, event: JSONObject, action: String): File {
        require(eligible(event) && action in actions)
        return File(
            File(context.noBackupFilesDir, "road-demo-receipts").apply { mkdirs() },
            "${event.getString("id")}-${event.getString("hash")}-${action}.json"
        )
    }

    fun hasRecord(context: Context, event: JSONObject, action: String): Boolean = runCatching {
        val file = path(context, event, action)
        file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()
    }.getOrDefault(false)

    fun read(context: Context, event: JSONObject, action: String): JSONObject? =
        synchronized(lock) {
            runCatching {
                val bytes = AtomicFile(path(context, event, action)).openRead().use { input ->
                    val buffer = ByteArray(LIMIT + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val next = input.read(buffer, count, buffer.size - count)
                        if (next < 0) break
                        count += next
                    }
                    buffer.copyOf(count)
                }
                require(bytes.size in 1..LIMIT)
                checked(JSONObject(bytes.toString(Charsets.UTF_8)), event, action)
            }.getOrNull()
        }

    fun submit(context: Context, event: JSONObject, action: String): JSONObject =
        synchronized(lock) {
            read(context, event, action)?.let { return@synchronized it }
            check(
                !hasRecord(
                    context,
                    event,
                    action
                )
            ) { "Existing receipt could not be verified; preserved without replacement" }
            val record = createRecord(event, action)
            val bytes = canonical(record).toByteArray(Charsets.UTF_8)
            require(bytes.size <= LIMIT)
            val file = AtomicFile(path(context, event, action))
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            checkNotNull(read(context, event, action)) { "Receipt read-back failed" }
        }
}
