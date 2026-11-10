package ai.teraq.roadguardian

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 로컬 시연 준비 데이터만 저장한다. 연락, 예약, 외부 접수는 하지 않는다 */
object DemoActionDraftStore {
    private const val SCHEMA = "road-guardian.demo-action-draft.v1"
    private const val MAX_BYTES = 16384
    private val lock = Any()
    private val fields = setOf(
        "schema",
        "source",
        "event_id",
        "sensor_sha256",
        "action",
        "fingerprint",
        "state",
        "created",
        "external_submission",
        "record_sha256"
    )

    fun eligibleAction(event: JSONObject): String? {
        if (!BuildConfig.SYNTHETIC_BACKEND || event.opt("source") != "synthetic" || event.opt("evidence_valid") != true) return null
        if (ServiceDemoRecommendation.present(event) && ServiceDemoRecommendation.checked(event) == null) return null
        val id = event.opt("id") as? String ?: return null
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                .not()
        ) return null
        val hash = event.opt("hash") as? String ?: return null
        if (!hash.matches(Regex("[0-9a-f]{64}")) || EventBriefState.from(event).action == null) return null
        return when (event.optJSONObject("assessment")?.optString("kind")) {
            "crash", "parking" -> if ((event.optString("status") in setOf(
                    "evidence_ready",
                    "dispatch_ready"
                ) || (event.optJSONObject("assessment")
                    ?.optString("kind") == "parking" && event.optString("status") == "acknowledged")) &&
                event.optJSONObject("insurance_case")
                    ?.let { it.opt("source") == "synthetic" && it.opt("status") == "ready" } == true
            ) "insurance" else null

            "diagnosis" -> if (DiagnosticEvidenceReview.checkedCase(event)
                    ?.optString("stage") == "observed_signal_recovered" &&
                event.optJSONObject("assessment")?.optJSONObject("diagnostic")
                    ?.optString("verdict") == "inspection_required"
            ) "service_handoff"
            else if ((DiagnosticEvidenceReview.checkedCase(event)
                    ?.optString("stage") == "follow_up_required") ||
                (event.optString("status") == "evidence_ready" && event.opt("diagnostic_valid") == true &&
                        event.optJSONObject("repair_case")
                            ?.let { it.opt("source") == "synthetic" && it.opt("stage") == "measurement_review" } == true &&
                        event.optJSONObject("assessment")?.optJSONObject("diagnostic")
                            ?.optString("verdict") == "inspection_required")
            ) "service" else null

            else -> null
        }
    }

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
        else -> error("Unsupported draft value")
    }

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun fingerprint(event: JSONObject): String? =
        runCatching { eligibleAction(event)?.let { fingerprint(event, it) } }.getOrNull()

    private fun fingerprint(event: JSONObject, action: String): String {
        val state = JSONObject().put("source", event.get("source")).put("event_id", event.get("id"))
            .put("sensor_sha256", event.get("hash"))
            .put("status", event.get("status")).put("action", action)
            .put("evidence_valid", event.get("evidence_valid"))
        if (action == "insurance") {
            state.put("insurance_case", event.getJSONObject("insurance_case"))
            if (event.getJSONObject("assessment")
                    .optString("kind") == "parking"
            ) for (key in listOf("video", "surround_views", "surround")) state.put(
                key,
                event.opt(key) ?: JSONObject.NULL
            )
        } else state.put("diagnostic_valid", event.get("diagnostic_valid"))
            .put("repair_case", event.getJSONObject("repair_case"))
            .put("diagnostic", event.getJSONObject("assessment").getJSONObject("diagnostic"))
        if (action in setOf("service", "service_handoff") && event.optJSONObject("repair_case")
                ?.optString("stage") in setOf("follow_up_required", "observed_signal_recovered")
        )
            state.put("post_repair", event.getJSONObject("repair").getJSONObject("post_repair"))
        ServiceDemoRecommendation.checked(event)?.let { state.put("service_target", it) }
        return hash(canonical(state))
    }

    private fun file(context: Context, event: JSONObject, fingerprint: String) =
        File(context.noBackupFilesDir, "demo-action-${event.getString("id")}-${fingerprint}.json")

    fun hasRecord(context: Context, event: JSONObject): Boolean = synchronized(lock) {
        val fingerprint = fingerprint(event) ?: return@synchronized false
        val base = file(context, event, fingerprint)
        base.exists() || File(base.path + ".bak").exists() || File(base.path + ".new").exists()
    }

    fun read(context: Context, event: JSONObject): JSONObject? = synchronized(lock) {
        runCatching {
            val action = eligibleAction(event) ?: return@synchronized null
            val fingerprint = fingerprint(event, action)
            val bytes = AtomicFile(file(context, event, fingerprint)).openRead().use { input ->
                val buffer = ByteArray(MAX_BYTES + 1)
                var count = 0
                while (count < buffer.size) {
                    val next = input.read(buffer, count, buffer.size - count)
                    if (next < 0) break
                    count += next
                }
                buffer.copyOf(count)
            }
            require(bytes.size in 1..MAX_BYTES)
            val stored = JSONObject(bytes.toString(Charsets.UTF_8))
            val serviceTarget = ServiceDemoRecommendation.checked(event)
            require(
                stored.keys().asSequence()
                    .toSet() == (if (serviceTarget != null) fields + "service_target" else fields)
            )
            if (serviceTarget != null) require(
                canonical(stored.getJSONObject("service_target")) == canonical(
                    serviceTarget
                )
            )
            require(
                stored.opt("schema") == SCHEMA && stored.opt("source") == "synthetic" && stored.opt(
                    "external_submission"
                ) == false
            )
            require(
                stored.opt("event_id") == event.getString("id") && stored.opt("sensor_sha256") == event.getString(
                    "hash"
                )
            )
            require(
                stored.opt("action") == action && stored.opt("fingerprint") == fingerprint && stored.opt(
                    "state"
                ) in setOf("prepared", "deferred")
            )
            val created = stored.opt("created")
            require(created is Number && created.toDouble().isFinite() && created.toDouble() > 0)
            val digest = stored.getString("record_sha256")
            stored.remove("record_sha256")
            require(hash(canonical(stored)) == digest)
            stored.put("record_sha256", digest)
        }.getOrNull()
    }

    fun prepare(context: Context, event: JSONObject): JSONObject = write(context, event, "prepared")
    fun defer(context: Context, event: JSONObject): JSONObject = write(context, event, "deferred")
    private fun write(context: Context, event: JSONObject, state: String): JSONObject =
        synchronized(lock) {
            val action =
                checkNotNull(eligibleAction(event)) { "This event is not eligible for a synthetic draft" }
            val fingerprint = fingerprint(event, action)
            val previous = read(context, event)
            if (previous?.optString("state") == state) return@synchronized previous
            val destination = file(context, event, fingerprint)
            check(
                previous != null || !hasRecord(
                    context,
                    event
                )
            ) { "Existing synthetic draft is unreadable; preserved without replacement" }
            val stored = JSONObject().put("schema", SCHEMA).put("source", "synthetic")
                .put("event_id", event.getString("id"))
                .put("sensor_sha256", event.getString("hash")).put("action", action)
                .put("fingerprint", fingerprint)
                .put("state", state).put("created", System.currentTimeMillis() / 1000.0)
                .put("external_submission", false)
            ServiceDemoRecommendation.checked(event)?.let { stored.put("service_target", it) }
            stored.put("record_sha256", hash(canonical(stored)))
            val bytes = canonical(stored).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES)
            val atomic = AtomicFile(destination)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
            checkNotNull(read(context, event)) { "Saved synthetic draft could not be verified" }
        }
}
