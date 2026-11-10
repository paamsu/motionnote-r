package ai.teraq.roadguardian

import android.view.Gravity
import android.widget.LinearLayout
import org.json.JSONObject

/** 사건을 먼저, 근거를 다음에 보여 준다. 생성한 주변 예시는 촬영 근거로 세지 않는다 */
object EventVisualBrief {
    fun render(
        ui: PhoneAccountUi,
        host: LinearLayout,
        event: JSONObject,
        client: BackendClient? = null,
        allowed: () -> Boolean = { false },
        onOpenDiagnostic: (() -> Unit)? = null,
        onEventChanged: ((JSONObject) -> Unit)? = null
    ) {
        if (EventFocusBrief.render(
                ui,
                host,
                event,
                client,
                allowed,
                onOpenDiagnostic,
                onEventChanged
            )
        ) return
        val kind =
            if (event.optString("status") == "input_review_required") "review" else if (EventBriefState.timelineState(
                    event
                ) != null
            ) "timeline_${EventBriefState.timelineState(event)}" else if (EventBriefState.isRoadObservation(
                    event
                )
            ) "road_observation" else if (EventBriefState.isVoltageObservation(event)) "diagnosis" else event.optJSONObject(
                "assessment"
            )?.optString("kind")
        val summary = EventBriefState.from(event)
        val helpAcknowledged = kind in setOf("emergency", "manual_help") && summary.next == "접수 완료"
        val helpNumber =
            event.optJSONObject("assessment")?.optString("recipient")?.removeSuffix("-mock")

        if (event.optString("status") == "dismissed" && event.opt("evidence_valid") == true) {
            host.addView(ui.text(summary.title, 40f, true).apply { gravity = Gravity.CENTER })
            host.addView(DriverEventGraphic(host.context, "timeline_cancelled", ui.light).apply {
                contentDescription = "검토 제외 완료 표시"
            }, LinearLayout.LayoutParams(-1, ui.dp(260)))
            host.addView(
                ui.text(summary.next, ui.evidenceCaptionSize, true)
                    .apply { gravity = Gravity.CENTER })
            return
        }
        val comparisonLabel = EventBriefState.roadComparisonLabel(event)
        val roadComparison =
            client != null && comparisonLabel in setOf("충격 재관측 · 비교 보기", "같은 기준 충격 없음 · 비교 보기")
        val representativeRoadPhoto =
            kind == "pothole" && event.optJSONObject("surround_views") == null &&
                    event.optJSONObject("surround") == null && event.optJSONObject("video")
                ?.optString("input_kind") in setOf(
                "authored_road_scene",
                "static_photo_encoded_as_video"
            ) &&
                    event.optJSONObject("video")?.optJSONObject("input_photo") != null
        val bumpPicture = EventBriefState.isBumpPicture(event)
        val roadPhoto = bumpPicture || representativeRoadPhoto || (kind in setOf(
            "observation",
            "road_observation"
        ) && event.optJSONObject("video")?.optString("input_kind") == "authored_road_scene") ||
                (kind == "violation" && event.optJSONObject("video")
                    ?.optString("input_kind") in setOf(
                    "authored_stationary_scene",
                    "static_photo_encoded_as_video"
                )) ||
                (kind == "wanted" && event.optJSONObject("video")?.optString("input_kind") in setOf(
                    "authored_moving_plate_scene",
                    "static_photo_encoded_as_video"
                ))
        val laneBrief =
            VideoLaneAnalysis.checked(event) != null && VideoLaneAnalysis.summary(event) == summary
        val config = host.resources.configuration
        val splitSurround = kind == "pothole" && event.optJSONObject("surround_views") != null &&
                config.screenWidthDp in 760..1099
        val splitPhoto = roadPhoto && config.screenWidthDp in 760..1099
        val helpBrief =
            kind == "emergency" || kind == "manual_help" || kind == "crash" || kind == "diagnosis"
        val roomyHelp = helpBrief && config.screenWidthDp >= 760 && config.screenHeightDp >= 600
        val roomyStatus = !roadPhoto && kind != "pothole" && !laneBrief &&
                config.screenWidthDp >= 760 && config.screenHeightDp >= 600
        val graphicHeight = when {
            laneBrief -> 260
            // 진단·보험 버튼 아래에 위치와 상세 버튼이 보이게 한다
            roomyHelp && config.screenWidthDp < 1100 && kind in setOf("crash", "diagnosis") -> 230
            roomyHelp && kind in setOf(
                "crash",
                "diagnosis"
            ) -> (config.screenHeightDp * 0.30f).toInt().coerceIn(260, 400)

            roomyStatus && config.fontScale < 1.3f -> (config.screenHeightDp * 0.38f).toInt()
                .coerceIn(300, 460)

            roomyStatus -> 300
            else -> 170
        }
        val scale = if (roomyStatus) 1.65f else if (config.screenWidthDp >= 760) 1.3f else 1f
        // 사진 사건과 그림 사건에서 제목의 역할을 같게 둔다
        // 그림 내부 배율과 관계없이 크기를 정한다
        val headingScale = if (config.screenWidthDp >= 760) 1.3f else 1f
        val heading = ui.text(
            if (helpAcknowledged) "$helpNumber 접수 완료" else if (roadComparison) "도로 전후 확인" else summary.title,
            34f * headingScale,
            true
        ).apply { gravity = Gravity.CENTER }
        host.addView(heading)
        if (roadPhoto && kind == "wanted") host.addView(
            ui.text(summary.next, 24f * scale, true).apply { gravity = Gravity.CENTER })
        var photoMap: EventLocationPanel? = null
        val body = if (client != null && EventLocationPanel.location(event) != null) {
            val row = LinearLayout(host.context).apply {
                // OEM 분할 화면의 첫 화면에 근거 사진과 지도를 함께 둔다
                orientation =
                    if (config.screenWidthDp >= 1100 || ((roadPhoto || kind == "pothole") && config.screenWidthDp >= 760)) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.TOP
            }
            val brief = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
            val map = EventLocationPanel(
                host.context,
                ui,
                event,
                compactCaption = splitSurround || splitPhoto,
                allowed = allowed
            )
            if (row.orientation == LinearLayout.HORIZONTAL) {
                // 짧은 요약 옆에 지도, 설명, 주소가 들어갈 자리를 남긴다
                brief.minimumHeight =
                    if (laneBrief || (roadPhoto && config.screenWidthDp < 1100)) 0 else ui.dp(360)
                row.addView(
                    brief,
                    LinearLayout.LayoutParams(
                        0,
                        -2,
                        if (kind == "pothole" && event.optJSONObject("surround_views") != null) (if (splitSurround) 1.8f else if (config.fontScale >= 1.3f) 0.66f else 0.70f) else if (splitPhoto) 1.8f else if (roadPhoto || laneBrief) 1f else 1.2f
                    ).apply { marginEnd = ui.dp(16) })
                if (kind == "diagnosis") map.useCompactFrame()
                else if (!roadComparison && (config.screenWidthDp >= 1100 || !roadPhoto)) map.fillRowHeight(
                    kind == "pothole" && !representativeRoadPhoto
                )
                if (kind == "pothole" || roadPhoto) photoMap = map
                // 사진 지도는 높이를 맞춘 틀을 쓴다. 주소와 출처가 사진 열에서 잘리지 않고 행 높이에 반영되게 한다
                row.addView(
                    map,
                    LinearLayout.LayoutParams(
                        0,
                        if (config.screenWidthDp < 1100 || photoMap != null || kind == "diagnosis") -2 else -1,
                        if (kind == "diagnosis") 0.8f else 1f
                    )
                )
            } else {
                row.addView(brief)
                row.addView(map)
            }
            host.addView(row, LinearLayout.LayoutParams(-1, -2))
            brief
        } else host

        fun line(value: String, size: Float, bold: Boolean = false) {
            body.addView(ui.text(value, size * scale, bold).apply { gravity = Gravity.CENTER })
        }
        if (roadComparison && client != null) {
            val result = ui.text("비교 기록 확인 중", ui.evidenceCaptionSize, true)
                .apply { gravity = Gravity.CENTER }
            body.addView(RoadComparisonPhotos(body.context, ui, event, client, allowed) { valid ->
                heading.text = when (valid) {
                    true -> if (comparisonLabel == "충격 재관측 · 비교 보기") "충격 다시 감지" else "같은 기준 충격 없음"
                    false -> "비교 기록 확인 필요"
                    null -> "도로 전후 확인"
                }
                result.text = when (valid) {
                    true -> "전후 기록 확보 완료"
                    false -> "상세 기록을 확인해 주세요"
                    null -> "비교 기록 확인 중"
                }
            })
            body.addView(result)
            EvidenceBundleStrip.render(ui, body, event)
            if (RoadDemoActionsPanel.eligible(event)) host.addView(
                RoadDemoActionsPanel(
                    ui,
                    event,
                    allowed
                )
            )
        } else if (kind == "parking") {
            line(summary.next, 22f)
            if (event.optJSONObject("surround_views") != null) body.addView(
                SyntheticSurroundPanel(
                    body.context,
                    ui,
                    event,
                    client,
                    allowed
                )
            )
            else body.addView(EventEvidencePhotoPanel(body.context, ui, event, client, allowed))
            ParkingIncidentSummary.add(ui, body, event)
            if (client != null && DemoActionDraftStore.eligibleAction(event) != null) body.addView(
                DemoActionPanel(ui, event, client, allowed, onEventChanged)
            )
        } else if (roadPhoto) {
            body.addView(
                EventEvidencePhotoPanel(
                    body.context,
                    ui,
                    event,
                    client,
                    allowed,
                    alignWithMap = true,
                    allowStaticRoadReference = representativeRoadPhoto || bumpPicture,
                    onPhotoHeight = { photoMap?.matchPhotoHeight(it) })
            )
            EvidenceBundleStrip.render(ui, host, event)
            if (representativeRoadPhoto && client != null && RoadDemoActionsPanel.eligible(event)) host.addView(
                RoadDemoActionsPanel(ui, event, allowed)
            )
        } else if (kind == "pothole") {
            if (ScenePhotoAnalysis.present(event) && summary.title != "포트홀 후보 확인") line(
                summary.next,
                22f
            )
            body.addView(SyntheticSurroundPanel(body.context, ui, event, client, allowed).apply {
                onPhotoHeight = { photoMap?.matchPhotoHeight(it) }
                fillWidth = body !== host
            })
            EvidenceBundleStrip.render(ui, host, event)
            if (client != null && RoadDemoActionsPanel.eligible(event)) host.addView(
                RoadDemoActionsPanel(ui, event, allowed)
            )
        } else {
            // 사건 그림은 표시 중인 사건을 기준으로 고른다
            val graphicKind =
                if (helpAcknowledged) "record_saved" else if (kind == "diagnosis" && summary.title == "저전압 다시 감지") "voltage_repeat"
                else if (kind == "diagnosis" && summary.title == "재측정 전압 회복") "voltage_recovered"
                else if (kind == "diagnosis" && summary.title == "전압 이상 없음") "voltage_normal"
                else if (kind == "diagnosis" && summary.voltageLow) "voltage_low"
                else if (kind == "emergency" && summary.title == "응답 시간 초과") "timeline_timeout"
                else if (kind == "manual_help") when (event.optJSONObject("assessment")
                    ?.optString("recipient")) {
                    "112-mock" -> "help_112"
                    "119-mock" -> "help_119"
                    else -> "review"
                }
                else if (kind == "crash" && summary.title == "보험 접수 완료") "record_saved"
                else if (kind == "crash" && summary.title == "보험 연결 실패") "review"
                else if (kind == "crash" && summary.title == "사고 감지") "crash"
                else if (VideoCollisionAnalysis.present(event)) {
                    if (VideoCollisionAnalysis.checked(event) != null) "record_saved" else "review"
                } else if (VideoLaneAnalysis.summary(event) == summary) when (VideoLaneAnalysis.checked(
                    event
                )?.optString("observation")) {
                    "repeated_lane_changes" -> "reckless"
                    "lane_stable" -> "record_saved"
                    else -> "review"
                } else if (EventBriefState.roadVideoSummary(event) == summary) "review" else if (ScenePhotoAnalysis.summary(
                        event
                    ) == summary
                ) when {
                    summary.title in setOf(
                        "사진 판독 보류",
                        "사진 판독 확인 필요",
                        "노면 확인 필요",
                        "도로 충격 기록"
                    ) -> "review"

                    ScenePhotoAnalysis.checked(event)
                        ?.optString("class") == "raised_bump" -> "speed_bump"

                    else -> "observation"
                } else when (EventBriefState.weatherTimelineState(event)) {
                    "collecting" -> "visibility_change"
                    "washer_suppressed" -> "washer"
                    "recovered" -> "record_saved"
                    else -> if (kind == "road_observation") "pothole" else kind.orEmpty()
                }
            if (laneBrief) body.addView(ui.text("주행 변화", 22f).apply { gravity = Gravity.CENTER })
            val graphic =
                DriverEventGraphic(body.context, graphicKind, ui.light, summary.voltageLow)
            body.addView(graphic, LinearLayout.LayoutParams(-1, ui.dp(graphicHeight)))
            if (laneBrief) body.addView(
                ui.text(summary.next, ui.evidenceCaptionSize, true)
                    .apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
            else if (client == null || DemoActionDraftStore.eligibleAction(event) == null) line(
                if (helpAcknowledged) "기록 보관 완료" else summary.next,
                24f,
                true
            )
            EvidenceBundleStrip.render(ui, body, event)
            if (client != null && DemoActionDraftStore.eligibleAction(event) != null) {
                body.addView(
                    DemoActionPanel(
                        ui,
                        event,
                        client,
                        allowed,
                        onEventChanged,
                        onPrepared = {
                            graphic.showCompletion()
                            heading.text =
                                if (kind == "crash") "보험 접수 완료" else if (DemoActionDraftStore.eligibleAction(
                                        event
                                    ) == "service_handoff"
                                ) "서비스센터 기록 전달 완료" else "서비스센터 예약 완료"
                        })
                )
            } else if (summary.action != null && !DiagnosticEvidenceReview.canOpen(event)) {
                val details =
                    LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
                details.addView(
                    ui.text(
                        if (kind == "crash") "실제 보험사 통화는 연결되지 않습니다" else "실제 센터 검색·예약은 진행되지 않습니다",
                        20f
                    ).apply { gravity = Gravity.CENTER })
                ui.disclosure(body, summary.action, details)
            }
            if (kind == "diagnosis" && DiagnosticEvidenceReview.canOpen(event) && onOpenDiagnostic != null) {
                body.addView(
                    ui.button("재측정 기록 보기") { if (allowed()) onOpenDiagnostic() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
            }
        }
    }
}
