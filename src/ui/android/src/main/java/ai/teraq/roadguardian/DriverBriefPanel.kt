package ai.teraq.roadguardian

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject

/** 운전자용 요약 보기. 최신 여부와 OEM 권한은 메인 화면이 판단한다 */
object DriverBriefPanel {
    fun render(
        ui: PhoneAccountUi,
        host: FrameLayout,
        state: String,
        comparisonReady: Boolean = false,
        event: JSONObject? = null
    ) {
        val eventBrief =
            if (state in setOf("drive", "simulated_drive")) verifiedBrief(event) else null
        val showComparison = comparisonReady && state in setOf("drive", "simulated_drive")
        val title =
            eventBrief?.second?.title ?: if (showComparison) "시험 비교 자료 준비" else when (state) {
                "drive" -> "주행 기어"
                "reverse" -> "후진 기어"
                "neutral" -> "중립 기어"
                "simulated_drive" -> "주행 시연"
                "parked", "simulated_park" -> "화면 열람 제한"
                else -> "차량 상태 확인 중"
            }
        val message = if (eventBrief != null) when (eventBrief.second.title) {
            "포트홀 감지" -> "기록 확보 완료"
            "차선 변경 기록 확인" -> "정차 후 기록 확인"
            "전방 시야 저하" -> "속도를 줄이고 거리를 확보하세요"
            "시야 변화 확인 중" -> "기록 보관"
            "워셔 사용 구간" -> "강우 판단 보류"
            "시야 저하 신호 해제" -> "시야를 확인하세요"
            "저전압 다시 감지" -> "서비스센터 방문 추천"
            "재측정 전압 회복" -> "측정 구간의 결과"
            "전압 이상 없음" -> "측정 구간의 결과"
            "주차 중 충격 기록" -> "충격 기록 보관 · 정차 후 확인"
            "응답 시간 초과", "112 도움 요청", "119 도움 요청" -> "${eventBrief.second.next} · 기록 보관"
            "사고 감지" -> "정차 후 보험 안내"
            "저전압 확인" -> "정차 후 센터 안내"
            "도로 충격 기록" -> "원인 확인 필요 · 기록 확보"
            else -> "센서 기록 보관"
        } else if (showComparison) "자세한 내용은 주차 후 확인하세요" else when (state) {
            "drive", "reverse", "neutral", "simulated_drive" -> "주차 후 기록 확인"
            "parked", "simulated_park" -> "차량 화면 제한 중"
            else -> "상태 확인 후 열람"
        }
        val scale = (host.resources.configuration.screenWidthDp / 1100f).coerceIn(1f, 1.5f)
        val body = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32))
        }
        body.addView(ui.text(title, 30f * scale, true).apply { gravity = Gravity.CENTER })
        if (eventBrief != null) body.addView(
            DriverEventGraphic(
                host.context,
                eventBrief.first,
                ui.light,
                eventBrief.second.voltageLow
            ), LinearLayout.LayoutParams(-1, ui.dp((170 * scale).toInt()))
        )
        else {
            val icon = when {
                showComparison -> "기록"
                state in setOf("parked", "simulated_park") -> "shield"
                state in setOf("drive", "simulated_drive", "reverse", "neutral") -> "차량"
                else -> "clock"
            }
            body.addView(
                CockpitIcon(host.context, icon, PleosPalette.ink(ui.light)).apply {
                    contentDescription = when (icon) {
                        "차량" -> "차량 운행 상태"
                        "shield" -> "차량 화면 제한"
                        "기록" -> "기록 확인 안내"
                        else -> "차량 상태 확인 대기"
                    }
                },
                LinearLayout.LayoutParams(
                    ui.dp((170 * scale).toInt()),
                    ui.dp((170 * scale).toInt())
                ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        body.addView(ui.text(message, 20f * scale).apply {
            gravity = Gravity.CENTER
            setTextColor(ui.muted)
        })
        host.addView(body, FrameLayout.LayoutParams(-1, -1))
    }

    internal fun verifiedBrief(event: JSONObject?): Pair<String, EventBriefState.Summary>? =
        runCatching {
            if (event == null || event.opt("source") != "synthetic" || event.opt("evidence_valid") != true || event.optString(
                    "status"
                ) == "dismissed"
            ) return@runCatching null
            val kind =
                event.optJSONObject("assessment")?.optString("kind") ?: return@runCatching null
            val summary = EventBriefState.from(event)
            val recognized = when (kind) {
                "emergency", "manual_help", "parking", "wanted", "violation" -> DriverEventNoticeState.isEligible(
                    event
                )

                "rain" -> EventBriefState.authoredRainCandidate(event) && summary.title == "전방 시야 저하"
                "observation" -> (VideoLaneAnalysis.repeatedCandidate(event) && summary.title == "차선 변경 기록 확인") || (EventBriefState.weatherTimelineState(
                    event
                ) != null && summary.title in setOf("시야 변화 확인 중", "워셔 사용 구간", "시야 저하 신호 해제"))

                "pothole" -> summary.title in setOf("포트홀 감지", "도로 충격 기록")
                "crash" -> event.optJSONObject("insurance_case")
                    ?.optString("status") == "ready" && summary.title == "사고 감지"

                "diagnosis" -> event.opt("diagnostic_valid") == true && event.optJSONObject("repair_case")
                    ?.optString("stage") in setOf(
                    "measurement_review",
                    "observed_signal_recovered",
                    "follow_up_required"
                ) && summary.title in setOf("저전압 확인", "저전압 다시 감지", "재측정 전압 회복", "전압 이상 없음")

                else -> false
            }
            val helpGraphic =
                if (kind == "wanted" && WantedAutoNotice.received(event)) "help_112" else if (kind in setOf(
                        "emergency",
                        "manual_help"
                    )
                ) when (event.getJSONObject("assessment").optString("recipient")) {
                    "112-mock" -> "help_112"
                    "119-mock" -> "help_119"
                    else -> null
                } else null
            val graphic = helpGraphic ?: when (summary.title) {
                "저전압 다시 감지" -> "voltage_repeat"
                "재측정 전압 회복" -> "voltage_recovered"
                "전압 이상 없음" -> "voltage_normal"
                "저전압 확인" -> "voltage_low"
                else -> null
            }
            ?: if (summary.title == "도로 충격 기록") "record_saved" else if (VideoLaneAnalysis.repeatedCandidate(
                    event
                )
            ) "reckless" else when (EventBriefState.weatherTimelineState(event)) {
                "collecting" -> "visibility_change"
                "washer_suppressed" -> "washer"
                "recovered" -> "record_saved"
                else -> kind
            }
            if (recognized) graphic to summary else null
        }.getOrNull()
}
