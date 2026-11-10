package ai.teraq.roadguardian

import org.json.JSONObject

/** 같은 사건에 맞춘 작성된 추천 목록. 실제 보증이나 예약 가능 여부는 조회하지 않는다 */
object ServiceDemoRecommendation {
    fun present(event: JSONObject) =
        event.optJSONObject("data")?.optJSONObject("context")?.has("service_demo") == true

    fun checked(event: JSONObject): JSONObject? = runCatching {
        if (event.opt("source") != "synthetic" || event.opt("evidence_valid") != true || event.opt("diagnostic_valid") != true ||
            event.optJSONObject("assessment")?.optString("kind") != "diagnosis"
        ) return null
        val data = event.getJSONObject("data")
        val value = data.getJSONObject("context").getJSONObject("service_demo")
        if (data.optString("source") != "synthetic" || value.optString("schema") != "road-guardian.service-demo.v1" ||
            value.optString("source") != "authored_contest_scenario" || value.opt("external_submission") != false || value.opt(
                "live_warranty_lookup"
            ) != false
        ) return null
        val brand = value.optString("brand")
        val warranty = value.optString("warranty")
        if (brand !in setOf(
                "Hyundai",
                "Kia"
            ) || brand != data.optString("brand") || warranty !in setOf("covered", "not_covered")
        ) return null
        val offer = value.getJSONObject("offer")
        val center = offer.getJSONObject("center")
        if (offer.optString("source") != "synthetic" || offer.optString("status") != "accepted_mock" || center.opt(
                "available"
            ) != true
        ) return null
        val id =
            if (warranty == "not_covered") "garage-mock" else if (brand == "Hyundai") "bluehands-mock" else "kia-service-mock"
        val distance = when (id) {
            "garage-mock" -> 1.8
            "bluehands-mock" -> 2.4
            else -> 3.1
        }
        val name = when (id) {
            "garage-mock" -> "시험 정비소"
            "bluehands-mock" -> "블루핸즈 시험 센터"
            else -> "기아 시험 서비스센터"
        }
        if (center.optString("id") != id || center.optString("name") != name || center.opt("distance_km") !is Number || center.optDouble(
                "distance_km"
            ) != distance ||
            (if (warranty == "covered") center.optString("brand") != brand else !center.has("brand") || !center.isNull(
                "brand"
            ))
        ) return null
        val supports = center.optJSONArray("supports") ?: return null
        if ((0 until supports.length()).none { supports.optString(it) == "electrical" }) return null
        EvidenceJson.copy(value)
    }.getOrNull()

    fun name(value: JSONObject) =
        when (value.getJSONObject("offer").getJSONObject("center").getString("id")) {
            "bluehands-mock" -> "블루핸즈"
            "kia-service-mock" -> "기아 서비스센터"
            else -> "가까운 정비센터"
        }

    fun summary(value: JSONObject): String {
        val distance = value.getJSONObject("offer").getJSONObject("center").getDouble("distance_km")
        return "${if (value.getString("warranty") == "covered") "보증 적용" else "보증 외"} · ${name(value)} ${distance} km"
    }
}
