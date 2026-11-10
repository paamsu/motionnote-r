package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONObject

/** 합성 묶음 출처를 보여 준다. 정차·포그라운드 정책은 화면을 띄운 쪽이 맡는다 */
object RoadGroupPanel {
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        page: JSONObject,
        open: (String) -> Unit,
        next: (Int) -> Unit,
        back: () -> Unit,
        client: BackendClient? = null,
        allowed: () -> Boolean = { false }
    ) {
        ui.evidenceHeading(body, "묶음의 원본 관측")
        val events = page.optJSONArray("events")
        val total = page.optInt("total", -1)
        val offset = page.optInt("offset", -1)
        val ids = if (events == null) emptyList() else (0 until events.length()).map {
            events.optJSONObject(it)?.optString("id").orEmpty()
        }
        val valid =
            page.optString("schema") == "road-guardian.demo-road-group.v1" && page.optString("source") == "synthetic" &&
                    page.optString("group_id")
                        .matches(Regex("[0-9a-f]{16}")) && page.opt("external_submission") == false && page.opt(
                "repair_verified"
            ) == false &&
                    page.optString("membership_sha256").matches(Regex("[0-9a-f]{64}")) &&
                    total in 1..100000 && offset in 0..total && events != null && events.length() <= 10 && offset + events.length() <= total &&
                    ids.distinct().size == ids.size && ids.all {
                runCatching {
                    AccountEventRoute.checked(
                        it
                    ) == it
                }.getOrDefault(false)
            } &&
                    (0 until events.length()).all {
                        val e = events.getJSONObject(it)
                        e.optString("source") == "synthetic" && e.opt("evidence_valid") == true && e.optString(
                            "status"
                        ) !in listOf(
                            "pending_confirmation",
                            "dismissed"
                        ) && e.optJSONObject("assessment")?.optString("kind") == "pothole"
                    } &&
                    (if (offset + (events?.length()
                            ?: 0) < total
                    ) page.opt("next_offset") == offset + (events?.length()
                        ?: 0) && (events?.length() ?: 0) > 0 else page.isNull("next_offset"))
        if (!valid) {
            body.addView(ui.notice("원본 목록 확인 실패 · 이전 자료를 표시하지 않습니다"))
            body.addView(ui.button("집계로 돌아가기", action = back))
            return
        }
        body.addView(
            ui.text(
                if (events!!.length() == 0) "표시할 원본 없음" else "원본 ${offset + 1}–${offset + events.length()} / ${total}건",
                20f,
                true
            )
        )
        body.addView(ui.button("집계로 돌아가기", action = back))
        val wide = body.resources.configuration.screenWidthDp >= 1100
        var gallery: LinearLayout? = null
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val number = offset + i + 1
            if (wide && i % 3 == 0) {
                gallery = LinearLayout(body.context).apply { orientation = LinearLayout.HORIZONTAL }
                body.addView(
                    gallery,
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
            }
            val row = LinearLayout(body.context).apply {
                orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val width =
                if (wide) -1 else ui.dp(if (body.resources.configuration.screenWidthDp < 600) 140 else 220)
            row.addView(
                EventEvidencePhotoPanel(
                    body.context,
                    ui,
                    event,
                    client,
                    allowed,
                    comparisonLabel = "관측 $number",
                    allowStaticRoadReference = true,
                    fitPhotoAspect = true
                ),
                LinearLayout.LayoutParams(width, -2)
                    .apply { marginEnd = if (wide) 0 else ui.dp(24) })
            val actions = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
            if (!wide) actions.addView(ui.text(EventBriefState.from(event).title, 24f, true))
            actions.addView(ui.button("관측 $number 보기") { open(event.getString("id")) })
            row.addView(
                actions,
                if (wide) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(
                    0,
                    -2,
                    1f
                )
            )
            if (wide) gallery!!.addView(
                row,
                LinearLayout.LayoutParams(0, -2, 1f)
                    .apply { if (i % 3 != 0) marginStart = ui.dp(16) })
            else body.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
        }
        val source = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        source.addView(
            ui.text(
                "합성 관측의 원본 목록입니다. 실제 기관 접수나 도로 보수를 확인한 결과는 아닙니다. 사건별 원본 자료와 출처는 관측 화면에서 확인할 수 있습니다.",
                18f
            )
        )
        ui.disclosure(body, "자료 출처", source)
        if (!page.isNull("next_offset")) body.addView(
            ui.button(
                "다음 원본 보기",
                true
            ) { next(page.getInt("next_offset")) })
    }
}
