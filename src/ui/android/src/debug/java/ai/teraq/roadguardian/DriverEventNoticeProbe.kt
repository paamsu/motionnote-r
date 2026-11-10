package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Deterministic state boundaries; no UI taps, network or vehicle signal mutation. */
class DriverEventNoticeProbe : Instrumentation() {
    private var laneCases = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        laneCases = arguments?.getString("lane_cases") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var checks = 0
        fun verify(condition: Boolean) {
            check(condition) { "case $checks" }
            checks++
        }

        fun event(kind: String = "pothole", id: String = UUID.randomUUID().toString()): JSONObject =
            JSONObject()
                .put("id", id).put("source", "synthetic").put("evidence_valid", true)
                .put("status", "evidence_ready")
                .put("hash", "a".repeat(64))
                .put(
                    "assessment",
                    JSONObject().put("kind", kind)
                        .put("diagnostic", JSONObject().put("verdict", "inspection_required"))
                )
                .put("diagnostic_valid", true)
                .put("repair_case", JSONObject().put("stage", "measurement_review"))
                .put(
                    "insurance_case",
                    JSONObject().put("source", "synthetic").put("status", "ready")
                )

        fun rows(vararg events: JSONObject) = JSONArray(events.toList())
        fun state() = DriverEventNoticeState().apply { accept(JSONArray(), 0) }
        try {
            val old = event()
            val first = DriverEventNoticeState()
            first.accept(rows(old), 100)
            verify(first.current(100) == null)
            first.accept(rows(old), 200)
            verify(first.current(200) == null)
            val fresh = event()
            first.accept(rows(old, fresh), 300)
            verify(first.current(300)?.getString("id") == fresh.getString("id"))
            val clock = state()
            val timed = event()
            clock.accept(rows(timed), 100)
            verify(clock.current(10_099) != null)
            verify(clock.current(10_100) == null)
            clock.accept(rows(timed), 10_101)
            verify(clock.current(10_101) == null)
            val duration = state()
            duration.accept(rows(timed), 100)
            duration.accept(rows(timed), 9_000)
            duration.accept(rows(timed), 18_000)
            verify(duration.current(20_099) != null)
            verify(duration.current(20_100) == null)
            duration.accept(rows(timed), 20_101)
            verify(duration.current(20_101) == null)
            val priority = state()
            val crash = event("crash")
            val diagnosis = event("diagnosis")
            val road = event()
            priority.accept(rows(road, diagnosis, crash), 100)
            verify(priority.current(100)?.getString("id") == crash.getString("id"))
            val preempt = state()
            preempt.accept(rows(road), 100)
            preempt.accept(rows(road, diagnosis), 200)
            verify(preempt.current(200)?.getString("id") == diagnosis.getString("id"))
            preempt.accept(rows(road, diagnosis, crash), 300)
            verify(preempt.current(300)?.getString("id") == crash.getString("id"))
            preempt.accept(rows(road, diagnosis, crash, event()), 400)
            verify(preempt.current(400)?.getString("id") == crash.getString("id"))
            val copy = state()
            val original = event("crash")
            copy.accept(rows(original), 100)
            original.put("evidence_valid", false)
            verify(copy.current(101)?.getBoolean("evidence_valid") == true)
            copy.current(102)!!.getJSONObject("insurance_case").put("status", "unknown")
            verify(
                copy.current(103)!!.getJSONObject("insurance_case").getString("status") == "ready"
            )
            val updated = event("crash", original.getString("id")).put("updated_marker", "latest")
            copy.accept(rows(updated), 104)
            verify(copy.current(104)!!.getString("updated_marker") == "latest")
            copy.accept(rows(updated), 9_000)
            copy.accept(rows(updated), 18_000)
            verify(copy.current(20_100) == null)
            for (change in listOf<(JSONObject) -> Unit>(
                { it.put("evidence_valid", false) },
                { it.put("status", "dismissed") },
                { it.put("source", "vehicle") },
                { it.getJSONObject("insurance_case").put("status", "unknown") },
                { it.getJSONObject("insurance_case").put("status", "payload_invalid") })) {
                val s = state()
                val e = event("crash")
                s.accept(rows(e), 100)
                val bad = JSONObject(e.toString())
                change(bad)
                s.accept(rows(bad), 200)
                verify(s.current(200) == null)
                s.accept(rows(e), 300)
                verify(s.current(300) == null)
            }
            val missing = state()
            missing.accept(rows(road), 100)
            missing.accept(JSONArray(), 200)
            verify(missing.current(200) == null)
            missing.accept(rows(road), 300)
            verify(missing.current(300) == null)
            val failure = state()
            failure.accept(rows(road), 100)
            failure.invalidate()
            verify(failure.current(101) == null)
            failure.accept(rows(road, diagnosis), 200)
            verify(failure.current(200) == null)
            failure.accept(rows(road, diagnosis, crash), 300)
            verify(failure.current(300)?.getString("id") == crash.getString("id"))
            val backward = state()
            backward.accept(rows(road), 100)
            verify(backward.current(99) == null)
            backward.accept(rows(road), 100)
            verify(backward.current(100) == null)
            backward.accept(rows(road, diagnosis), 200)
            backward.accept(rows(road, diagnosis, crash), 199)
            verify(backward.current(200) == null)
            backward.accept(rows(road, diagnosis, crash), 201)
            verify(backward.current(201) == null)
            val negative = state()
            negative.accept(rows(road), -1)
            verify(negative.current(0) == null)
            for (bad in listOf(
                event("unknown"),
                event("diagnosis").put("diagnostic_valid", false),
                event("diagnosis").apply {
                    getJSONObject("repair_case").put(
                        "stage",
                        "follow_up_invalid"
                    )
                },
                event(id = "not-a-uuid"),
                event(id = "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF"),
                event().put("evidence_valid", "true")
            )) {
                val s = state()
                s.accept(rows(bad), 100)
                verify(s.current(100) == null)
            }
            val duplicate = state()
            duplicate.accept(rows(road, JSONObject(road.toString())), 100)
            verify(duplicate.current(100) == null)
            duplicate.accept(rows(road), 200)
            verify(duplicate.current(200) == null)
            val initiallyInvalid = state()
            val invalid = event("diagnosis").put("diagnostic_valid", false)
            initiallyInvalid.accept(rows(invalid), 100)
            invalid.put("diagnostic_valid", true)
            initiallyInvalid.accept(rows(invalid), 200)
            verify(initiallyInvalid.current(200) == null)
            val latest = state()
            val low = event("diagnosis")
            latest.accept(rows(low), 100)
            val recovered = JSONObject(low.toString()).apply {
                put(
                    "repair_case", JSONObject().put("stage", "observed_signal_recovered")
                        .put(
                            "after",
                            JSONObject().put("delta_v", 0).put("voltage_median_v", 12.4)
                                .put("baseline_v", 12.4).put("matched_samples", 40)
                        )
                )
                put(
                    "repair",
                    JSONObject().put(
                        "post_repair",
                        JSONObject().put("hash", "b".repeat(64)).put("source", "synthetic")
                            .put("integrity_valid", true)
                    )
                )
            }
            latest.accept(rows(recovered), 200)
            verify(EventBriefState.from(latest.current(200)!!).title == "재측정 전압 회복")
            for (bad in listOf(
                event().put("hash", "A".repeat(64)),
                event().put("hash", "a".repeat(63)),
                event("crash").put("status", "pending_confirmation"),
                event("diagnosis").apply {
                    getJSONObject("repair_case").put(
                        "stage",
                        "center_accepted_mock"
                    )
                },
                event("crash").apply {
                    getJSONObject("insurance_case").put(
                        "status",
                        "awaiting_receipt"
                    )
                })) {
                val s = state()
                s.accept(rows(bad), 100)
                verify(s.current(100) == null)
            }
            val fixtures = JSONObject(
                targetContext.assets.open("diagnostic-cases-v135.json").bufferedReader()
                    .use { it.readText() })
            val initial = JSONObject(fixtures.getJSONObject("measurement").toString())
            val repeat = JSONObject(fixtures.getJSONObject("unresolved").toString()).put(
                "id",
                initial.getString("id")
            )
            val recovery = JSONObject(fixtures.getJSONObject("recovered").toString()).put(
                "id",
                initial.getString("id")
            )
            check(repeat.getString("status") == "repair_reviewed" && recovery.getString("status") == "repair_reviewed")
            val repeated = state()
            repeated.accept(rows(initial), 100)
            repeated.accept(rows(initial), 9_000)
            repeated.accept(rows(initial), 18_000)
            verify(repeated.current(20_100) == null)
            repeated.accept(rows(repeat), 21_000)
            verify(EventBriefState.from(repeated.current(21_000)!!).title == "저전압 다시 감지")
            repeated.accept(rows(recovery), 22_000)
            verify(EventBriefState.from(repeated.current(22_000)!!).title == "재측정 전압 회복")
            repeated.accept(rows(recovery), 30_000)
            repeated.accept(rows(recovery), 39_000)
            verify(repeated.current(41_000) == null)
            repeated.accept(rows(recovery), 41_001)
            verify(repeated.current(41_001) == null)
            val previousResult = DriverEventNoticeState()
            previousResult.accept(rows(repeat), 100)
            verify(previousResult.current(100) == null)
            previousResult.accept(rows(repeat), 200)
            verify(previousResult.current(200) == null)
            for (field in listOf("integrity_valid", "hash", "source")) {
                val invalidPost = JSONObject(repeat.toString())
                invalidPost.getJSONObject("repair").getJSONObject("post_repair")
                    .put(
                        field, when (field) {
                            "integrity_valid" -> false
                            "hash" -> "F".repeat(64)
                            else -> "camera"
                        }
                    )
                val s = state()
                s.accept(rows(invalidPost), 100)
                verify(s.current(100) == null)
                if (field != "hash") {
                    s.accept(rows(repeat), 200)
                    verify(s.current(200) == null)
                }
            }
            val awaiting = event().put("status", "pending_confirmation")
            val roadPending = state()
            roadPending.accept(rows(awaiting), 100)
            verify(roadPending.current(100)?.getString("id") == awaiting.getString("id"))
            if (laneCases) {
                val source =
                    java.io.File(targetContext.filesDir, "lane-notice-fixture.json").readText()
                val laneInputs = JSONObject(source)
                val lane = laneInputs.getJSONObject("lane_changes")
                check(VideoLaneAnalysis.repeatedCandidate(lane))
                val laneId = lane.getString("id")
                val audit = JSONArray()
                fun record(name: String, condition: Boolean) {
                    verify(condition)
                    audit.put(JSONObject().put("check", name).put("pass", true))
                }

                val simultaneous = listOf(lane, diagnosis, crash)
                for (a in 0..2) for (b in 0..2) if (a != b) {
                    val c = 3 - a - b
                    val q = state()
                    q.accept(rows(simultaneous[a], simultaneous[b], simultaneous[c]), 100)
                    record(
                        "simultaneous-$a$b$c",
                        q.current(100)?.getString("id") == crash.getString("id")
                    )
                }
                val upgrade = state()
                upgrade.accept(rows(lane), 100)
                record("lane-start", upgrade.current(100)?.getString("id") == laneId)
                upgrade.accept(rows(lane, diagnosis), 200)
                record(
                    "diagnosis-preempts-lane",
                    upgrade.current(200)?.getString("id") == diagnosis.getString("id")
                )
                upgrade.accept(rows(lane, diagnosis, crash), 300)
                record(
                    "crash-preempts-diagnosis",
                    upgrade.current(300)?.getString("id") == crash.getString("id")
                )
                val lower = state()
                lower.accept(rows(crash), 100)
                lower.accept(rows(crash, lane), 200)
                record(
                    "lane-cannot-preempt-crash",
                    lower.current(200)?.getString("id") == crash.getString("id")
                )
                lower.accept(rows(lane), 300)
                record("suppressed-lane-not-replayed", lower.current(300) == null)
                val stale = state()
                stale.accept(rows(lane), 100)
                record("fresh-before-boundary", stale.current(10099) != null)
                record("freshness-boundary", stale.current(10100) == null)
                stale.accept(rows(lane), 10101)
                record("expired-no-replay", stale.current(10101) == null)
                val capped = state()
                capped.accept(rows(lane), 100)
                capped.accept(rows(lane), 9000)
                capped.accept(rows(lane), 18000)
                record("duration-before-boundary", capped.current(20099) != null)
                record("duration-boundary", capped.current(20100) == null)
                capped.accept(rows(lane), 20101)
                record("duration-no-replay", capped.current(20101) == null)
                val prior = DriverEventNoticeState()
                prior.accept(rows(lane), 100)
                record("baseline-lane-silent", prior.current(100) == null)
                val removed = state()
                removed.accept(rows(lane), 100)
                removed.accept(JSONArray(), 200)
                record("missing-removes-lane", removed.current(200) == null)
                removed.accept(rows(lane), 300)
                record("reappeared-id-silent", removed.current(300) == null)
                val repeatedId = state()
                repeatedId.accept(rows(lane, EvidenceJson.copy(lane)), 100)
                record("duplicate-id-rejected", repeatedId.current(100) == null)
                repeatedId.accept(rows(lane), 200)
                record("duplicate-id-no-retry", repeatedId.current(200) == null)
                val broken = state()
                broken.accept(rows(lane), 100)
                val invalid = EvidenceJson.copy(lane)
                invalid.getJSONObject("video_lane_analysis").put("visible_changes", 99)
                broken.accept(rows(invalid), 200)
                record("invalid-update-removes-active", broken.current(200) == null)
                broken.accept(rows(lane), 300)
                record("repaired-id-no-replay", broken.current(300) == null)
                for (name in listOf("straight", "camera_pan")) {
                    val q = state()
                    q.accept(rows(laneInputs.getJSONObject(name)), 100)
                    record("$name-no-alert", q.current(100) == null)
                }
                java.io.File(targetContext.filesDir, "lane-notice-policy-result.json").writeText(
                    JSONObject().put("checks", audit).put(
                        "source_sha256",
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(source.toByteArray()).joinToString("") { "%02x".format(it) })
                        .put(
                            "scope",
                            "Actual Android policy with stored synthetic lane analyses and authored priority competitors; injected policy time, no wall-clock, UI or vehicle test."
                        ).toString(2)
                )
            }
            result.putString(
                "stream",
                "PASS: $checks event notice state checks: first baseline, priorities, expiry, freshness, rollback, invalidation, latest state, deep copies and no replay. No UI taps or actual driving.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: checks=$checks ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
