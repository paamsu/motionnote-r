package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import org.json.JSONArray

class TrafficZoneAutoNoticeProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val out = Bundle()
        var count = 0
        fun expect(ok: Boolean) {
            check(ok) { "boundary $count" }
            count++
        }

        fun event(zone: String): JSONObject {
            val photo =
                if (zone == "school_zone") "e33c47aefa74867675a6e7b086ba2c19e6415206b8a13deb23d9f05c2b887f51" else "eea83c3e7d9e2286b0325fce1aef7b529b615ec9a10a46ad79abdfac1343e4f9"
            val label = if (zone == "school_zone") "어린이보호구역" else "주정차금지구역"
            val id = "11111111-1111-4111-8111-111111111111"
            val hash = "a".repeat(64)
            val a = JSONObject().put("schema", "road-guardian.traffic-zone-auto.v1")
                .put("source", "authored_contest_scenario").put("zone_type", zone)
                .put("zone_label", label).put("dispatch_mode", "automatic_contest")
                .put("recipient", "public-report-mock").put("recognition_performed", false)
                .put("external_submission", false).put("target_actor_id", "vehicle-1")
                .put("target_plate", "DEMO-001").put("target_photo_sha256", photo)
            val detail =
                JSONObject().put("source", "authored_contest_scenario").put("zone_type", zone)
                    .put("driver_confirmation", false).put("external_submission", false)
            return JSONObject().put("id", id).put("hash", hash).put("source", "synthetic")
                .put("evidence_valid", true).put("status", "acknowledged")
                .put(
                    "assessment",
                    JSONObject().put("kind", "violation").put("recipient", "public-report-mock")
                )
                .put(
                    "data",
                    JSONObject().put("source", "synthetic").put(
                        "context",
                        JSONObject().put("traffic_zone_auto", a).put(
                            "actors",
                            JSONArray().put(
                                JSONObject().put("id", "vehicle-1").put("plate", "DEMO-001")
                                    .put("recognition_status", "not_run")
                            )
                        )
                    )
                        .put(
                            "observations",
                            JSONObject().put("restricted_zone", true).put("stationary_seconds", 125)
                                .put("plate", "DEMO-001")
                        )
                        .put(
                            "location",
                            JSONObject().put("lat", 37.5).put("lon", 127.0)
                                .put("address_label", "작성 위치").put("road_id", "demo-zone")
                        )
                )
                .put(
                    "video",
                    JSONObject().put("source", "synthetic_scene_fixture")
                        .put("camera_evidence", false).put("integrity_valid", true).put(
                        "input_photo",
                        JSONObject().put("integrity_valid", true).put("sha256", photo)
                            .put("event_id", id).put("sensor_sha256", hash)
                    )
                )
                .put(
                    "dispatches",
                    JSONArray().put(
                        JSONObject().put("id", id).put("event_id", id)
                            .put("recipient", "public-report-mock").put("status", "acknowledged")
                            .put("receipt", "MOCK-11111111")
                    )
                )
                .put(
                    "history",
                    JSONArray().put(
                        JSONObject().put("event_id", id)
                            .put("action", "authored_traffic_auto_authorized")
                            .put("detail", detail.toString())
                    )
                )
        }
        try {
            for (zone in listOf("school_zone", "no_parking")) {
                val normal = event(zone)
                expect(
                    TrafficZoneAutoNotice.received(normal) && TrafficZoneAutoNotice.mediaValid(
                        normal
                    )
                )
                expect(DriverEventNoticeState.isEligible(normal))
                expect(DriverBriefPanel.verifiedBrief(normal)?.second?.title == "안전신문고 접수 완료")
                for (change in listOf(
                    "photo",
                    "cross_photo",
                    "hash",
                    "address",
                    "manual",
                    "history",
                    "confirmation",
                    "receipt",
                    "recipient",
                    "pending"
                )) {
                    val e = event(zone)
                    when (change) {
                        "photo" -> e.getJSONObject("video").getJSONObject("input_photo")
                            .put("integrity_valid", false)

                        "cross_photo" -> e.getJSONObject("video").getJSONObject("input_photo")
                            .put("sha256", "b".repeat(64))

                        "hash" -> e.getJSONObject("video").getJSONObject("input_photo")
                            .put("sensor_sha256", "b".repeat(64))

                        "address" -> e.getJSONObject("data").getJSONObject("location")
                            .remove("address_label")

                        "manual" -> e.getJSONObject("data").getJSONObject("context")
                            .remove("traffic_zone_auto")

                        "history" -> e.remove("history")
                        "confirmation" -> {
                            val h = e.getJSONArray("history").getJSONObject(0)
                            h.put(
                                "detail",
                                JSONObject(h.getString("detail")).put("driver_confirmation", true)
                                    .toString()
                            )
                        }

                        "receipt" -> e.getJSONArray("dispatches").getJSONObject(0)
                            .put("receipt", "MOCK-other")

                        "recipient" -> e.getJSONObject("assessment").put("recipient", "112-mock")
                        "pending" -> e.put("status", "pending_confirmation")
                    }
                    if (change in listOf("photo", "cross_photo", "hash", "address")) expect(
                        DriverBriefPanel.verifiedBrief(e)?.second?.next == "현장 자료 확인 필요"
                    )
                    else expect(
                        !TrafficZoneAutoNotice.received(e) && !DriverEventNoticeState.isEligible(
                            e
                        )
                    )
                }
            }
            out.putString("result", "PASS $count checks; predicates only")
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("error", t.toString())
            finish(Activity.RESULT_CANCELED, out)
        }
    }
}
