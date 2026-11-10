package ai.teraq.roadguardian

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 정차 중에만 그리는 상세 화면. 차량 제한과 인증 데이터는 호출한 쪽이 맡는다 */
object VehicleEventDetail {
    private fun value(obj: JSONObject?, key: String) =
        obj?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: "미확인"

    fun render(
        activity: Activity,
        body: LinearLayout,
        event: JSONObject,
        light: Boolean,
        actions: LinearLayout? = null,
        client: BackendClient? = null,
        allowed: () -> Boolean = { false },
        onOpenDiagnostic: (() -> Unit)? = null,
        onEventChanged: ((JSONObject) -> Unit)? = null
    ) {
        run {
            body.removeAllViews()
            val ui = PhoneAccountUi(activity, light)
            EventVisualBrief.render(
                ui,
                body,
                event,
                client,
                allowed,
                onOpenDiagnostic,
                onEventChanged
            )
            val detail = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val comparison = EventBriefState.roadComparisonLabel(event)
            if (comparison != null) {
                val comparisonBody =
                    LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                RoadFollowupPanel.render(
                    ui,
                    comparisonBody,
                    event,
                    client = client,
                    allowed = allowed
                )
                ui.disclosure(
                    body,
                    "관측 비교",
                    comparisonBody,
                    collapsedLabel = "전후 비교 보기",
                    expandedLabel = "비교 접기",
                    revealOnExpand = true
                )
            }
            if (actions != null) detail.addView(actions)
            val original = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            renderDetails(
                activity,
                original,
                event,
                light,
                includeRoadComparison = comparison == null
            )
            ui.disclosure(detail, "원본 데이터", original)
            ui.disclosure(body, "상세 기록", detail, revealTarget = actions?.getChildAt(0))
        }
    }

    private fun renderDetails(
        activity: Activity,
        body: LinearLayout,
        event: JSONObject,
        light: Boolean,
        includeRoadComparison: Boolean = true
    ) {
        body.removeAllViews()
        val ui = PhoneAccountUi(activity, light)
        if (OwnerCasePanel.render(ui, body, event)) return
        if (TrafficCasePanel.render(ui, body, event)) return
        val density = activity.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val ink = PleosPalette.ink(light)
        val muted = PleosPalette.muted(light)
        val accent = Color.parseColor(if (light) "#805000" else "#FFC777")
        val line = Color.parseColor(if (light) "#DEDEDE" else "#3B4A58")
        fun text(
            parent: LinearLayout,
            value: String,
            size: Float = 21f,
            color: Int = ink,
            bold: Boolean = false
        ) {
            parent.addView(TextView(activity).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                typeface = PleosTypography.face(bold)
                setLineSpacing(dp(3).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            })
        }

        fun rule(parent: LinearLayout) {
            parent.addView(
                View(activity).apply { setBackgroundColor(line) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(20)
                })
        }

        fun field(parent: LinearLayout, label: String, value: String, size: Float = 23f) {
            text(parent, label, 17f, muted)
            text(parent, value, size, ink, true)
        }

        val context = event.optJSONObject("data")?.optJSONObject("context")
        val synthetic =
            event.optString("source") == "synthetic" || context?.optString("source") == "scenario_fixture"
        val kind = event.optJSONObject("assessment")?.optString("kind")
        ui.evidenceHeading(
            body, when (kind) {
                "crash" -> "충돌 근거 정리"
                "parking" -> "주차 충격 근거"
                "pothole" -> "도로 근거 정리"
                else -> value(event.optJSONObject("assessment"), "title")
            },
            if (synthetic) "합성 시연 · 실제 차량 측정 자료 아님" else "서버 수신 기록 · 원본 확인 필요"
        )
        rule(body)
        if (VideoLaneAnalysis.present(event)) {
            body.addView(ui.text("영상 차선 이동 판독", 20f, true))
            body.addView(ui.text(VideoLaneAnalysis.detail(event), 24f, true))
            body.addView(ui.text("합성 영상 6시점 · 속도·의도는 판단하지 않음", 18f))
        }
        if (VideoCollisionAnalysis.present(event)) {
            body.addView(ui.text("영상 접촉 판독", 20f, true))
            body.addView(ui.text(VideoCollisionAnalysis.detail(event), 24f, true))
            body.addView(ui.text("합성 영상 6시점 · 실제 충돌 확인 아님", 18f))
            body.addView(ui.text("접촉·시간 순서 오판 가능 · 센서 판단과 별도", 18f))
        }
        if (VideoRoadAnalysis.present(event)) {
            body.addView(ui.text("영상 노면 판독", 20f, true))
            body.addView(ui.text(VideoRoadAnalysis.detail(event), 24f, true))
            body.addView(ui.text("합성 영상 6시점 · 형상 확인 필요", 18f))
            body.addView(ui.text("충격 신호와 별도 근거입니다", 18f))
        }
        if (VideoVisibilityAnalysis.present(event)) {
            body.addView(ui.notice("합성 영상 판독 · 센서 판단과 별도"))
            body.addView(ui.text(VideoVisibilityAnalysis.detail(event), 24f, true))
            body.addView(ui.text("조명·부분 와이퍼·시간 순서 오판 가능 · 실제 시야 확인 아님", 20f))
            body.addView(ui.text("영상과 센서는 독립 작성 자료 · 시간 동기화 미검증", 18f))
        }
        if (ScenePhotoAnalysis.present(event)) {
            body.addView(ui.notice(ScenePhotoAnalysis.detail(event)))
            body.addView(ui.text("합성 사진의 사전 분석 · 센서 입력과 별도 보관", 20f))
        }
        event.optJSONObject("assessment")?.optJSONObject("collision_assessment")?.let { collision ->
            if (collision.optString("status") == "authored_collision_candidate") {
                body.addView(ui.notice("합성 충돌 관측 · 영상 인식 결과 아님"))
                body.addView(ui.text("에어백 미전개 입력에서도 충돌 후보 기록을 보관합니다.", 22f))
            }
        }
        event.optJSONObject("assessment")?.optJSONObject("road_assessment")?.let { road ->
            if (kind == "pothole" || road.optString("reason") in setOf(
                    "surface_not_depression",
                    "road_surface_invalid"
                )
            ) {
                val surface = when (road.optString("surface")) {
                    "depression" -> "패임"
                    "raised" -> "돌출"
                    else -> "미확인"
                }
                body.addView(ui.notice("합성 센서 입력 노면: $surface · 사진 판독과 별도"))
            }
        }
        event.optJSONObject("assessment")?.optJSONObject("weather_timeline_assessment")
            ?.let { timeline ->
                if (timeline.optString("source") == "authored_weather_timeline_evaluation") {
                    body.addView(ui.notice("합성 신호의 시간 변화 · 영상 인식 결과 아님"))
                    body.addView(ui.text("지속·워셔·회복 조건을 계산한 시험 기록이며 실제 시야를 보장하지 않습니다.", 20f))
                }
            }
        event.optJSONObject("assessment")?.optJSONObject("weather_assessment")?.let { weather ->
            val label = when (weather.optString("condition")) {
                "rain" -> "강우"
                "car_wash" -> "세차"
                "tunnel" -> "터널"
                "manual_wiper" -> "수동 와이퍼"
                else -> "미확인"
            }
            body.addView(ui.notice("합성 맥락: $label · 영상 인식 결과 아님"))
            if (weather.optString("status") == "withheld") body.addView(
                ui.text(
                    "신호만으로 강우를 확정할 수 없어 판정을 보류했습니다.",
                    22f
                )
            )
        }
        if (kind in setOf("pothole", "parking", "crash") && !event.optBoolean("evidence_valid")) {
            body.addView(ui.notice("원본 확인 실패 · 차량과 상황 정보 판단을 보류합니다."))
            return
        }
        if (kind == "parking") {
            event.optJSONObject("data")?.optJSONArray("samples")?.let { samples ->
                body.addView(ui.text("충격 신호 기록", 22f, true))
                body.addView(
                    ImpactTimelineView(activity, samples),
                    LinearLayout.LayoutParams(-1, ui.dp(80))
                )
            }
        }
        val graphic = event.optJSONObject("assessment")?.optString("kind") in setOf(
            "crash",
            "diagnosis",
            "emergency",
            "manual_help",
            "pothole"
        )
        val details = if (graphic) LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        } else body
        if (graphic) {
            EvidenceDossierPanel.render(ui, body, event, details)
            DiagnosticCasePanel.render(ui, body, event)
            EmergencyCasePanel.render(ui, body, event)
            if (includeRoadComparison) RoadFollowupPanel.render(
                ui,
                body,
                event,
                compactWaiting = true,
                detailTarget = details
            )
        }
        val split =
            activity.resources.configuration.screenWidthDp >= 760 && activity.resources.configuration.fontScale < 1.5f
        val columns = LinearLayout(activity).apply {
            orientation = if (split) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val incident = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val vehicles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (split) {
            columns.addView(
                incident,
                LinearLayout.LayoutParams(0, -2, 0.95f).apply { marginEnd = dp(28) })
            columns.addView(
                View(activity).apply { setBackgroundColor(line) },
                LinearLayout.LayoutParams(dp(1), -1)
            )
            columns.addView(
                vehicles,
                LinearLayout.LayoutParams(0, -2, 1.25f).apply { marginStart = dp(28) })
        } else {
            columns.addView(incident)
            columns.addView(vehicles)
        }
        details.addView(columns)
        val timestamp = event.optDouble("created", Double.NaN)
        val date = if (timestamp.isFinite() && timestamp > 0) SimpleDateFormat(
            "M월 d일  HH:mm",
            Locale.KOREAN
        ).format(Date((timestamp * 1000).toLong())) else "기록 시각 미확인"
        field(incident, "서버 저장 시각", date)
        field(incident, "장소", value(context, "location_label"))
        if (kind in setOf(
                "crash",
                "parking",
                "pothole"
            ) || context?.optJSONObject("impact") != null
        )
            field(incident, "충격 위치", value(context?.optJSONObject("impact"), "area"))
        val own = context?.optJSONObject("own_vehicle")
        field(incident, "내 차량", value(own, "plate"))
        text(incident, "${value(own, "model")} · ${value(own, "color")}", 19f, muted)
        if (!split) rule(incident)
        text(vehicles, "주변 차량", 19f, muted)
        val actors = context?.optJSONArray("actors")
        if (actors == null || actors.length() == 0) text(vehicles, "확인된 주변 차량 정보가 없습니다.", 23f)
        else for (i in 0 until actors.length()) {
            val actor = actors.optJSONObject(i) ?: continue
            if (i > 0) rule(vehicles)
            text(vehicles, value(actor, "plate"), 30f, ink, true)
            text(
                vehicles,
                if (kind == "parking") "${value(actor, "brand")} ${value(actor, "model")} · ${
                    value(
                        actor,
                        "color"
                    )
                }" else "${value(actor, "model")} · ${value(actor, "color")}",
                23f
            )
            text(vehicles, value(actor, "relationship"), 19f, muted)
            if (synthetic || actor.optString("recognition_status") == "not_run") text(
                vehicles,
                "번호판·차종 영상 인식 미실행",
                17f,
                accent
            )
        }
        rule(details)
        val facts = context?.optJSONArray("facts")
        if (facts != null && facts.length() > 0) {
            text(details, "기록된 상황", 23f, ink, true)
            for (i in 0 until facts.length()) {
                val fact = facts.optJSONObject(i) ?: continue
                text(details, "${value(fact, "label")}   ${value(fact, "value")}", 20f)
            }
            rule(details)
        }
        text(details, "확인이 필요한 내용", 23f, ink, true)
        val unknowns = context?.optJSONArray("unknowns")
        if (unknowns == null || unknowns.length() == 0) text(
            details,
            "접촉 여부와 책임은 이 기록만으로 확정할 수 없습니다.",
            19f,
            muted
        )
        else for (i in 0 until unknowns.length()) text(details, unknowns.optString(i), 19f, muted)
        if (graphic) ui.disclosure(body, "자료와 확인 사항", details)
    }
}
