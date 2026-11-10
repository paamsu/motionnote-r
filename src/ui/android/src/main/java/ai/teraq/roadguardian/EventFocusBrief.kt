package ai.teraq.roadguardian

import android.view.Gravity
import android.widget.LinearLayout
import org.json.JSONObject

/** 정차 중 사건 화면. 왼쪽에 근거, 오른쪽에 위치와 다음 조치를 둔다 */
internal object EventFocusBrief {
    fun render(
        ui: PhoneAccountUi,
        host: LinearLayout,
        event: JSONObject,
        client: BackendClient?,
        allowed: () -> Boolean,
        onOpenDiagnostic: (() -> Unit)?,
        onEventChanged: ((JSONObject) -> Unit)?
    ): Boolean {
        val config = host.resources.configuration
        if (client == null || config.screenWidthDp < 1100 || config.fontScale >= 1.3f ||
            event.opt("evidence_valid") != true || event.optString("status") in setOf(
                "dismissed",
                "input_review_required"
            )
        ) return false
        val summary = EventBriefState.from(event)
        val kind =
            if (EventBriefState.isVoltageObservation(event)) "diagnosis" else event.optJSONObject("assessment")
                ?.optString("kind")
        val surround = kind in setOf(
            "pothole",
            "parking"
        ) && event.optJSONObject("surround_views") != null && EventBriefState.roadComparisonLabel(
            event
        ) == null
        val lanePreview =
            VideoLaneAnalysis.repeatedCandidate(event) && VideoLaneAnalysis.summary(event) == summary &&
                    runCatching { EventEvidencePhotoPanel.metadata(event) != null }.getOrDefault(
                        false
                    )
        if (!surround && !lanePreview && kind !in setOf("crash", "diagnosis")) return false
        if (kind == "crash" && summary.title !in setOf("사고 감지", "보험 접수 완료")) return false
        if (kind == "diagnosis" && summary.title !in setOf(
                "저전압 확인",
                "저전압 다시 감지",
                "재측정 전압 회복",
                "전압 이상 없음"
            )
        ) return false
        val heading = ui.text(summary.title, 44f, true)
        val row = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            isBaselineAligned = false
        }
        val visual = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
        val next = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        fun section(view: android.view.View, container: LinearLayout = next) {
            container.addView(
                view,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24) })
        }
        row.addView(visual, LinearLayout.LayoutParams(0, -2, 1.5f).apply { marginEnd = ui.dp(32) })
        row.addView(next, LinearLayout.LayoutParams(0, -2, 1f))
        host.addView(row)
        next.addView(heading)
        val map = EventLocationPanel.location(event)?.let {
            EventLocationPanel(
                host.context,
                ui,
                event,
                compactCaption = true,
                allowed = allowed
            ).apply { useCompactFrame() }
        }
        if (lanePreview) {
            val stored = ui.text("사진 확인 중", 30f, true)
            next.addView(stored)
            if (BuildConfig.SYNTHETIC_BACKEND) {
                // 공모전 그림은 표시용이다. 사건 원본 데이터를 바꾸지 않는다
                val illustration = android.widget.ImageView(host.context).apply {
                    setImageResource(R.drawable.lane_representative_v644)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    contentDescription = "차 안에서 본 차선 변경 대표 장면. 분석 원본 사진과 별도 그림"
                }
                visual.addView(illustration, LinearLayout.LayoutParams(-1, -2))
                visual.addView(ui.text("대표 장면", 18f).apply {
                    gravity = Gravity.START
                    setTextColor(ui.muted)
                })
                val original =
                    LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
                original.addView(
                    EventEvidencePhotoPanel(
                        host.context,
                        ui,
                        event,
                        client,
                        allowed,
                        fitPhotoAspect = true,
                        externalStatus = stored
                    )
                )
                ui.disclosure(visual, "분석 원본 사진", original)
            } else {
                visual.addView(
                    EventEvidencePhotoPanel(
                        host.context,
                        ui,
                        event,
                        client,
                        allowed,
                        fitPhotoAspect = true,
                        externalStatus = stored
                    )
                )
            }
            if (map != null) section(map)
            return true
        }
        if (surround) {
            val stored = ui.text("기록 확인 중", 30f, true)
            next.addView(stored)
            visual.addView(SyntheticSurroundPanel(host.context, ui, event, client, allowed).apply {
                fillWidth = true
                compactPresentation = true
                externalStatus = stored
            })
            if (kind == "parking") {
                ParkingIncidentSummary.add(ui, next, event)
                if (DemoActionDraftStore.eligibleAction(event) != null) next.addView(
                    DemoActionPanel(
                        ui,
                        event,
                        client,
                        allowed,
                        onEventChanged,
                        onPrepared = { heading.text = "보험 접수 완료" },
                        compactPresentation = true
                    )
                )
            }
            if (RoadDemoActionsPanel.eligible(event)) next.addView(
                RoadDemoActionsPanel(
                    ui,
                    event,
                    allowed,
                    stacked = true
                )
            )
            if (map != null) section(map)
            return true
        }
        val graphicKind = when (summary.title) {
            "보험 접수 완료" -> "record_saved"
            "저전압 다시 감지" -> "voltage_repeat"
            "재측정 전압 회복" -> "voltage_recovered"
            "전압 이상 없음" -> "voltage_normal"
            else -> if (kind == "crash") "crash" else "voltage_low"
        }
        val graphic = DriverEventGraphic(host.context, graphicKind, ui.light, summary.voltageLow)
        var observation: android.widget.TextView? = null
        if (kind == "crash" && runCatching { EventEvidencePhotoPanel.metadata(event) != null }.getOrDefault(
                false
            )
        ) {
            val stored = ui.text("기록 확인 중", 30f, true)
            next.addView(stored)
            visual.addView(
                EventEvidencePhotoPanel(
                    host.context,
                    ui,
                    event,
                    client,
                    allowed,
                    fitPhotoAspect = true,
                    externalStatus = stored
                )
            )
        } else {
            visual.addView(graphic, LinearLayout.LayoutParams(-1, ui.dp(300)))
            if (summary.title in setOf("저전압 다시 감지", "재측정 전압 회복")) {
                val labels = LinearLayout(host.context)
                for (label in listOf("이전 기록", "현재 기록")) labels.addView(
                    ui.text(label, 24f, true).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                visual.addView(labels, LinearLayout.LayoutParams(ui.dp(480), -2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = -ui.dp(48)
                })
            }
            observation = ui.text(
                when (summary.title) {
                    "저전압 다시 감지" -> "반복 기록 확보 · 방문 추천"
                    "재측정 전압 회복" -> "이전 이상 기록 보관"
                    "전압 이상 없음" -> "측정 기록 보관 완료"
                    else -> "이상 발생 당시 기록 확보 완료"
                }, 30f, true
            )
            next.addView(observation)
            EvidenceBundleStrip.render(ui, visual, event, compact = true)
        }
        if (DemoActionDraftStore.eligibleAction(event) != null) {
            section(DemoActionPanel(ui, event, client, allowed, onEventChanged, onPrepared = {
                observation?.visibility = android.view.View.GONE
                heading.text =
                    if (kind == "crash") "보험 접수 완료" else if (DemoActionDraftStore.eligibleAction(
                            event
                        ) == "service_handoff"
                    ) "서비스센터 기록 전달 완료" else "서비스센터 예약 완료"
            }, compactPresentation = true))
        }
        if (kind == "diagnosis" && DiagnosticEvidenceReview.canOpen(event) && onOpenDiagnostic != null)
            section(ui.textButton("이전·현재 기록 비교") { if (allowed()) onOpenDiagnostic() }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, paddingTop, 0, paddingBottom)
            })
        if (map != null) section(map, if (kind == "diagnosis") visual else next)
        return true
    }
}
