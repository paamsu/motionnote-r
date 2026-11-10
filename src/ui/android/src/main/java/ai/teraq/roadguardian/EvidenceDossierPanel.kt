package ai.teraq.roadguardian

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject

/** 근거 목록 화면. 보험 처리 흐름은 넣지 않는다 */
object EvidenceDossierPanel {
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        event: JSONObject,
        detailTarget: LinearLayout? = null
    ) {
        val kind = event.optJSONObject("assessment")?.optString("kind")
        if (kind !in setOf("pothole", "crash")) return
        val dossier = event.optJSONObject("evidence_dossier")
        if (!event.optBoolean("evidence_valid") || dossier?.optString("state") == "invalid") {
            body.addView(ui.notice("원본 확인 실패 · 차량과 상황 정보 판단을 보류합니다."))
            return
        }
        if (dossier?.optString("schema") != "road-guardian.evidence-dossier.v1" || dossier.optString(
                "kind"
            ) != kind
        ) {
            body.addView(ui.notice("근거 목록을 받지 못했습니다. 사건을 다시 조회해 주세요."))
            return
        }
        val items = dossier.optJSONArray("items")
        val scale =
            if (body.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
                (body.resources.configuration.screenWidthDp / 1000f).coerceIn(1f, 1.65f) else 1f

        fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
        fun text(value: String, size: Float = 18f, bold: Boolean = false) =
            ui.text(value, size * scale, bold)

        val records = (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }
        if (records.isEmpty()) {
            body.addView(ui.notice("근거 항목이 비어 있습니다. 다시 조회해 주세요."))
            return
        }
        if (kind == "crash") IncidentVehiclesPanel.render(ui, body, event, scale, detailTarget)
        val attention = records.count { it.optString("state") != "available" }
        body.addView(text("추가 확인 $attention 항목", 22f, true))
        val wide =
            body.resources.configuration.screenWidthDp >= 760 && body.resources.configuration.fontScale < 1.3f
        val row = LinearLayout(body.context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val identity = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        val context = event.optJSONObject("data")?.optJSONObject("context")
        val synthetic =
            event.optString("source") == "synthetic" || context?.optString("source") == "scenario_fixture"
        val own = context?.optJSONObject("own_vehicle")
        fun supplied(key: String) =
            own?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: "미확인"
        if (kind != "crash") {
            val place = LinearLayout(body.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            place.addView(
                CockpitIcon(body.context, "도로", PleosPalette.action(ui.light)),
                LinearLayout.LayoutParams(dp(72), dp(92)).apply { marginEnd = dp(18) })
            val location = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
            location.addView(text("기록 위치", 15f))
            location.addView(
                text(
                    context?.optString("location_label")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: "위치 미확인", 24f, true
                )
            )
            place.addView(location, LinearLayout.LayoutParams(0, -2, 1f))
            identity.addView(place)
            val steps = listOf(
                CaseFlowGraphic.Step("원본", "해시 일치", 1),
                CaseFlowGraphic.Step("자료 확인", "$attention 항목 남음", 2),
                CaseFlowGraphic.Step("외부 제출", "미실시", 0)
            )
            if (body.resources.configuration.fontScale >= 1.3f) steps.forEach {
                identity.addView(
                    text("${it.label} · ${it.status}", 18f)
                )
            }
            else identity.addView(
                CaseFlowGraphic(body.context, steps, ui.light, scale),
                LinearLayout.LayoutParams(-1, dp(118))
            )
            identity.addView(text(supplied("plate"), 25f, true))
            identity.addView(text(supplied("model"), 20f))
            identity.addView(text(if (synthetic) "가상 차량 · 차량 식별 인식 미실행" else "차량 정보 원본 대조 필요", 16f))
        }
        val inventory = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        fun tile(item: JSONObject): LinearLayout {
            val state = item.optString("state")
            val available = state == "available"
            val color = Color.parseColor(
                if (available) {
                    if (ui.light) "#246BD1" else "#8EB8FF"
                } else {
                    if (ui.light) "#805000" else "#FFC777"
                }
            )
            val tile = LinearLayout(body.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val symbol = text(
                if (available) "✓" else if (state == "invalid") "!" else "·",
                20f,
                true
            ).apply {
                gravity = Gravity.CENTER
                setTextColor(color)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(1), color)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            tile.addView(
                symbol,
                LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
            tile.addView(
                text(item.optString("label"), 18f, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            val status = when (state) {
                "available" -> "자료 있음"
                "test" -> "시험 자료"
                "invalid" -> "확인 실패"
                "needs_review" -> "대조 필요"
                else -> "자료 없음"
            }
            tile.addView(
                text(status, 16f).apply { setTextColor(color) },
                LinearLayout.LayoutParams(-2, -2)
            )
            tile.contentDescription = "${item.optString("label")}, $status"
            return tile
        }

        var inventoryWidth = -1
        fun fillInventory(width: Int) {
            inventory.removeAllViews()
            val columns =
                kind == "crash" && width / body.resources.displayMetrics.density >= 760 && body.resources.configuration.fontScale < 1.3f
            if (columns) records.chunked(2).forEach { pair ->
                val line =
                    LinearLayout(body.context).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { index, item ->
                    line.addView(
                        tile(item),
                        LinearLayout.LayoutParams(0, -2, 1f)
                            .apply { if (index == 0) marginEnd = dp(36) })
                }
                if (pair.size == 1) line.addView(
                    View(body.context),
                    LinearLayout.LayoutParams(0, 0, 1f)
                )
                inventory.addView(
                    line,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            } else records.forEach {
                inventory.addView(
                    tile(it),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        if (kind == "crash") inventory.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != inventoryWidth) {
                inventoryWidth = width
                inventory.post {
                    if (inventory.isAttachedToWindow && inventory.width == width) fillInventory(
                        width
                    )
                }
            }
        }
        fillInventory((body.resources.configuration.screenWidthDp * body.resources.displayMetrics.density).toInt())
        if (kind != "crash") row.addView(
            identity,
            if (wide) LinearLayout.LayoutParams(0, -2, 1f)
                .apply { marginEnd = dp(40) } else LinearLayout.LayoutParams(-1, -2))
        row.addView(
            inventory,
            if (wide) LinearLayout.LayoutParams(0, -2, 1.1f) else LinearLayout.LayoutParams(-1, -2)
        )
        body.addView(row)
        body.addView(text("외부 제출 미실시 · 접촉·책임 미확정", 17f, true))
        val details = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        records.forEach {
            details.addView(text(it.optString("label"), 19f, true))
            details.addView(text(it.optString("detail"), 17f))
        }
        if (kind == "pothole") RoadEvidencePanel.render(ui, details, event)
        details.addView(text("자료 유무를 정리한 목록입니다. 기관 제출 요건이나 증거 효력을 판단하지 않습니다.", 16f))
        if (detailTarget != null) detailTarget.addView(details) else ui.disclosure(
            body,
            "자료별 확인 내용",
            details
        )
    }
}
