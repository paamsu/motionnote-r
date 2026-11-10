package ai.teraq.roadguardian

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlin.math.abs

/** 불러온 파일과 개인 영수증을 엄격하게 검사한다. 로컬 해시는 OEM 인증으로 쓰지 않는다 */
object MeasurementProtocol {
    val conditions = linkedMapOf(
        "speed_kph" to .5,
        "rpm" to 100.0,
        "battery_temperature_c" to 2.0,
        "ambient_temperature_c" to 3.0,
        "state_of_charge_pct" to 2.0,
        "battery_current_a" to 2.0
    )
    private val units = mapOf(
        "voltage_v" to "V",
        "speed_kph" to "km/h",
        "rpm" to "rpm",
        "battery_temperature_c" to "degC",
        "ambient_temperature_c" to "degC",
        "state_of_charge_pct" to "percent",
        "battery_current_a" to "A"
    )

    data class Summary(
        val id: String,
        val hash: String,
        val environment: String,
        val channel: String,
        val started: Double,
        val count: Int
    )

    data class Receipt(val summary: Summary, val data: JSONObject)
    data class Comparison(val raw: JSONObject, val before: Receipt, val after: Receipt) {
        val matched get() = raw.getString("state") == "reported_conditions_matched"
    }

    fun id(value: String): String {
        require(UUID.fromString(value).toString() == value)
        return value
    }

    private fun keys(obj: JSONObject) = obj.keys().asSequence().toSet()
    private fun label(value: Any?) {
        require(value is String && value.length in 1..128 && value.none {
            Character.getType(it) == Character.CONTROL.toInt() || Character.getType(
                it
            ) == Character.SURROGATE.toInt()
        })
    }

    fun number(obj: JSONObject, key: String, nullable: Boolean = false): Double? {
        val value = obj.get(key)
        if (value == JSONObject.NULL) {
            require(nullable)
            return null
        }
        require(value is Number)
        return value.toDouble().also { require(it.isFinite()) }
    }

    private fun integer(obj: JSONObject, key: String, min: Int, max: Int): Int {
        val value = number(obj, key)!!
        require(value >= min && value <= max && value == value.toInt().toDouble())
        return value.toInt()
    }

    fun decode(bytes: ByteArray, maxBytes: Int = 256 * 1024): JSONObject {
        require(maxBytes in 1..1024 * 1024 && bytes.isNotEmpty() && bytes.size <= maxBytes)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            .toString()
        val reader = JsonReader(StringReader(text))
        reader.isLenient = false
        var tokens = 0
        fun read(depth: Int): Any {
            require(depth <= 12 && ++tokens <= 10000)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val obj = JSONObject()
                    reader.beginObject()
                    var count = 0
                    while (reader.hasNext()) {
                        require(++count <= 64)
                        val key = reader.nextName()
                        require(!obj.has(key))
                        obj.put(key, read(depth + 1))
                    }
                    reader.endObject()
                    obj
                }

                JsonToken.BEGIN_ARRAY -> {
                    val array = JSONArray()
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(array.length() < 512)
                        array.put(read(depth + 1))
                    }
                    reader.endArray()
                    array
                }

                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> {
                    val raw = reader.nextString()
                    require(raw.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")))
                    raw.toLongOrNull() ?: raw.toDouble().also { require(it.isFinite()) }
                }

                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> {
                    reader.nextNull()
                    JSONObject.NULL
                }

                else -> throw IllegalArgumentException("측정 JSON 형식을 확인하세요")
            }
        }
        return reader.use {
            val obj = read(0)
            require(obj is JSONObject && reader.peek() == JsonToken.END_DOCUMENT)
            obj
        }
    }

    fun validate(body: JSONObject, vehicle: String): JSONObject {
        require(
            keys(body) == setOf(
                "schema",
                "run_id",
                "vehicle_id",
                "environment",
                "channel",
                "instrument_id",
                "calibration_ref",
                "started_at",
                "sample_period_ms",
                "units",
                "samples"
            )
        )
        require(body.getString("schema") == "road-guardian.timed-voltage-run.v1")
        id(body.getString("run_id"))
        label(body.get("vehicle_id"))
        require(body.getString("vehicle_id") == vehicle)
        label(body.get("instrument_id"))
        if (!body.isNull("calibration_ref")) label(body.get("calibration_ref"))
        require(body.getString("environment") in setOf("synthetic", "external_unattested"))
        val channel = body.getString("channel")
        require(channel in setOf("auxiliary_12v", "hv_pack"))
        val actualUnits = body.getJSONObject("units")
        require(keys(actualUnits) == units.keys && units.all { actualUnits.get(it.key) == it.value })
        val start = number(body, "started_at")!!
        val now = System.currentTimeMillis() / 1000.0
        require(start > 0 && start <= now + 300)
        integer(body, "sample_period_ms", 100, 10000)
        val rows = body.getJSONArray("samples")
        require(rows.length() in 1..256)
        val ranges = mapOf(
            "voltage_v" to (0.0..if (channel == "auxiliary_12v") 60.0 else 6553.0),
            "speed_kph" to (0.0..400.0),
            "rpm" to (0.0..20000.0),
            "battery_temperature_c" to (-60.0..150.0),
            "ambient_temperature_c" to (-60.0..80.0),
            "state_of_charge_pct" to (0.0..100.0),
            "battery_current_a" to (-2000.0..2000.0)
        )
        var previous = -1
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(
                keys(row) == units.keys + setOf(
                    "elapsed_ms",
                    "gear",
                    "operating_mode",
                    "load_state"
                )
            )
            val elapsed = integer(row, "elapsed_ms", 0, 180000)
            require(elapsed > previous && (i != 0 || elapsed == 0))
            previous = elapsed
            for ((key, range) in ranges) number(row, key, true)?.let { require(it in range) }
            for ((key, allowed) in mapOf(
                "gear" to setOf("P", "R", "N", "D"),
                "operating_mode" to setOf("engine_idle", "vehicle_ready"),
                "load_state" to setOf("accessories_off", "fixed_test_load")
            ))
                require(row.isNull(key) || row.get(key) in allowed)
        }
        require(
            start + previous / 1000.0 <= now + 300 && body.toString()
                .toByteArray(Charsets.UTF_8).size <= 256 * 1024
        )
        return body
    }

    fun hash(raw: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> keys(a) == keys(b) && keys(a).all {
            same(
                a.get(it),
                b.get(it)
            )
        }

        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all {
            same(
                a.get(it),
                b.get(it)
            )
        }

        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    fun summary(obj: JSONObject, eventId: String): Summary {
        val identity = id(obj.getString("id"))
        val sha = obj.getString("sha256")
        require(sha.matches(Regex("[0-9a-f]{64}")))
        require(obj.getString("event_id") == eventId && obj.getString("scope") == "current_subject_only")
        require(obj.getString("hash_basis") == "canonical_validated_run_utf8" && obj.getString("provenance") == "reported_measurements_not_attested")
        require(
            obj.get("sensor_original") == false && obj.get("assessment_changed") == false && number(
                obj,
                "received_at"
            )!! > 0
        )
        val environment = obj.getString("environment")
        val channel = obj.getString("channel")
        require(
            environment in setOf(
                "synthetic",
                "external_unattested"
            ) && channel in setOf("auxiliary_12v", "hv_pack")
        )
        return Summary(
            identity,
            sha,
            environment,
            channel,
            number(obj, "started_at")!!.also { require(it > 0) },
            integer(obj, "sample_count", 1, 256)
        )
    }

    fun list(raw: String, eventId: String): List<Summary> {
        val obj = JSONObject(raw)
        require(
            obj.getString("schema") == "road-guardian.measurement-runs.v1" && obj.getString("event_id") == eventId && obj.getString(
                "scope"
            ) == "current_subject_only" && obj.get("limit") == 16
        )
        val rows = obj.getJSONArray("runs")
        require(rows.length() <= 16)
        val values = (0 until rows.length()).map { summary(rows.getJSONObject(it), eventId) }
        require(values.map { it.id }.distinct().size == values.size)
        return values
    }

    fun receipt(
        raw: String,
        eventId: String,
        vehicle: String,
        expected: JSONObject? = null
    ): Receipt {
        val obj = JSONObject(raw)
        val summary = summary(obj, eventId)
        val canonical = obj.getString("canonical_json")
        require(hash(canonical) == summary.hash)
        val data = validate(decode(canonical.toByteArray(Charsets.UTF_8)), vehicle)
        require(
            same(
                data,
                obj.getJSONObject("data")
            ) && data.getString("run_id") == summary.id && data.getString("environment") == summary.environment && data.getString(
                "channel"
            ) == summary.channel && data.getJSONArray("samples")
                .length() == summary.count && number(
                data,
                "started_at"
            ) == summary.started
        )
        if (expected != null) require(same(data, expected))
        return Receipt(summary, data)
    }

    fun comparison(
        raw: String,
        eventId: String,
        caseHash: String,
        before: Receipt,
        after: Receipt
    ): Comparison {
        val wrapper = JSONObject(raw)
        require(
            wrapper.getString("schema") == "road-guardian.case-condition-comparison.v1" && wrapper.getString(
                "event_id"
            ) == eventId && wrapper.getString("scope") == "current_subject_only" && wrapper.getString(
                "case_sha256"
            ) == caseHash
        )
        require(wrapper.getString("before_id") == before.summary.id && wrapper.getString("after_id") == after.summary.id)
        val result = wrapper.getJSONObject("comparison")
        require(
            result.getString("schema") == "road-guardian.condition-comparison.v1" && result.getString(
                "rule"
            ) == "reported-voltage-conditions-dev-v1"
        )
        require(
            result.getString("before_run_id") == before.summary.id && result.getString("after_run_id") == after.summary.id && result.getString(
                "before_sha256"
            ) == before.summary.hash && result.getString("after_sha256") == after.summary.hash
        )
        require(
            result.getString("hash_basis") == "canonical_validated_run_utf8" && result.getString(
                "provenance"
            ) == "reported_measurements_not_attested" && result.getString("scope") == "descriptive_voltage_difference_only" && result.getString(
                "voltage_unit"
            ) == "V"
        )
        require(
            result.getString("environment") == before.summary.environment && result.getString("channel") == before.summary.channel && result.getString(
                "tolerance_authority"
            ) == "development_only_not_oem_criteria"
        )
        for (key in listOf(
            "conditions_verified",
            "repair_confirmed",
            "diagnosis_performed"
        )) require(result.get(key) == false)
        val missing = result.getJSONArray("unverified")
        require(
            (0 until missing.length()).map { missing.getString(it) }.toSet().containsAll(
                setOf(
                    "vehicle_identity",
                    "instrument_calibration_validity",
                    "condition_accuracy",
                    "capture_clock",
                    "repair_action",
                    "causal_attribution"
                )
            )
        )
        val tolerances = result.getJSONObject("tolerances")
        require(keys(tolerances) == conditions.keys && conditions.all {
            number(
                tolerances,
                it.key
            ) == it.value
        })
        val state = result.getString("state")
        require(state in setOf("not_comparable", "reported_conditions_matched"))
        val pairs = result.getJSONArray("matched_pairs")
        val reasons = result.getJSONArray("reasons")
        for (i in 0 until reasons.length()) require(
            reasons.get(i) is String && reasons.getString(i).matches(Regex("[a-z0-9_]{1,96}"))
        )
        if (state == "not_comparable") {
            require(result.has("metrics") && result.isNull("metrics") && pairs.length() == 0 && reasons.length() in 1..32)
            return Comparison(result, before, after)
        }
        require(reasons.length() == 0 && before.summary.id != after.summary.id)
        for (key in listOf(
            "vehicle_id",
            "environment",
            "channel",
            "instrument_id",
            "sample_period_ms",
            "calibration_ref"
        )) require(!before.data.isNull(key) && same(before.data.get(key), after.data.get(key)))
        val left = before.data.getJSONArray("samples")
        val right = after.data.getJSONArray("samples")
        val period = number(before.data, "sample_period_ms")!!
        for (rows in listOf(left, right)) {
            require(
                rows.length() >= 20 && number(
                    rows.getJSONObject(rows.length() - 1),
                    "elapsed_ms"
                )!! >= 10000
            )
            for (i in 1 until rows.length()) require(
                abs(
                    number(
                        rows.getJSONObject(i),
                        "elapsed_ms"
                    )!! - number(rows.getJSONObject(i - 1), "elapsed_ms")!! - period
                ) <= period * .1
            )
        }
        require(
            number(before.data, "started_at")!! + number(
                left.getJSONObject(left.length() - 1),
                "elapsed_ms"
            )!! / 1000 < number(after.data, "started_at")!!
        )
        require(
            pairs.length() >= 20 && pairs.length()
                .toDouble() / left.length() >= .8 && pairs.length()
                .toDouble() / right.length() >= .8
        )
        val seenLeft = mutableSetOf<Int>()
        val seenRight = mutableSetOf<Int>()
        val aValues = mutableListOf<Double>()
        val bValues = mutableListOf<Double>()
        val maxDifferences = conditions.mapValues { 0.0 }.toMutableMap()
        for (i in 0 until pairs.length()) {
            val pair = pairs.getJSONArray(i)
            require(pair.length() == 2 && pair.get(0) is Int && pair.get(1) is Int)
            val a = pair.getInt(0)
            val b = pair.getInt(1)
            require(
                a in 0 until left.length() && b in 0 until right.length() && seenLeft.add(a) && seenRight.add(
                    b
                )
            )
            val first = left.getJSONObject(a)
            val second = right.getJSONObject(b)
            for (row in listOf(first, second)) require(
                row.get("gear") == "P" && number(
                    row,
                    "speed_kph"
                )!! <= 1 && !row.isNull("operating_mode") && !row.isNull("load_state")
            )
            for (key in listOf(
                "operating_mode",
                "load_state"
            )) require(first.get(key) == second.get(key))
            for ((key, tolerance) in conditions) {
                val difference = abs(number(first, key)!! - number(second, key)!!)
                require(difference <= tolerance)
                maxDifferences[key] = maxOf(maxDifferences.getValue(key), difference)
            }
            aValues.add(number(first, "voltage_v")!!)
            bValues.add(number(second, "voltage_v")!!)
        }
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        }

        val metrics = result.getJSONObject("metrics")
        fun metric(key: String, value: Double) {
            require(abs(number(metrics, key)!! - value) < .00001)
        }
        metric("before_median_v", median(aValues))
        metric("after_median_v", median(bValues))
        metric("paired_delta_median_v", median(aValues.zip(bValues).map { it.second - it.first }))
        require(integer(metrics, "matched_samples", 20, 256) == pairs.length())
        metric("before_coverage", pairs.length().toDouble() / left.length())
        metric("after_coverage", pairs.length().toDouble() / right.length())
        for ((key, values) in listOf("before_range_v" to aValues, "after_range_v" to bValues)) {
            val range = metrics.getJSONArray(key)
            require(range.length() == 2 && range.get(0) is Number && range.get(1) is Number)
            require(abs(range.getDouble(0) - values.min()) < .00001 && abs(range.getDouble(1) - values.max()) < .00001)
        }
        val maximum = result.getJSONObject("condition_differences_max")
        require(keys(maximum) == conditions.keys && maxDifferences.all {
            abs(
                number(
                    maximum,
                    it.key
                )!! - it.value
            ) < .00001
        })
        return Comparison(result, before, after)
    }
}
