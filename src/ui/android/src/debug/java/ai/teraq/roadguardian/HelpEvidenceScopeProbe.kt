package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject

/** Bounded presentation scope checks only; no account access or external action. */
class HelpEvidenceScopeProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(BuildConfig.SYNTHETIC_BACKEND)
            for (kind in listOf("manual_help", "emergency")) for (target in listOf(
                "112-mock",
                "119-mock"
            )) {
                val event = JSONObject().put("source", "synthetic").put("evidence_valid", true)
                    .put("status", "evidence_ready")
                    .put("assessment", JSONObject().put("kind", kind).put("recipient", target))
                check(EvidenceScope.category(event) == "help")
                check(EvidenceScope.helpGraphic(event) == if (target == "112-mock") "help_112" else "help_119")
                check(EvidenceScope.helpStatus(event)?.startsWith("기록 보관") == true)
                check(!EvidenceScope.canNotifyComparison(event))
                for (source in listOf("owner_reported", "server", "")) check(
                    EvidenceScope.category(
                        JSONObject(event.toString()).put("source", source)
                    ) == null
                )
                check(
                    EvidenceScope.category(
                        JSONObject(event.toString()).put(
                            "evidence_valid",
                            false
                        )
                    ) == null
                )
                for (state in listOf(
                    "dismissed",
                    "input_review_required"
                )) check(
                    EvidenceScope.category(
                        JSONObject(event.toString()).put(
                            "status",
                            state
                        )
                    ) == null
                )
                val invalid = JSONObject(event.toString())
                invalid.getJSONObject("assessment").put("recipient", "119")
                check(EvidenceScope.category(invalid) == null)
            }
            val owner = JSONObject().put("source", "owner_reported")
                .put("assessment", JSONObject().put("kind", "owner_record").put("category", "help"))
            check(EvidenceScope.category(owner) == null)
            val selection = VehicleOverviewSelection()
            selection.setCategory("help")
            check(selection.category == "help")
            selection.select("preserved")
            check(selection.resolve(listOf("other", "preserved")) == 1)
            result.putString(
                "stream",
                "PASS: 4 synthetic help combinations, source/validity/recipient exclusions, owner category unchanged, help selection retained. No UI taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
