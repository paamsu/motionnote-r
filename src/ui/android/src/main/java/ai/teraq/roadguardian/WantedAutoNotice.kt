package ai.teraq.roadguardian

import org.json.JSONObject

/** 작성된 로컬 수배 목록과만 비교한다 */
object WantedAutoNotice {
    private const val PHOTO = "4a7f0da72354f1843871a46687c9de3affd358d402a97cfbb07ea5ed6da2b31f"
    fun authored(e: JSONObject): Boolean {
        val data = e.optJSONObject("data") ?: return false
        val context = data.optJSONObject("context") ?: return false
        val a = context.optJSONObject("wanted_auto") ?: return false
        val obs = data.optJSONObject("observations") ?: return false
        val actors = context.optJSONArray("actors") ?: return false
        val actor = actors.optJSONObject(0) ?: return false
        return BuildConfig.SYNTHETIC_BACKEND && e.optString("source") == "synthetic" && data.optString(
            "source"
        ) == "synthetic" && e.opt("evidence_valid") == true &&
                e.optJSONObject("assessment")
                    ?.optString("kind") == "wanted" && e.optJSONObject("assessment")
            ?.optString("recipient") == "112-mock" &&
                a.optString("schema") == "road-guardian.wanted-auto.v1" && a.optString("source") == "authored_contest_scenario" &&
                a.optString("dispatch_mode") == "automatic_contest" && a.optString("recipient") == "112-mock" && a.optString(
            "list_id"
        ) == "contest-wanted-list-v1" &&
                a.opt("recognition_performed") == false && a.opt("external_lookup") == false && a.opt(
            "external_submission"
        ) == false &&
                a.optString("target_actor_id") == "vehicle-1" && a.optString("target_photo_sha256") == PHOTO &&
                obs.opt("authorized_fixture_match") == true && obs.optString("plate") == "DEMO-001" && actors.length() == 1 &&
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

    fun received(e: JSONObject): Boolean {
        if (!authored(e) || e.optString("status") != "acknowledged") return false
        val ds = e.optJSONArray("dispatches") ?: return false
        val matches = (0 until ds.length()).mapNotNull { ds.optJSONObject(it) }
            .filter { it.optString("recipient") == "112-mock" }
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
                p.opt("integrity_valid") == true && p.optString("sha256") == PHOTO && p.optString("event_id") == e.optString(
            "id"
        ) && p.optString("sensor_sha256") == e.optString("hash")
    }

    fun suffix(e: JSONObject) =
        if (received(e)) ":wanted112:" + if (mediaValid(e)) "saved" else "media_invalid" else ""

    fun summary(e: JSONObject) =
        if (received(e) && mediaValid(e)) EventBriefState.Summary("수배 차량 112 신고 완료", "위치·현장 기록 확보")
        else EventBriefState.Summary("수배 목록 일치 기록", if (received(e)) "현장 자료 확인 필요" else "접수 확인 대기")
}
