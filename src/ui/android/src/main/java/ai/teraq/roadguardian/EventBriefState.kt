package ai.teraq.roadguardian

import org.json.JSONObject

/** 가장 최근에 확인된 관측값을 쓴다. 처음의 저전압을 현재 결론으로 쓰지 않는다 */
object EventBriefState {
    data class Summary(
        val title: String,
        val next: String,
        val action: String? = null,
        val voltageLow: Boolean = false
    )

    fun authoredRainCandidate(event: JSONObject): Boolean {
        if (event.opt("source") != "synthetic" || event.opt("evidence_valid") != true) return false
        val assessment = event.optJSONObject("assessment") ?: return false
        if (assessment.opt("kind") != "rain") return false
        val weather = assessment.optJSONObject("weather_assessment") ?: return false
        if (weather.opt("source") != "authored_context_gate" || weather.opt("status") != "authored_rain_candidate" ||
            weather.opt("condition") != "rain" || weather.opt("visual_analysis_performed") != false
        ) return false
        if (!assessment.has("weather_timeline_assessment") || assessment.isNull("weather_timeline_assessment")) return true
        val timeline = assessment.optJSONObject("weather_timeline_assessment") ?: return false
        return timeline.opt("source") == "authored_weather_timeline_evaluation" &&
                timeline.opt("status") == "sustained_candidate" && timeline.opt("camera_analysis_performed") == false
    }

    fun weatherTimelineState(event: JSONObject): String? {
        if (event.optString("source") != "synthetic" || event.optJSONObject("assessment")
                ?.optString("kind") != "observation"
        ) return null
        val timeline =
            event.optJSONObject("assessment")?.optJSONObject("weather_timeline_assessment")
                ?: return null
        if (timeline.optString("source") != "authored_weather_timeline_evaluation" || timeline.opt("camera_analysis_performed") != false) return null
        return timeline.optString("status")
            .takeIf { it in setOf("collecting", "washer_suppressed", "recovered") }
    }

    fun timelineState(event: JSONObject): String? {
        if (event.optString("source") != "synthetic" || event.optJSONObject("assessment")
                ?.optString("kind") != "observation"
        ) return null
        val timeline =
            event.optJSONObject("assessment")?.optJSONObject("emergency_assessment") ?: return null
        if (timeline.optString("source") != "authored_timeline_evaluation") return null
        return timeline.optString("status")
            .takeIf { it in setOf("waiting", "responded", "cancelled") }
    }

    fun isVoltageObservation(event: JSONObject) = event.optString("source") == "synthetic" &&
            event.optJSONObject("assessment")?.optString("kind") == "observation" &&
            event.optString("observation_purpose") == "voltage_review" &&
            event.optJSONObject("data")?.optString("scenario") in setOf("diagnosis", "healthy")

    fun isRoadObservation(event: JSONObject) = event.optString("source") == "synthetic" &&
            event.optJSONObject("assessment")?.optString("kind") == "observation" &&
            event.optString("observation_purpose") == "road_followup"

    fun roadComparisonLabel(event: JSONObject): String? {
        if (event.optJSONObject("assessment")?.optString("kind") != "pothole") return null
        val case = event.optJSONObject("road_followup_case") ?: return null
        val stage = case.optString("stage")
        if (stage == "awaiting_observation") return null
        val unavailable = "비교 확인 필요 · 기록 보기"
        if (stage == "comparison_invalid" || event.opt("evidence_valid") != true) return unavailable
        if (RoadObservationSource.reference(event) == null) return null
        val before = (case.optJSONObject("before")?.opt("peak_g") as? Number)?.toDouble()
            ?: return unavailable
        val after = (case.optJSONObject("after")?.opt("peak_g") as? Number)?.toDouble()
            ?: return unavailable
        if (!before.isFinite() || !after.isFinite() || before < 0 || after < 0) return unavailable
        return when (stage) {
            "repeat_signal" -> if (after > 1.5) "충격 재관측 · 비교 보기" else unavailable
            "no_repeat_signal" -> if (after <= 1.5) "같은 기준 충격 없음 · 비교 보기" else unavailable
            else -> null
        }
    }

    private fun voltageObservation(event: JSONObject): Summary {
        val unknown = Summary("측정 확인 필요", "상세 기록을 확인해 주세요")
        val report =
            event.optJSONObject("assessment")?.optJSONObject("diagnostic") ?: return unknown
        if (event.opt("diagnostic_valid") != true || report.optString("verdict") != "no_anomaly_in_observed_signals") return unknown
        val metrics = report.optJSONObject("metrics") ?: return unknown
        fun value(key: String) = (metrics.opt(key) as? Number)?.toDouble() ?: Double.NaN
        val voltage = value("voltage_median_v")
        val baseline = value("baseline_v")
        val delta = value("delta_v")
        val count = value("matched_samples")
        if (!voltage.isFinite() || !baseline.isFinite() || !delta.isFinite() || !count.isFinite() || count < 20 || count % 1.0 != 0.0 || delta < -1 || kotlin.math.abs(
                voltage - baseline - delta
            ) > .0011
        ) return unknown
        return Summary("전압 이상 없음", "측정 구간에서 확인한 결과입니다")
    }

    fun isBumpPicture(event: JSONObject): Boolean =
        event.optString("source") == "synthetic" && event.opt("evidence_valid") == true &&
                event.optString("status") !in setOf("dismissed", "input_review_required") &&
                event.optJSONObject("data")?.optString("scenario") == "speed_bump" &&
                event.optJSONObject("assessment")?.optString("kind") == "observation" &&
                event.optJSONObject("video")
                    ?.optString("input_kind") == "static_photo_encoded_as_video" &&
                event.optJSONObject("video")?.opt("integrity_valid") == true

    fun roadVideoSummary(event: JSONObject): Summary? {
        if (event.optString("source") != "synthetic" || event.opt("evidence_valid") != true ||
            event.optString("status") in setOf("dismissed", "input_review_required")
        ) return null
        val assessment = event.optJSONObject("assessment") ?: return null
        val road = assessment.optJSONObject("road_assessment") ?: return null
        val video = event.optJSONObject("video") ?: return null
        if (assessment.optString("kind") != "observation" || road.optString("source") != "declared_surface_and_impact" ||
            road.optString("surface") != "unknown" || road.optString("status") != "withheld" ||
            road.opt("visual_analysis_performed") != false || video.opt("integrity_valid") != true ||
            video.optString("input_kind") != "authored_road_scene" || video.opt("camera_evidence") != false
        ) return null
        val peak =
            (assessment.optJSONObject("metrics")?.opt("peak_vertical_g") as? Number)?.toDouble()
                ?: return null
        if (!peak.isFinite() || peak < 0) return null
        return if (peak > 1.5) Summary("도로 충격 기록", "기록 확보 · 원인 확인 필요")
        else Summary("도로 관측 기록", "기록 확보 · 노면 판단 보류")
    }

    fun from(event: JSONObject): Summary {
        if (event.opt("evidence_valid") != true) return Summary("기록 확인 필요", "상세 기록을 확인해 주세요")
        if (event.optString("status") == "dismissed") return Summary("검토에서 제외", "제외한 기록입니다")
        if (event.optString("status") == "input_review_required") return Summary(
            "입력 확인 필요",
            "원문 보관 · 판정 보류"
        )
        when (weatherTimelineState(event)) {
            "collecting" -> return Summary("시야 변화 확인 중", "기록 확보 완료")
            "washer_suppressed" -> return Summary("워셔 사용 구간", "강우 판단 보류 · 기록 보관")
            "recovered" -> return Summary("시야 저하 신호 해제", "합성 신호 · 실제 시야 확인 필요")
        }
        if (event.optJSONObject("assessment")
                ?.optString("kind") == "observation" && event.optJSONObject("assessment")
                ?.optJSONObject("weather_assessment")?.optString("status") == "withheld"
        ) return Summary("강우 판정 보류", "기록을 보관했습니다")
        if (event.optString("source") == "synthetic" && event.optJSONObject("assessment")
                ?.optString("kind") == "observation"
        ) {
            val timeline = event.optJSONObject("assessment")?.optJSONObject("emergency_assessment")
            if (timeline?.optString("source") == "authored_timeline_evaluation") when (timeline.optString(
                "status"
            )) {
                "waiting" -> return Summary("운전자 응답 확인 중", "도움 요청 대기")
                "responded" -> return Summary("운전자 응답 확인", "도움 요청 안 함")
                "cancelled" -> return Summary("도움 요청 취소", "요청을 보내지 않았습니다")
            }
        }
        if (isRoadObservation(event)) return Summary("도로 후속 관측", "최초 기록과 비교해 주세요")
        if (isVoltageObservation(event)) return voltageObservation(event)
        if (event.optJSONObject("assessment")
                ?.optString("kind") == "observation" && VideoCollisionAnalysis.present(event)
        ) {
            return if (VideoCollisionAnalysis.checked(event) != null) Summary(
                "차량 영상 기록",
                "영상·판독 기록 보관"
            )
            else Summary("영상 기록 확인 필요", "상세 기록을 확인해 주세요")
        }
        if (PlateAnalysis.present(event)) return Summary("번호판 판독 기록", PlateAnalysis.detail(event))
        VideoLaneAnalysis.summary(event)?.let { return it }
        if (event.optJSONObject("assessment")
                ?.optString("kind") == "pothole" && VideoRoadAnalysis.present(event)
        ) {
            val video = VideoRoadAnalysis.checked(event)
            if (video == null) return Summary("영상 기록 확인 필요", "충격 기록 보관 · 영상 확인 필요")
            if (video.optString("road_class") != "pothole") return Summary(
                "도로 충격 기록",
                "원인 확인 필요 · 기록 확보"
            )
        }
        ScenePhotoAnalysis.summary(event)?.let { return it }
        if (isBumpPicture(event)) return Summary("과속방지턱 비교", "충격 기록 보관 완료")
        roadVideoSummary(event)?.let { return it }
        return when (event.optJSONObject("assessment")?.optString("kind")) {
            "violation" -> if (TrafficZoneAutoNotice.authored(event)) TrafficZoneAutoNotice.summary(
                event
            ) else Summary("주정차 위반 후보", "기록 보관 · 확인 필요")

            "reckless" -> Summary("급격한 주행 변화", "기록 보관 · 확인 필요")
            "wanted" -> if (WantedAutoNotice.authored(event)) WantedAutoNotice.summary(event) else Summary(
                "목록 번호 일치",
                "확인 필요"
            )

            "rain" -> Summary("전방 시야 저하", rainAdvice(event))
            "emergency", "manual_help" -> emergency(event)
            "observation" -> Summary("관측 자료 확인", "측정 기록 보관 완료")
            "parking" -> Summary(
                "주차 중 충격 기록",
                "주변 상황 확인 필요",
                if (event.optJSONObject("insurance_case")
                        ?.optString("status") == "ready"
                ) "보험사 연결" else null
            )

            "pothole" -> Summary("포트홀 감지", "센서 기록 확보")
            "crash" -> crash(event)
            "diagnosis" -> diagnosis(event)
            else -> Summary("차량 상태 확인", "측정 기록을 확인해 주세요")
        }
    }

    fun rainAdvice(event: JSONObject): String {
        val radar = event.optJSONObject("assessment")?.optJSONObject("weather_assessment")
            ?.optJSONObject("radar")
        val authored =
            radar?.optString("source") == "authored_synthetic" && radar.opt("live_sensor_read") == false
        return when (if (authored) radar?.optString("state") else "unknown") {
            "degraded", "unavailable" -> "레이더 확인 필요 · 감속하세요"
            "available" -> "시야 저하 · 감속하세요"
            else -> "레이더 미확인 · 감속하세요"
        }
    }

    internal fun authoredDriverEmergency(event: JSONObject): Boolean {
        val data = event.optJSONObject("data") ?: return false
        val authored =
            data.optJSONObject("context")?.optJSONObject("driver_emergency") ?: return false
        val observation = data.optJSONObject("observations") ?: return false
        return event.optString("source") == "synthetic" && event.opt("evidence_valid") == true &&
                event.optJSONObject("assessment")?.optString("kind") == "emergency" &&
                event.optJSONObject("assessment")?.optString("recipient") == "119-mock" &&
                authored.optString("schema") == "road-guardian.driver-emergency.v1" &&
                authored.optString("source") == "authored_contest_scenario" && authored.optString("subject") == "own_driver" &&
                authored.optString("movement_state") == "authored_no_movement" &&
                authored.optString("response_state") == "authored_unresponsive" &&
                authored.optString("dispatch_mode") == "automatic_contest" && authored.optString("recipient") == "119-mock" &&
                authored.opt("recognition_performed") == false && authored.opt("external_submission") == false &&
                observation.opt("unresponsive_test_signal") == true &&
                !observation.has("emergency_timeline") && !observation.has("emergency_timeline_analysis")
    }

    internal fun driverEmergencyMediaValid(event: JSONObject): Boolean {
        if (!authoredDriverEmergency(event)) return false
        val video = event.optJSONObject("video") ?: return false
        val photo = video.optJSONObject("input_photo") ?: return false
        return video.opt("integrity_valid") == true && photo.opt("integrity_valid") == true &&
                photo.optString("event_id") == event.optString("id") &&
                photo.optString("sensor_sha256") == event.optString("hash")
    }

    private fun emergency(event: JSONObject): Summary {
        val assessment = event.optJSONObject("assessment")
        val target = assessment?.optString("recipient")
        if (target !in setOf("119-mock", "112-mock")) return Summary("요청 확인 필요", "상세 기록을 확인해 주세요")
        val number = target!!.removeSuffix("-mock")
        val title =
            if (authoredDriverEmergency(event)) "운전자 긴급 도움" else if (assessment?.optString("kind") == "emergency") "응답 시간 초과" else "$number 도움 요청"
        val deliveries = event.optJSONArray("dispatches")
        val delivery =
            (0 until (deliveries?.length() ?: 0)).mapNotNull { deliveries?.optJSONObject(it) }
                .firstOrNull { it.optString("recipient") == target }
        val receipt = delivery?.optString("receipt")?.takeIf { it != "null" && it.isNotBlank() }
        val state = EmergencyDelivery.state(
            target,
            delivery?.optString("recipient"),
            delivery?.optString("status"),
            receipt,
            event.opt("evidence_valid") == true
        )
        return Summary(
            title, when (state) {
                "acknowledged" -> "접수 완료"
                "retry" -> "전달 실패 · 다시 시도해 주세요"
                "evidence_invalid" -> "자료 확인 실패 · 판정 보류"
                else -> "접수 확인 대기"
            }
        )
    }

    private fun crash(event: JSONObject): Summary {
        val unknown = Summary("보험 연결 상태 미확인", "상세 기록을 확인해 주세요")
        val insurance = event.optJSONObject("insurance_case") ?: return unknown
        if (event.optString("source") != "synthetic" || insurance.optString("source") != "synthetic") return unknown
        return when (insurance.optString("status")) {
            "ready" -> Summary(
                if (event.optJSONObject("assessment")?.optJSONObject("collision_assessment")
                        ?.optString("status") == "authored_collision_candidate"
                ) "충돌 후보 확인" else "사고 감지", "보험사 연결 안내를 확인하세요", "보험사 연결 안내"
            )

            "acknowledged_mock" -> if (insurance.opt("receipt")
                    .let { it is String && it.isNotBlank() && it != "null" }
            )
                Summary(
                    "보험 접수 완료",
                    if (insurance.opt("video_added_after_dispatch") == true) "추가 영상은 기존 접수에 미포함" else "접수 기록 보관 완료"
                ) else unknown

            "retry_pending" -> Summary("보험 연결 실패", "다시 시도해 주세요")
            "awaiting_receipt" -> Summary("보험 접수 확인 중", "수신 확인을 기다립니다")
            "review_required" -> Summary("차량·보험 확인 필요", "상세 기록을 확인해 주세요")
            "payload_invalid" -> Summary("접수 자료 확인 실패", "상세 기록을 확인해 주세요")
            "evidence_invalid" -> Summary("기록 확인 필요", "상세 기록을 확인해 주세요")
            else -> unknown
        }
    }

    private fun diagnosis(event: JSONObject): Summary {
        val repair = event.optJSONObject("repair_case")
        val stage = repair?.optString("stage")
        val unknown = Summary("측정 확인 필요", "상세 기록을 확인해 주세요")
        if (event.opt("diagnostic_valid") != true || stage in setOf(
                "evidence_invalid",
                "unavailable"
            )
        ) return unknown
        if (stage == "follow_up_invalid") return Summary("재측정 확인 필요", "재측정 자료를 확인해 주세요")
        if (stage == "center_accepted_mock") return Summary("재측정 대기", "이후 측정 자료를 확인해 주세요")
        val diagnostic = event.optJSONObject("assessment")?.optJSONObject("diagnostic")
        if (stage in setOf("observed_signal_recovered", "follow_up_required")) {
            val after = repair?.optJSONObject("after") ?: return unknown
            val delta = after.optDouble("delta_v", Double.NaN)
            val voltage = after.optDouble("voltage_median_v", Double.NaN)
            val baseline = after.optDouble("baseline_v", Double.NaN)
            if (!delta.isFinite() || !voltage.isFinite() || !baseline.isFinite() || after.optInt(
                    "matched_samples",
                    0
                ) < 20
            ) return unknown
            if (stage == "observed_signal_recovered") return if (delta >= -1) Summary(
                "재측정 전압 회복",
                "이전 측정 기록을 보관했습니다",
                if (diagnostic?.optString("verdict") == "inspection_required") "서비스센터 기록 전달" else null
            ) else unknown
            if (delta >= -1) return unknown
            return if (diagnostic?.optString("verdict") == "inspection_required") Summary(
                "저전압 다시 감지",
                "서비스센터 방문을 추천합니다",
                "서비스센터 안내",
                true
            )
            else Summary("저전압 확인", "서비스센터 점검을 추천합니다", "서비스센터 안내", true)
        }
        return when (diagnostic?.optString("verdict")) {
            "inspection_required" -> Summary("저전압 확인", "서비스센터 안내를 확인하세요", "서비스센터 안내", true)
            "no_anomaly_in_observed_signals" -> Summary("전압 이상 없음", "측정 구간에서 확인한 결과입니다")
            else -> unknown
        }
    }
}
