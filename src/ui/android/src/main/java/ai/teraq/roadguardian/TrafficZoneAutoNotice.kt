package ai.teraq.roadguardian

import org.json.JSONObject

/** 작성된 교통 구역 모의 접수(크기 제한). 영상 판독이나 법적 판단은 하지 않는다 */
object TrafficZoneAutoNotice {
    private val photos = mapOf(
        "school_zone" to "e33c47aefa74867675a6e7b086ba2c19e6415206b8a13deb23d9f05c2b887f51",
        "no_parking" to "eea83c3e7d9e2286b0325fce1aef7b529b615ec9a10a46ad79abdfac1343e4f9"
    )
    private val labels = mapOf("school_zone" to "어린이보호구역", "no_parking" to "주정차금지구역")
    private fun meta(e: JSONObject) =
        e.optJSONObject("data")?.optJSONObject("context")?.optJSONObject("traffic_zone_auto")

    fun zoneLabel(e: JSONObject) = labels[meta(e)?.optString("zone_type")].orEmpty()
    fun authored(e: JSONObject): Boolean {
        val data = e.optJSONObject("data") ?: return false
        val context = data.optJSONObject("context") ?: return false
        val a = context.optJSONObject("traffic_zone_auto") ?: return false
        val obs = data.optJSONObject("observations") ?: return false
        val actors = context.optJSONArray("actors") ?: return false
        val actor = actors.optJSONObject(0) ?: return false
        val zone = a.optString("zone_type")
        val photo = photos[zone] ?: return false
        val dwell = obs.opt("stationary_seconds") as? Number ?: return false
        return BuildConfig.SYNTHETIC_BACKEND && e.optString("source") == "synthetic" && data.optString(
            "source"
        ) == "synthetic" && e.opt("evidence_valid") == true &&
                e.optJSONObject("assessment")
                    ?.optString("kind") == "violation" && e.optJSONObject("assessment")
            ?.optString("recipient") == "public-report-mock" &&
                a.optString("schema") == "road-guardian.traffic-zone-auto.v1" && a.optString("source") == "authored_contest_scenario" &&
                a.optString("dispatch_mode") == "automatic_contest" && a.optString("recipient") == "public-report-mock" && a.optString(
            "zone_label"
        ) == labels[zone] &&
                a.opt("recognition_performed") == false && a.opt("external_submission") == false &&
                a.optString("target_actor_id") == "vehicle-1" && a.optString("target_photo_sha256") == photo && a.optString(
            "target_plate"
        ) == "DEMO-001" &&
                obs.opt("restricted_zone") == true && dwell.toDouble()
            .isFinite() && dwell.toDouble() >= 120.0 && obs.optString("plate") == "DEMO-001" && actors.length() == 1 &&
                actor.optString("id") == "vehicle-1" && actor.optString("plate") == "DEMO-001" && actor.optString(
            "recognition_status"
        ) == "not_run"
    }

    fun locationValid(e: JSONObject): Boolean {
        val loc = e.optJSONObject("data")?.optJSONObject("location") ?: return false
        val lat = loc.opt("lat") as? Number ?: return false
        val lon = loc.opt("lon") as? Number ?: return false
        return lat.toDouble().isFinite() && lat.toDouble() in -90.0..90.0 && lon.toDouble()
            .isFinite() && lon.toDouble() in -180.0..180.0 &&
                (loc.opt("address_label") as? String)?.isNotBlank() == true && (loc.opt("road_id") as? String)?.isNotBlank() == true
    }

    private fun authorized(e: JSONObject): Boolean {
        val history = e.optJSONArray("history") ?: return false
        return (0 until history.length()).any { i ->
            val row = history.optJSONObject(i)
            if (row == null || row.optString("action") != "authored_traffic_auto_authorized" || row.optString(
                    "event_id"
                ) != e.optString("id")
            ) false
            else runCatching {
                val d = JSONObject(row.getString("detail"))
                d.optString("source") == "authored_contest_scenario" && d.optString("zone_type") == meta(
                    e
                )?.optString("zone_type") &&
                        d.opt("driver_confirmation") == false && d.opt("external_submission") == false
            }.getOrDefault(false)
        }
    }

    fun received(e: JSONObject): Boolean {
        if (!authored(e) || !authorized(e) || e.optString("status") != "acknowledged") return false
        val ds = e.optJSONArray("dispatches") ?: return false
        val matches = (0 until ds.length()).mapNotNull { ds.optJSONObject(it) }
            .filter { it.optString("recipient") == "public-report-mock" }
        val d = matches.singleOrNull() ?: return false
        return d.optString("event_id") == e.optString("id") && d.optString("status") == "acknowledged" &&
                d.optString("id")
                    .matches(Regex("[0-9a-f-]{36}")) && d.optString("receipt") == "MOCK-" + d.optString(
            "id"
        ).take(8)
    }

    fun mediaValid(e: JSONObject): Boolean {
        if (!authored(e) || !locationValid(e)) return false
        val v = e.optJSONObject("video") ?: return false
        val p = v.optJSONObject("input_photo") ?: return false
        return v.optString("source") == "synthetic_scene_fixture" && v.opt("camera_evidence") == false && v.opt(
            "integrity_valid"
        ) == true &&
                p.opt("integrity_valid") == true && p.optString("sha256") == photos[meta(e)?.optString(
            "zone_type"
        )] && p.optString("event_id") == e.optString("id") && p.optString("sensor_sha256") == e.optString(
            "hash"
        )
    }

    fun suffix(e: JSONObject) =
        if (received(e)) ":trafficZone:" + if (mediaValid(e)) "saved" else "media_invalid" else ""

    fun summary(e: JSONObject) = if (received(e) && mediaValid(e)) EventBriefState.Summary(
        "안전신문고 접수 완료",
        zoneLabel(e) + " · 현장 기록 확보"
    )
    else EventBriefState.Summary(
        zoneLabel(e) + " 기록",
        if (received(e)) "현장 자료 확인 필요" else "접수 확인 대기"
    )
}
