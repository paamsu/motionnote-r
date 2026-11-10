package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DemoEventMonitorProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val source = JSONObject(File(targetContext.filesDir, "popup-fixture.json").readText())
            fun event() = JSONObject(source.toString()).put("id", UUID.randomUUID().toString())
            fun rows(vararg events: JSONObject) = JSONArray().apply { events.forEach { put(it) } }
            var checks = 0
            fun verify(value: Boolean, label: String) {
                check(value) { label }
                checks++
            }

            val state = DemoEventMonitorState()
            val first = event()
            val second = event()
            verify(state.receive(rows(first), 1000, 1001, true) == null, "first-load silence")
            verify(
                state.receive(rows(first, second), 2000, 2001, true)
                    ?.getString("id") == second.getString("id"),
                "new event while independent of Activity"
            )
            verify(
                state.receive(rows(first, second), 3000, 3001, true) == null,
                "no repeated delivery"
            )
            verify(state.receive(null, 4000, 4001, true) == null, "network loss")
            val offline = event()
            verify(
                state.receive(rows(first, second, offline), 5000, 5001, true) == null,
                "reconnect baseline silence"
            )
            val permissionLost = event()
            verify(
                state.receive(
                    rows(first, second, offline, permissionLost),
                    6000,
                    6001,
                    false
                ) == null, "permission loss suppresses event"
            )
            verify(
                state.receive(
                    rows(first, second, offline, permissionLost),
                    7000,
                    7001,
                    true
                ) == null, "no replay after permission restored"
            )
            val slow = event()
            verify(state.receive(rows(slow), 8000, 13000, true) == null, "slow response rejected")
            verify(
                state.receive(rows(slow), 14000, 14001, true) == null,
                "baseline after slow response"
            )
            val invalid = event().put("evidence_valid", false)
            verify(
                state.receive(rows(slow, invalid), 15000, 15001, true) == null,
                "invalid evidence"
            )
            val duplicate = event()
            verify(
                state.receive(rows(slow, duplicate, duplicate), 16000, 16001, true) == null,
                "duplicate rows"
            )
            val next = event()
            verify(
                state.receive(rows(slow, next), 17000, 17001, true)
                    ?.getString("id") == next.getString("id"), "new valid event after failures"
            )
            val rapid = DemoEventMonitorState()
            verify(rapid.receive(rows(), 20000, 20001, true) == null, "rapid baseline")
            val rapidFirst = event()
            val rapidSecond = event()
            verify(
                rapid.receive(rows(rapidFirst), 21000, 21001, true)
                    ?.getString("id") == rapidFirst.getString("id"), "first rapid event"
            )
            verify(
                rapid.receive(rows(rapidFirst, rapidSecond), 22000, 22001, true)
                    ?.getString("id") == rapidSecond.getString("id"),
                "second distinct event must not be lost during previous notice lifetime"
            )
            verify(
                rapid.receive(rows(rapidFirst, rapidSecond), 23000, 23001, true) == null,
                "rapid events not repeated"
            )
            rapid.invalidate()
            val duringPause = event()
            verify(
                rapid.receive(
                    rows(rapidFirst, rapidSecond, duringPause),
                    24000,
                    24001,
                    true
                ) == null, "resume baseline suppresses unseen background history"
            )
            val afterResume = event()
            verify(
                rapid.receive(
                    rows(rapidFirst, rapidSecond, duringPause, afterResume),
                    25000,
                    25001,
                    true
                )?.getString("id") == afterResume.getString("id"),
                "fresh event after resume is delivered"
            )
            val serviceOwned = event()
            verify(
                rapid.receive(rows(serviceOwned), 26000, 26001, false) == null,
                "active service owns delivery"
            )
            verify(
                rapid.receive(rows(serviceOwned), 27000, 27001, true) == null,
                "no replay when service relinquishes delivery"
            )
            val afterHandoff = event()
            verify(
                rapid.receive(rows(serviceOwned, afterHandoff), 28000, 28001, true)
                    ?.getString("id") == afterHandoff.getString("id"),
                "fresh event after service handoff"
            )
            val report = JSONObject().put("passed", checks).put(
                "scope",
                "Monitor state only; no OS notification, HTTP, service lifecycle or visual overlay assertion"
            ).put("permission_changed", false)
            File(targetContext.filesDir, "event-monitor-result.json").writeText(report.toString(2))
            result.putString("stream", "PASS: $checks monitor state checks; no permission change\n")
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
