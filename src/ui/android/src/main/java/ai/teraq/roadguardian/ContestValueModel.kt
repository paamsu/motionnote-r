package ai.teraq.roadguardian

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 공모전용으로 작성한 데이터. 고객 사건 저장소, 실제 진단과 분리한다 */
object ContestValueModel {
    fun hash(raw: String) =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun observation(
        id: String,
        vehicle: String,
        model: String,
        voltage: Double
    ): JSONObject {
        val raw = JSONObject().put("source", "synthetic").put("id", id).put("vehicle", vehicle)
            .put("model", model)
            .put("condition", "idle-800rpm-20C").put("voltage_v", voltage).put("baseline_v", 14.2)
            .put("observed_at", "2026-11-04T09:${if (id == "A-1") "00" else "10"}:00+09:00")
        return JSONObject().put("raw", raw).put("sha256", hash(raw.toString()))
    }

    fun create(stage: Int, reserved: Boolean): JSONObject {
        require(stage in 0..4 && (!reserved || stage == 4))
        val rows = JSONArray().put(observation("A-1", "customer-A", "Genesis G80", 11.8))
            .put(observation("A-2", "customer-A", "Genesis G80", 11.9))
            .put(observation("B-1", "customer-B", "Genesis G80", 12.0))
            .put(observation("C-1", "customer-C", "Genesis G80", 14.2))
            .put(observation("D-1", "customer-D", "Hyundai Sonata", 11.8))
        val current = observation("A-current", "customer-A", "Genesis G80", 14.2)
        current.getJSONObject("raw").put("observed_at", "2026-11-04T10:00:00+09:00")
            .put(
                "dtc_observation",
                JSONObject().put("source", "authored_synthetic_scan")
                    .put("status", "scanned_no_codes").put("codes", JSONArray())
            )
        current.put("sha256", hash(current.getJSONObject("raw").toString()))
        val refs = JSONArray()
        val vehicles = linkedSetOf<String>()
        var normal = 0
        var other = 0
        for (i in 0 until rows.length()) {
            val item = rows.getJSONObject(i)
            val raw = item.getJSONObject("raw")
            if (raw.getString("model") != "Genesis G80") other++ else if (raw.getDouble("voltage_v") >= raw.getDouble(
                    "baseline_v"
                ) - 1
            ) normal++
            if (raw.getString("model") == "Genesis G80" && raw.getString("condition") == "idle-800rpm-20C" && raw.getDouble(
                    "voltage_v"
                ) < raw.getDouble("baseline_v") - 1
            ) {
                refs.put(
                    JSONObject().put("id", raw.getString("id"))
                        .put("sha256", item.getString("sha256"))
                )
                vehicles.add(raw.getString("vehicle"))
            }
        }
        val group = JSONObject().put("model", "Genesis G80").put("condition", "idle-800rpm-20C")
            .put("evidence", refs).put("vehicle_count", vehicles.size)
            .put("observation_count", refs.length())
            .put("excluded_normal", normal).put("excluded_other_model", other)
            .put("defect_confirmed", false)
        group.put("id", hash(group.toString()))
        val original = rows.getJSONObject(0)
        return JSONObject().put("schema", "road-guardian.contest-value.v2")
            .put("source", "authored_synthetic")
            .put("external_submission", false).put("stage", stage).put("reserved", reserved)
            .put("observations", rows).put("current", current).put("group", group)
            .put(
                "center",
                if (stage >= 2) JSONObject().put("recipient", "genesis-service-mock")
                    .put("receipt", "MOCK-CENTER-A1").put("name", "서초 서비스센터")
                    .put("evidence_id", "A-1").put("evidence_sha256", original.getString("sha256"))
                    .put("current_sha256", current.getString("sha256")) else JSONObject.NULL
            )
            .put(
                "notice",
                if (stage >= 4) JSONObject().put("id", "MOCK-NOTICE-A1")
                    .put("vehicle", "customer-A")
                    .put("group_id", group.getString("id")) else JSONObject.NULL
            )
            .put(
                "reservation",
                if (reserved) JSONObject().put("receipt", "MOCK-BOOK-A1")
                    .put("center_name", "서초 서비스센터").put("appointment", "11월 6일 10:00")
                    .put("notice_id", "MOCK-NOTICE-A1")
                    .put("group_id", group.getString("id")).put("center_receipt", "MOCK-CENTER-A1")
                    .put("evidence_sha256", original.getString("sha256")) else JSONObject.NULL
            )
    }

    private fun file(context: Context) = AtomicFile(
        File(
            context.noBackupFilesDir,
            "contest-value-v631/state-v2.json"
        ).apply { parentFile?.mkdirs() })

    fun load(context: Context): JSONObject {
        val f = file(context)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return create(
            0,
            false
        )
        val bytes = f.openRead().use { it.readBytes() }
        require(bytes.size <= 65536)
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val raw = envelope.getString("payload")
        require(hash(raw) == envelope.getString("sha256"))
        val record = JSONObject(raw)
        val expected = create(record.getInt("stage"), record.getBoolean("reserved"))
        require(raw == expected.toString())
        return record
    }

    fun save(context: Context, stage: Int, reserved: Boolean): JSONObject {
        // 다음 단계로 가기 전에 저장된 원본을 확인한다. 손상되었으면 그대로 알린다
        val previous = load(context)
        require(stage >= previous.getInt("stage") && (!previous.getBoolean("reserved") || reserved))
        val record = create(stage, reserved)
        val raw = record.toString()
        val bytes = JSONObject().put("payload", raw).put("sha256", hash(raw)).toString()
            .toByteArray(Charsets.UTF_8)
        val f = file(context)
        val stream = f.startWrite()
        try {
            stream.write(bytes)
            f.finishWrite(stream)
        } catch (t: Throwable) {
            f.failWrite(stream)
            throw t
        }
        return load(context)
    }
}
