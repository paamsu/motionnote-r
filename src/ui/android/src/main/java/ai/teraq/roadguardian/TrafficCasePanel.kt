package ai.teraq.roadguardian

import android.content.pm.PackageManager
import android.widget.LinearLayout
import org.json.JSONObject

/** 합성 근거 목록. 승인된 사건 분류에는 넣지 않는다 */
object TrafficCasePanel {
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        event: JSONObject,
        client: BackendClient? = null,
        allowed: () -> Boolean = { false }
    ): Boolean {
        val kind = event.optJSONObject("assessment")?.optString("kind")
        if (kind !in setOf("violation", "wanted", "reckless")) return false
        val car = body.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val scale = if (car) (body.resources.configuration.screenWidthDp / 1100f).coerceIn(
            1f,
            1.65f
        ) else 1f

        fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
        fun text(value: String, size: Float = 18f, bold: Boolean = false) =
            ui.text(value, size * scale, bold)

        val plateEvidence = PlateAnalysis.present(event)
        val trafficAuto = TrafficZoneAutoNotice.authored(event)
        if (trafficAuto) ui.evidenceHeading(
            body,
            TrafficZoneAutoNotice.summary(event).title,
            TrafficZoneAutoNotice.summary(event).next
        )
        else if (!plateEvidence) ui.evidenceHeading(
            body, if (PlateAnalysis.present(event)) "사진 판독 근거" else when (kind) {
                "violation" -> "주변 차량 근거"
                "reckless" -> "주행 변화 근거"
                else -> "목록 확인 근거"
            }, "합성 시연 · 위반·수배 판정 아님"
        )
        val review = event.optJSONObject("traffic_review")
        if (!event.optBoolean("evidence_valid") || event.optString("source") != "synthetic" ||
            review?.optString("schema") != "road-guardian.traffic-review.v1" || review.optString("kind") != kind ||
            review.optString("stage") != "synthetic_review" || review.optString("source") != "synthetic" ||
            !listOf(
                "verdict_verified",
                "recognition_performed",
                "external_submission"
            ).all { review.has(it) && review.opt(it) == false }
        ) {
            body.addView(ui.notice("원본 또는 시연 출처 확인 실패 · 대상 차량 표시를 보류합니다."))
            return true
        }
        val analysisDetails =
            LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        if (plateEvidence) {
            analysisDetails.addView(text("합성 사진 사전 분석 · 차량 신원 확인 아님", 18f))
        }
        if (PlateVideoAnalysis.present(event)) {
            analysisDetails.addView(text("영상 시점별 판독", 24f, true))
            analysisDetails.addView(text(PlateVideoAnalysis.detail(event), 20f))
            analysisDetails.addView(text("합성 영상 일부 시점 · 연속 추적 아님", 18f))
        }
        val panel = body
        val large = body.resources.configuration.fontScale >= 1.3f
        val decodedStationary = review.optJSONObject("stationary_motion")
            ?.optString("source") == "synthetic_video_tracking"
        val decodedMotion = decodedStationary || review.optJSONObject("relative_motion")
            ?.optString("source") == "synthetic_video_tracking"
        val sameEventPhoto = client != null && (trafficAuto || event.optJSONObject("video")
            ?.optString("input_kind") in
                setOf("authored_stationary_scene", "authored_moving_plate_scene"))
        val compactDetails = plateEvidence || (kind == "violation" && sameEventPhoto)
        val identity = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        if (sameEventPhoto) identity.addView(
            EventEvidencePhotoPanel(
                body.context, ui, event, client, allowed,
                comparisonLabel = "사건 사진", fitPhotoAspect = true
            )
        )
        val vehicle = LinearLayout(body.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        if (!large && !sameEventPhoto) vehicle.addView(
            VehicleIdentityOutline(
                body.context,
                null,
                ui.light,
                true
            ),
            LinearLayout.LayoutParams(dp(if (car) 200 else 112), dp(140))
                .apply { marginEnd = dp(20) })
        val labels = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        val subject = review.optJSONObject("subject")
        fun value(key: String) =
            subject?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: "미확인"
        if (plateEvidence) {
            labels.addView(text("사진 판독 문자", 16f))
            val candidates = PlateAnalysis.candidateTexts(event)
            if (candidates.isEmpty()) labels.addView(text("판독 보류", 30f, true))
            else candidates.forEach { labels.addView(text(it, 30f, true)) }
        } else {
            labels.addView(text("기록된 대상", 16f))
            labels.addView(text(if (decodedMotion) "합성 표식" else value("plate"), 30f, true))
            labels.addView(
                text(
                    if (decodedMotion) "파랑 · 영상 시험" else "${value("model")} · ${value("color")}",
                    19f
                )
            )
        }
        vehicle.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        if (compactDetails) analysisDetails.addView(vehicle) else identity.addView(vehicle)
        if (!sameEventPhoto) identity.addView(
            text(
                if (PlateAnalysis.present(event)) "차종 공통 그림 · 대상 정보는 시연 설정" else "차종 공통 그림 · 번호·차종 인식 미실행",
                15f
            )
        )
        val observation = LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        observation.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(ui.cardColor)
            cornerRadius = dp(10).toFloat()
        }
        if (plateEvidence) {
            observation.addView(text(PlateAnalysis.detail(event), 24f, true))
            observation.addView(text("기록 확보 완료", 18f))
        } else {
            observation.addView(
                text(
                    when (kind) {
                        "violation" -> if (decodedStationary) "연속 정지 · 합성 영상 계산" else "가상 정지 시간"
                        "reckless" -> if (review.optJSONObject("relative_motion")
                                ?.optString("source") == "synthetic_video_tracking"
                        ) "차선 이동 · 합성 영상" else "주변 차량 변화 · 가상"

                        else -> "가상 목록 비교"
                    }, 17f
                )
            )
            val duration = review.optDouble("stationary_seconds", Double.NaN)
            observation.addView(
                text(
                if (kind == "violation") if (duration.isFinite() && duration >= 0) "${duration.toInt()}초" else "미확인"
            else if (kind == "reckless") review.optInt("abrupt_manoeuvres", -1)
                .let { if (it >= 0) "${it}회" else "미확인" }
            else if (review.optBoolean("fixture_flag")) "시험 번호 일치" else "미확인", 32f, true))
            observation.addView(
                text(
                    when (kind) {
                        "violation" -> "구역·시간대·예외는 확인 전"
                        "reckless" -> "회피 운전·책임은 확인 전"
                        else -> "실제 수배 목록 조회 없음"
                    }, 16f
                )
            )
        }
        val row = LinearLayout(body.context)
        row.addView(identity)
        row.addView(observation)
        panel.addView(row)
        var wideBefore: Boolean? = null
        fun arrange(width: Int) {
            val widthDp = width / body.resources.displayMetrics.density
            val wide = widthDp >= 760 && (!large || (sameEventPhoto && widthDp >= 1100))
            if (wideBefore == wide) return
            wideBefore = wide
            row.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            identity.layoutParams = if (wide) LinearLayout.LayoutParams(0, -2, 1.4f)
                .apply { marginEnd = dp(24) } else LinearLayout.LayoutParams(-1, -2)
            observation.layoutParams =
                if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(
                    -1,
                    -2
                )
        }
        arrange(body.width)
        row.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> if (r > l) arrange(r - l) }
        if (kind == "reckless") {
            val motion = review.optJSONObject("relative_motion")
            val decoded = motion?.optString("source") == "synthetic_video_tracking"
            if (motion != null) {
                panel.addView(
                    text(
                        if (decoded) "상대 횡방향 위치 · 합성 영상 계산" else "상대 횡방향 위치 · 가상 좌표",
                        18f,
                        true
                    )
                )
                if (large) panel.addView(
                    text(
                        if (decoded) "${
                            motion.optJSONArray("points")?.length() ?: 0
                        }개 영상 좌표 · 색 표식 시험" else "본인 차량과 주변 차량을 분리한 ${
                            motion.optJSONArray("points")?.length() ?: 0
                        }개 시연 좌표 · 영상 인식·촬영 차량 보정 미실행", 18f
                    )
                )
                else panel.addView(
                    TrafficMotionGraphic(body.context, motion, ui.light),
                    LinearLayout.LayoutParams(-1, dp(if (car) 190 else 120))
                )
            } else panel.addView(text("이동 좌표 미확인 · 궤적 표시 보류", 18f, true))
            panel.addView(text("합성 자료 · 실제 기관 전송 없음", 16f, true))
            ui.disclosure(panel, "자료와 확인 사항", LinearLayout(body.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    text(
                        if (decoded) "합성 영상의 색 표식 두 개를 추적해 상대 위치를 계산했습니다. 실제 차량·번호판 인식과 위험 의도 판정은 실행하지 않았습니다." else "본인 차량의 센서 신호와 주변 차량의 작성 좌표는 별개입니다. 영상 인식·촬영 차량 움직임 보정·위험 의도 판정은 실행하지 않았습니다.",
                        17f
                    )
                )
            })
            return true
        }
        val receipt = review.optString("mock_receipt")
            .takeIf { it.matches(Regex("MOCK-[0-9a-f]{8}")) && review.optString("mock_delivery") == "acknowledged" }
        val steps = listOf(
            CaseFlowGraphic.Step("원본 보관", "저장 확인", 1),
            CaseFlowGraphic.Step(
                if (kind == "wanted") "목록 자료" else "시연 확인",
                if (review.optBoolean("dismissed")) "제외됨" else if (kind == "wanted") "시험용" else if (review.optBoolean(
                        "review_confirmed"
                    )
                ) "확인 기록" else "대기",
                if (review.optBoolean("dismissed")) 3 else if (review.optBoolean("review_confirmed")) 1 else 0
            ),
            CaseFlowGraphic.Step(
                if (receipt != null) "모의 수신" else "기관 연동",
                if (receipt != null) "시험 접수" else "미연결",
                if (receipt != null) 1 else 0
            )
        )
        val supporting = if (compactDetails) analysisDetails else panel
        if (large) steps.forEach { supporting.addView(text("${it.label} · ${it.status}", 19f)) }
        else supporting.addView(
            CaseFlowGraphic(body.context, steps, ui.light, scale),
            LinearLayout.LayoutParams(-1, dp(118))
        )
        supporting.addView(text("합성 자료 · 실제 기관 전송 없음", 16f, true))
        if (receipt != null) supporting.addView(text("모의 접수 확인 · $receipt", 18f, true))
        else if (review.optString("mock_delivery") == "retry_pending") supporting.addView(
            text(
                "모의 전달 실패 · 다시 시도할 수 있습니다",
                18f,
                true
            )
        )
        val details = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        details.addView(text("시험 플래그는 영상 분석·공인 목록·위반 성립을 확인한 결과가 아닙니다.", 17f))
        if (WantedAutoNotice.authored(event)) details.addView(
            text(
                "로컬 합성 수배 명단 DEMO-001과 작성 번호를 대조한 자동 접수 시연입니다. 실제 수배 조회·번호판 인식·112 전송은 하지 않았습니다.",
                17f
            )
        )
        if (trafficAuto) details.addView(
            text(
                "작성된 구역·대상 사진으로 구성한 자동 접수 시연입니다. 구역 인식·위법 판정·실제 안전신문고 전송은 하지 않았습니다.",
                17f
            )
        )
        val unknowns =
            event.optJSONObject("data")?.optJSONObject("context")?.optJSONArray("unknowns")
        if (unknowns != null) for (i in 0 until unknowns.length()) details.addView(
            text(
                unknowns.optString(
                    i
                ), 16f
            )
        )
        if (compactDetails) {
            analysisDetails.addView(details)
            ui.disclosure(panel, if (plateEvidence) "판독 근거" else "자료와 확인 사항", analysisDetails)
        } else {
            if (analysisDetails.childCount > 0) panel.addView(analysisDetails)
            ui.disclosure(panel, "자료와 확인 사항", details)
        }
        return true
    }
}
