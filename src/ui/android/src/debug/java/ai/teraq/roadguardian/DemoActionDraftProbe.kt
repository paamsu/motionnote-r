package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Storage method checks only; no simulated taps, external booking or submissions. */
class DemoActionDraftProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val ctx = targetContext
            fun asset(name: String, key: String) = JSONObject(
                ctx.assets.open(name).bufferedReader().use { it.readText() }).getJSONObject(key)

            fun copy(event: JSONObject) = JSONObject(event.toString())
            val insurance = copy(asset("crash-cases-v133.json", "ready")).put(
                "id",
                UUID.randomUUID().toString()
            )
            val service = copy(asset("diagnostic-cases-v135.json", "measurement")).put(
                "id",
                UUID.randomUUID().toString()
            )
            check(DemoActionDraftStore.eligibleAction(insurance) == "insurance")
            check(DemoActionDraftStore.eligibleAction(service) == "service")
            val repeat = copy(asset("diagnostic-cases-v135.json", "unresolved")).put(
                "id",
                UUID.randomUUID().toString()
            )
            check(DiagnosticEvidenceReview.canOpen(repeat))
            check(DemoActionDraftStore.eligibleAction(repeat) == "service")
            val recovered = copy(asset("diagnostic-cases-v135.json", "recovered")).put(
                "id",
                UUID.randomUUID().toString()
            )
            check(DiagnosticEvidenceReview.canOpen(recovered))
            check(DemoActionDraftStore.eligibleAction(recovered) == "service_handoff")
            val originalRecovered = recovered.toString()
            for ((event, action) in listOf(
                insurance to "insurance",
                service to "service",
                repeat to "service",
                recovered to "service_handoff"
            )) {
                check(DemoActionDraftStore.read(ctx, event) == null)
                check(!DemoActionDraftStore.hasRecord(ctx, event))
                val prepared = DemoActionDraftStore.prepare(ctx, event)
                check(prepared.getString("state") == "prepared" && prepared.getString("action") == action)
                check(
                    prepared.get("external_submission") == false && prepared.getString("sensor_sha256") == event.getString(
                        "hash"
                    )
                )
                check(
                    DemoActionDraftStore.read(ctx, copy(event))?.toString() == prepared.toString()
                )
                check(DemoActionDraftStore.hasRecord(ctx, event))
                check(DemoActionDraftStore.prepare(ctx, event).toString() == prepared.toString())
                val another = copy(event).put("id", UUID.randomUUID().toString())
                check(DemoActionDraftStore.read(ctx, another) == null)
                check(
                    DemoActionDraftStore.read(
                        ctx,
                        copy(event).put("hash", "0".repeat(64))
                    ) == null
                )
                check(
                    DemoActionDraftStore.read(
                        ctx,
                        copy(event).put("source", "owner_reported")
                    ) == null
                )
                check(
                    DemoActionDraftStore.read(
                        ctx,
                        copy(event).put("status", "dismissed")
                    ) == null
                )
                val deferred = DemoActionDraftStore.defer(ctx, event)
                check(
                    deferred.getString("state") == "deferred" && DemoActionDraftStore.read(
                        ctx,
                        event
                    )?.getString("state") == "deferred"
                )
            }
            for (key in listOf("service_covered", "service_not_covered")) {
                val authored = copy(asset("service-demo-v639.json", key)).put(
                    "id",
                    UUID.randomUUID().toString()
                )
                val before = authored.toString()
                val target =
                    ServiceDemoRecommendation.checked(authored) ?: error("Missing authored target")
                check(DemoActionDraftStore.eligibleAction(authored) == "service")
                val saved = DemoActionDraftStore.prepare(ctx, authored)
                check(saved.getJSONObject("service_target").toString() == target.toString())
                check(DemoActionDraftStore.read(ctx, authored)?.toString() == saved.toString())
                check(DemoActionDraftStore.prepare(ctx, authored).toString() == saved.toString())
                check(authored.toString() == before)
                val switched = copy(
                    asset(
                        "service-demo-v639.json",
                        if (key == "service_covered") "service_not_covered" else "service_covered"
                    )
                )
                    .put("id", authored.getString("id")).put("hash", authored.getString("hash"))
                check(
                    DemoActionDraftStore.fingerprint(switched) != DemoActionDraftStore.fingerprint(
                        authored
                    )
                )
                check(DemoActionDraftStore.read(ctx, switched) == null)
                for (mode in listOf("unknown", "unavailable", "wrong-center", "wrong-brand")) {
                    val bad = copy(authored)
                    val value = bad.getJSONObject("data").getJSONObject("context")
                        .getJSONObject("service_demo")
                    when (mode) {
                        "unknown" -> value.put("warranty", "unknown")
                        "unavailable" -> value.getJSONObject("offer").getJSONObject("center")
                            .put("available", false)

                        "wrong-center" -> value.getJSONObject("offer").getJSONObject("center")
                            .put("id", "changed")

                        "wrong-brand" -> value.put("brand", "Kia")
                    }
                    check(DemoActionDraftStore.eligibleAction(bad) == null)
                    check(DemoActionDraftStore.read(ctx, bad) == null)
                    check(runCatching { DemoActionDraftStore.prepare(ctx, bad) }.isFailure)
                }
            }
            val changed = copy(insurance)
            changed.getJSONObject("insurance_case").put("policy_id", "CHANGED-TEST")
            check(
                DemoActionDraftStore.eligibleAction(changed) == "insurance" && DemoActionDraftStore.read(
                    ctx,
                    changed
                ) == null
            )
            val statusChanged = copy(insurance).put("status", "dispatch_ready")
            check(DemoActionDraftStore.read(ctx, statusChanged) == null)
            val orderChanged = JSONObject()
            insurance.keys().asSequence().toList().reversed()
                .forEach { orderChanged.put(it, insurance.get(it)) }
            check(
                DemoActionDraftStore.fingerprint(orderChanged) == DemoActionDraftStore.fingerprint(
                    insurance
                )
            )
            for (key in listOf("accepted", "warranty")) {
                val denied = asset("diagnostic-cases-v135.json", key)
                check(
                    DemoActionDraftStore.eligibleAction(denied) == null && DemoActionDraftStore.read(
                        ctx,
                        denied
                    ) == null
                )
                check(runCatching { DemoActionDraftStore.prepare(ctx, denied) }.isFailure)
            }
            check(recovered.toString() == originalRecovered) { "Handoff changed original evidence" }
            val invalidRecovered = copy(recovered)
            invalidRecovered.getJSONObject("repair").getJSONObject("post_repair")
                .put("integrity_valid", false)
            check(DemoActionDraftStore.eligibleAction(invalidRecovered) == null)
            val changedRecovered = copy(recovered)
            changedRecovered.getJSONObject("repair").getJSONObject("post_repair")
                .put("hash", "2".repeat(64))
            check(
                DemoActionDraftStore.fingerprint(changedRecovered) != DemoActionDraftStore.fingerprint(
                    recovered
                )
            )
            check(DemoActionDraftStore.read(ctx, changedRecovered) == null)
            val inconsistentRecovered = copy(recovered)
            inconsistentRecovered.getJSONObject("repair_case").getJSONObject("after")
                .put("voltage_median_v", 99)
            check(DemoActionDraftStore.eligibleAction(inconsistentRecovered) == null)
            val invalidRepeat = copy(repeat)
            invalidRepeat.getJSONObject("repair").getJSONObject("post_repair")
                .put("integrity_valid", false)
            check(DemoActionDraftStore.eligibleAction(invalidRepeat) == null)
            val changedRepeat = copy(repeat)
            changedRepeat.getJSONObject("repair").getJSONObject("post_repair")
                .put("hash", "1".repeat(64))
            check(
                DemoActionDraftStore.fingerprint(changedRepeat) != DemoActionDraftStore.fingerprint(
                    repeat
                )
            )
            check(DemoActionDraftStore.read(ctx, changedRepeat) == null)
            val inconsistentRepeat = copy(repeat)
            inconsistentRepeat.getJSONObject("repair_case").getJSONObject("after")
                .put("voltage_median_v", 99)
            check(DemoActionDraftStore.eligibleAction(inconsistentRepeat) == null)
            for (key in listOf(
                "expired",
                "retry",
                "acknowledged"
            )) check(
                DemoActionDraftStore.eligibleAction(
                    asset(
                        "crash-cases-v133.json",
                        key
                    )
                ) == null
            )
            check(
                DemoActionDraftStore.eligibleAction(
                    copy(insurance).put(
                        "evidence_valid",
                        false
                    )
                ) == null
            )
            val path = File(
                ctx.noBackupFilesDir,
                "demo-action-${insurance.getString("id")}-${
                    DemoActionDraftStore.fingerprint(
                        insurance
                    )
                }.json"
            )
            val original = path.readBytes()
            File(
                ctx.filesDir,
                "demo-action-before-corruption-${insurance.getString("id")}.json"
            ).writeBytes(original)
            val altered = JSONObject(original.toString(Charsets.UTF_8)).put("state", "prepared")
            path.writeText(altered.toString())
            check(DemoActionDraftStore.read(ctx, insurance) == null)
            check(DemoActionDraftStore.hasRecord(ctx, insurance))
            check(runCatching { DemoActionDraftStore.prepare(ctx, insurance) }.isFailure)
            val blocker = File(
                ctx.filesDir,
                "demo-action-storage-block-${UUID.randomUUID()}"
            ).apply { writeText("Retained storage-failure fixture") }
            val blocked = object : ContextWrapper(ctx) {
                override fun getNoBackupFilesDir(): File = blocker
            }
            check(runCatching {
                DemoActionDraftStore.prepare(
                    blocked,
                    copy(service).put("id", UUID.randomUUID().toString())
                )
            }.isFailure)
            val report = JSONObject().put("schema", "road-guardian.demo-action-probe.v1")
                .put("passed", true).put(
                    "event_ids",
                    JSONArray(listOf(insurance.getString("id"), service.getString("id")))
                )
                .put(
                    "checks",
                    JSONArray(
                        listOf(
                            "authored warranty target binding and invalid recommendation refusal",
                            "prepare and re-read",
                            "idempotent prepare",
                            "other event/hash/source/status refusal",
                            "insurance policy fingerprint change",
                            "canonical field order",
                            "defer",
                            "repeat booking and post-record binding",
                            "invalid/inconsistent repeat refusal",
                            "recovered handoff preserves evidence and binds both measurements",
                            "invalid recovered refusal",
                            "accepted refusal",
                            "corruption refusal and retention",
                            "storage failure throws"
                        )
                    )
                )
                .put(
                    "scope",
                    "native storage methods; no UI taps, service booking, insurer connection or external submission"
                )
            File(ctx.filesDir, "demo-action-draft-v224.json").writeText(report.toString(2))
            result.putString(
                "stream",
                "PASS: synthetic action drafts prepare/defer/re-read, binding, stale-state refusal, corrupt preservation and storage failure. No external action.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
