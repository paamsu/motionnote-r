package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject

/** Only driver authoring/media predicates; does not claim rendered overlay verification. */
class DriverEmergencyMediaProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val out = Bundle()
        var checks = 0
        fun expect(ok: Boolean) {
            check(ok) { "boundary $checks failed" }
            checks++
        }

        fun event(): JSONObject {
            val authored = JSONObject().put("schema", "road-guardian.driver-emergency.v1")
                .put("source", "authored_contest_scenario")
                .put("subject", "own_driver").put("movement_state", "authored_no_movement")
                .put("response_state", "authored_unresponsive")
                .put("dispatch_mode", "automatic_contest").put("recipient", "119-mock")
                .put("recognition_performed", false).put("external_submission", false)
            return JSONObject().put("id", "11111111-1111-4111-8111-111111111111")
                .put("hash", "a".repeat(64))
                .put("source", "synthetic").put("evidence_valid", true)
                .put(
                    "assessment",
                    JSONObject().put("kind", "emergency").put("recipient", "119-mock")
                )
                .put(
                    "data",
                    JSONObject().put("context", JSONObject().put("driver_emergency", authored))
                        .put("observations", JSONObject().put("unresponsive_test_signal", true))
                )
                .put(
                    "video", JSONObject().put("integrity_valid", true).put(
                        "input_photo", JSONObject().put("integrity_valid", true)
                            .put("event_id", "11111111-1111-4111-8111-111111111111")
                            .put("sensor_sha256", "a".repeat(64))
                    )
                )
        }
        try {
            expect(
                EventBriefState.authoredDriverEmergency(event()) && EventBriefState.driverEmergencyMediaValid(
                    event()
                )
            )
            for (change in listOf(
                "photo_invalid",
                "video_invalid",
                "other_event",
                "other_hash",
                "recipient",
                "timeline",
                "missing_photo",
                "raw_invalid"
            )) {
                val e = event()
                when (change) {
                    "photo_invalid" -> e.getJSONObject("video").getJSONObject("input_photo")
                        .put("integrity_valid", false)

                    "video_invalid" -> e.getJSONObject("video").put("integrity_valid", false)
                    "other_event" -> e.getJSONObject("video").getJSONObject("input_photo")
                        .put("event_id", "22222222-2222-4222-8222-222222222222")

                    "other_hash" -> e.getJSONObject("video").getJSONObject("input_photo")
                        .put("sensor_sha256", "b".repeat(64))

                    "recipient" -> e.getJSONObject("assessment").put("recipient", "112-mock")
                    "timeline" -> e.getJSONObject("data").getJSONObject("observations")
                        .put("emergency_timeline", JSONObject())

                    "missing_photo" -> e.getJSONObject("video").remove("input_photo")
                    "raw_invalid" -> e.put("evidence_valid", false)
                }
                expect(!EventBriefState.driverEmergencyMediaValid(e))
                if (change == "recipient") expect(!EventBriefState.authoredDriverEmergency(e))
            }
            out.putString("result", "PASS $checks checks; predicates only, no rendered UI/network")
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("error", t.toString())
            finish(Activity.RESULT_CANCELED, out)
        }
    }
}
