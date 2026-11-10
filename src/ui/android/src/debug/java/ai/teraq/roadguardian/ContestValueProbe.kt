package ai.teraq.roadguardian

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Isolated store checks only: no Activity, live data, networking, or production storage. */
class ContestValueProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var checks = 0
        fun verify(value: Boolean, label: String) {
            check(value) { label }
            checks++
        }
        try {
            check(BuildConfig.DEBUG && BuildConfig.SYNTHETIC_BACKEND)
            val root = File(
                targetContext.cacheDir,
                "contest-value-probe-${UUID.randomUUID()}"
            ).apply { mkdirs() }
            val isolated = object : ContextWrapper(targetContext) {
                override fun getNoBackupFilesDir() = root
            }
            val initial = ContestValueModel.load(isolated)
            verify(initial.getInt("stage") == 0, "fresh fixture")
            val group = initial.getJSONObject("group")
            verify(
                group.getInt("vehicle_count") == 2 && group.getInt("observation_count") == 3,
                "2 vehicles / 3 observations"
            )
            verify(
                group.getInt("excluded_normal") == 1 && group.getInt("excluded_other_model") == 1,
                "normal/other model excluded"
            )
            val refs = group.getJSONArray("evidence")
            verify((0 until refs.length()).map { refs.getJSONObject(it).getString("id") }
                .toSet() == setOf("A-1", "A-2", "B-1"), "selected source IDs")
            val dtc = initial.getJSONObject("current").getJSONObject("raw")
                .getJSONObject("dtc_observation")
            verify(
                dtc.getString("source") == "authored_synthetic_scan" && dtc.getString("status") == "scanned_no_codes" && dtc.getJSONArray(
                    "codes"
                ).length() == 0, "explicit synthetic DTC scan"
            )
            val raw = initial.getJSONArray("observations").toString()
            val source = initial.getJSONArray("observations").getJSONObject(0)
            val sourceHash = source.getString("sha256")
            verify(
                sourceHash == ContestValueModel.hash(source.getJSONObject("raw").toString()),
                "raw digest"
            )
            var saved = initial
            for (stage in 1..4) {
                saved = ContestValueModel.save(isolated, stage, false)
                verify(
                    saved.getJSONArray("observations").toString() == raw,
                    "original unchanged at stage $stage"
                )
            }
            val center = saved.getJSONObject("center")
            verify(
                center.getString("evidence_id") == "A-1" && center.getString("evidence_sha256") == sourceHash && center.getString(
                    "current_sha256"
                ) == saved.getJSONObject("current").getString("sha256"),
                "center bound to before/current"
            )
            verify(refs.getJSONObject(0).getString("sha256") == sourceHash, "group source digest")
            val notice = saved.getJSONObject("notice")
            verify(
                notice.getString("vehicle") == "customer-A" && notice.getString("group_id") == group.getString(
                    "id"
                ), "notice target and group"
            )
            saved = ContestValueModel.save(isolated, 4, true)
            val booking = saved.getJSONObject("reservation")
            verify(
                booking.getString("notice_id") == notice.getString("id") && booking.getString("group_id") == group.getString(
                    "id"
                ) && booking.getString("center_receipt") == center.getString("receipt") && booking.getString(
                    "evidence_sha256"
                ) == sourceHash, "reservation chain"
            )
            verify(
                ContestValueModel.load(isolated).toString() == saved.toString(),
                "reopen same saved record"
            )
            verify(
                ContestValueModel.save(isolated, 4, true).toString() == saved.toString(),
                "repeat same reservation"
            )
            verify(
                !saved.getBoolean("external_submission") && saved.getString("source") == "authored_synthetic" && !saved.getJSONObject(
                    "group"
                ).getBoolean("defect_confirmed"), "simulation metadata"
            )
            val file = File(root, "contest-value-v631/state-v2.json")
            val original = file.readBytes()
            File(root, "original-state.json").writeBytes(original)
            val envelope = JSONObject(String(original, Charsets.UTF_8))
            val altered = JSONObject(envelope.getString("payload"))
            altered.getJSONObject("center").put("evidence_sha256", "0".repeat(64))
            val changed = altered.toString()
            // Recompute envelope digest too: semantic fixture validation must still reject.
            val damaged =
                JSONObject().put("payload", changed).put("sha256", ContestValueModel.hash(changed))
                    .toString().toByteArray(Charsets.UTF_8)
            file.writeBytes(damaged)
            verify(
                runCatching { ContestValueModel.load(isolated) }.isFailure,
                "changed source link rejected"
            )
            verify(
                runCatching { ContestValueModel.save(isolated, 4, true) }.isFailure,
                "advance must not overwrite corruption"
            )
            verify(
                file.readBytes().contentEquals(damaged) && File(
                    root,
                    "original-state.json"
                ).readBytes().contentEquals(original),
                "failed load preserves corrupt bytes and original evidence"
            )
            file.writeBytes(original)
            verify(
                ContestValueModel.load(isolated).toString() == saved.toString(),
                "original restored only in isolated probe"
            )
            result.putString("result", "PASS $checks checks")
            result.putString("test_path", root.absolutePath)
            finish(-1, result)
        } catch (t: Throwable) {
            result.putString("failure", t.stackTraceToString())
            result.putInt("completed_checks", checks)
            finish(0, result)
        }
    }
}
