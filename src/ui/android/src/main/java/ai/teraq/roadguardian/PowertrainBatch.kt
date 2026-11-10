package ai.teraq.roadguardian

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 제공자가 준 배열을 각각 보관한다. 12V 진단 입력으로 쓰지 않는다 */
data class PowertrainBatch(val trips: List<Trip>, val retrievedAt: Double) {
    data class Signal(
        val label: String,
        val unit: String,
        val values: List<Double?>,
        val invalid: Int,
        val returned: Boolean
    )

    data class Trip(val start: String, val durationMs: Long, val signals: List<Signal>)
    companion object {
        fun checkedDates(start: String, end: String): Pair<LocalDate, LocalDate> {
            require(start.matches(Regex("[0-9]{8}")) && end.matches(Regex("[0-9]{8}")))
            val first = LocalDate.parse(start, DateTimeFormatter.BASIC_ISO_DATE)
            val last = LocalDate.parse(end, DateTimeFormatter.BASIC_ISO_DATE)
            require(
                first.year in 1..9999 && last.year in 1..9999 && ChronoUnit.DAYS.between(
                    first,
                    last
                ) in 0..7
            )
            return first to last
        }

        fun parse(
            raw: String,
            eventId: String,
            vehicleId: String,
            start: String,
            end: String
        ): PowertrainBatch {
            try {
                val dates = checkedDates(start, end)
                val wrapper = JSONObject(raw)
                require(
                    wrapper.getString("schema") == "road-guardian.case-powertrain.v1" && wrapper.getString(
                        "event_id"
                    ) == eventId
                )
                require(
                    wrapper.getString("scope") == "current_subject_only" && wrapper.getString("start_date") == start && wrapper.getString(
                        "end_date"
                    ) == end
                )
                val batch = wrapper.getJSONObject("batch")
                require(
                    batch.getString("schema") == "road-guardian.powertrain-import.v1" && batch.getString(
                        "vehicle_id"
                    ) == vehicleId
                )
                require(batch.getString("transport") == "https_adapter" && batch.getString("provenance") == "external_response_not_attested")
                require(batch.getString("raw_sha256").matches(Regex("[0-9a-f]{64}")))
                for (key in listOf(
                    "diagnosis_performed",
                    "repair_confirmed",
                    "comparison_ready"
                )) require(batch.get(key) == false)
                val missing = batch.getJSONArray("missing_conditions")
                require(
                    (0 until missing.length()).map { missing.getString(it) }.toSet().containsAll(
                        setOf(
                            "per_sample_time",
                            "signal_synchronization",
                            "vehicle_speed",
                            "battery_state_of_charge",
                            "electrical_load",
                            "sensor_calibration"
                        )
                    )
                )
                val retrieved = batch.getDouble("retrieved_at")
                require(retrieved.isFinite() && retrieved > 0)
                val rows = batch.getJSONArray("trips")
                require(rows.length() <= 1024)
                var count = 0
                val starts = mutableSetOf<Instant>()
                val trips = (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    val started = row.getString("started_at")
                    require(started.endsWith("Z"))
                    val instant = Instant.parse(started)
                    require(starts.add(instant))
                    val day = instant.atOffset(ZoneOffset.UTC).toLocalDate()
                    require(day >= dates.first && day <= dates.second)
                    val duration = row.get("duration_ms")
                    require(
                        duration is Number && duration.toDouble() == duration.toLong()
                            .toDouble() && duration.toLong() in 0..604800000
                    )
                    require(
                        row.has("sample_timestamps") && row.isNull("sample_timestamps") && row.has(
                            "sample_hz"
                        ) && row.isNull("sample_hz") && row.get("aligned_samples") == false
                    )
                    val signals = row.getJSONObject("signals")
                    val definitions = listOf(
                        Triple("hv_pack_voltage_v", "고전압 팩 전압", "V"),
                        Triple("engine_rpm", "엔진 회전수", "rpm"),
                        Triple("engine_oil_temperature_c", "엔진 오일 온도", "°C")
                    )
                    Trip(started, duration.toLong(), definitions.mapIndexed { i, definition ->
                        val item = signals.getJSONObject(definition.first)
                        val state = item.getString("state")
                        require(state in setOf("returned", "not_returned"))
                        val values = item.getJSONArray("values")
                        count += values.length()
                        require(count <= 100000)
                        val invalid = item.get("invalid_count")
                        require(invalid is Int && invalid in 0..values.length())
                        val maximum = listOf(6553.0, 16383.5, 150.5)[i]
                        val samples = (0 until values.length()).map { position ->
                            if (values.isNull(position)) null else {
                                val value = values.get(position)
                                require(value is Number)
                                value.toDouble()
                                    .also { require(it.isFinite() && it in 0.0..maximum) }
                            }
                        }
                        require(invalid <= samples.count { it == null })
                        require(state != "not_returned" || (samples.isEmpty() && invalid == 0))
                        Signal(
                            definition.second,
                            definition.third,
                            samples,
                            invalid,
                            state == "returned"
                        )
                    })
                }
                return PowertrainBatch(trips, retrieved)
            } catch (_: Exception) {
                throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
            }
        }
    }
}
