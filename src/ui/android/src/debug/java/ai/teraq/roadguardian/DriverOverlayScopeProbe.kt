package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Targeted notice boundaries, no notification publication or Activity launch. */
class DriverOverlayScopeProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var checks = 0
        fun verify(value: Boolean) {
            check(value) { "boundary $checks" }
            checks++
        }

        fun row(kind: String = "manual_help", recipient: String = "119-mock") =
            JSONObject().put("id", UUID.randomUUID().toString())
                .put("source", "synthetic").put("evidence_valid", true).put("hash", "a".repeat(64))
                .put("status", "evidence_ready")
                .put("assessment", JSONObject().put("kind", kind).put("recipient", recipient))
                .put("dispatches", JSONArray())

        fun copy(row: JSONObject) = JSONObject(row.toString())
        fun rows(vararg row: JSONObject) = JSONArray(row.toList())
        fun state() = DriverEventNoticeState().apply { accept(JSONArray(), 0) }
        fun delivered(row: JSONObject, status: String, receipt: String = "MOCK-1234abcd") =
            copy(row).put("status", status).put(
                "dispatches", JSONArray().put(
                    JSONObject()
                        .put("recipient", row.getJSONObject("assessment").getString("recipient"))
                        .put("status", status).put("receipt", receipt)
                )
            )
        try {
            for (kind in listOf("manual_help", "emergency")) for (recipient in listOf(
                "119-mock",
                "112-mock"
            )) {
                val event = row(kind, recipient)
                verify(DriverEventNoticeState.isEligible(event))
                verify(DriverBriefPanel.verifiedBrief(event)?.first == if (recipient == "119-mock") "help_119" else "help_112")
                val s = state()
                s.accept(rows(event), 100)
                verify(s.takeCurrent(100) != null)
                s.accept(rows(event), 200)
                verify(s.takeCurrent(200) == null)
                val retry = delivered(event, "retry_pending")
                s.accept(rows(retry), 300)
                verify(s.takeCurrent(300) != null)
                val ack = delivered(event, "acknowledged")
                s.accept(rows(ack), 400)
                verify(s.takeCurrent(400) != null)
                verify(
                    DriverEventNotification.identity(event) != DriverEventNotification.identity(
                        ack
                    )
                )
                val unrelated = copy(ack).put("updated_at", 999)
                verify(
                    DriverEventNotification.identity(ack) == DriverEventNotification.identity(
                        unrelated
                    )
                )
                s.accept(rows(event), 500)
                verify(s.current(500) == null) // terminal state must not regress
                s.accept(rows(ack), 600)
                verify(s.current(600) == null)
                val old = DriverEventNoticeState()
                old.accept(rows(ack), 0)
                verify(old.current(0) == null)
                val duplicate = state()
                duplicate.accept(rows(event, copy(event)), 100)
                verify(duplicate.current(100) == null)
                duplicate.accept(rows(event), 200)
                verify(duplicate.current(200) == null)
                val malformed = delivered(event, "acknowledged", "wrong")
                verify(!DriverEventNoticeState.isEligible(malformed))
                val wrong = copy(ack)
                wrong.getJSONArray("dispatches").getJSONObject(0)
                    .put("recipient", if (recipient == "119-mock") "112-mock" else "119-mock")
                verify(!DriverEventNoticeState.isEligible(wrong))
                val double = copy(ack)
                double.getJSONArray("dispatches")
                    .put(copy(double.getJSONArray("dispatches").getJSONObject(0)))
                verify(!DriverEventNoticeState.isEligible(double))
                val invalidFirst = state()
                invalidFirst.accept(rows(malformed), 100)
                invalidFirst.accept(rows(ack), 200)
                verify(invalidFirst.current(200) == null)
                for (bad in listOf(
                    copy(event).put("source", "vehicle"),
                    copy(event).put("hash", "broken"),
                    copy(event).put("evidence_valid", false),
                    copy(event).put("status", "dismissed")
                )) verify(!DriverEventNoticeState.isEligible(bad))
                val expiring = state()
                expiring.accept(rows(event), 100)
                verify(expiring.current(10100) == null)
                val clock = state()
                clock.accept(rows(event), 100)
                verify(clock.current(99) == null)
            }
            val parking = row("parking")
            verify(DriverEventNoticeState.isEligible(parking))
            verify(DriverBriefPanel.verifiedBrief(parking)?.first == "parking")
            verify(!DriverEventNoticeState.isEligible(copy(parking).put("status", "acknowledged")))
            val priority = state()
            val emergency = row("emergency")
            priority.accept(rows(parking, emergency), 100)
            verify(priority.current(100)?.getString("id") == emergency.getString("id"))
            verify(!DriverEventNoticeState.isEligible(row("wanted")))
            verify(!DriverEventNoticeState.isEligible(row("violation")))
            result.putString(
                "stream",
                "PASS: $checks bounded overlay checks; help delivery transitions, no re-alert on metadata/rollback, stale/duplicate/invalid refusal, parking and emergency priority. No UI or external calls.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
