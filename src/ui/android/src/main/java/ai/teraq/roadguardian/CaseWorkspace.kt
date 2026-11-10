package ai.teraq.roadguardian

import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 현재 페이지의 후보 처리 흐름. 알 수 없거나 예전 서비스 종류는 화면에 넣지 않는다 */
object CaseWorkspace {
    private val titles = mapOf(
        "road" to "도로 근거 검토",
        "maintenance" to "정비 전후 비교",
        "incident" to "충돌 근거 정리",
        "help" to "도움 요청"
    )

    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        events: JSONArray,
        selectedCategory: String? = null,
        onCategory: ((String) -> Unit)? = null,
        selection: VehicleOverviewSelection = VehicleOverviewSelection(),
        open: (String) -> Unit
    ) {
        if (body.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            VehicleCaseWorkspace.render(
                ui,
                body,
                events,
                selectedCategory,
                onCategory,
                selection = selection,
                open = open
            )
            return
        }
        val scale =
            if (body.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
                (body.resources.configuration.screenWidthDp / 1000f).coerceIn(1f, 1.65f) else 1f

        fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
        fun text(value: String, size: Float = 18f, bold: Boolean = false) =
            ui.text(value, size * scale, bold)

        val records = (0 until events.length()).mapNotNull { events.optJSONObject(it) }
            .filter { EvidenceScope.category(it) != null }
        val section = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(section)
        fun show(selected: String) {
            section.removeAllViews()
            val tabs = LinearLayout(body.context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((key, label) in linkedMapOf(
                "all" to "전체",
                "road" to "도로",
                "maintenance" to "정비",
                "incident" to "충돌"
            ).apply {
                if (records.any { EvidenceScope.category(it) == "help" }) put(
                    "help",
                    "도움 요청"
                )
            }) {
                val count = records.count { key == "all" || EvidenceScope.category(it) == key }
                tabs.addView(
                    ui.button(
                        if (onCategory == null) "$label $count" else label,
                        selected == key
                    ) { if (onCategory == null) show(key) else onCategory(key) }.apply {
                        textSize = 18f * scale
                        isSelected = selected == key
                        contentDescription =
                            if (onCategory == null) "$label, 현재 목록 $count 건${if (selected == key) ", 선택됨" else ""}" else "$label 분류${if (selected == key) ", 선택됨" else ""}"
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            }
            section.addView(tabs)
            section.addView(
                text(
                    if (onCategory == null) "현재 받은 목록의 근거 정리 후보 · 전체 차량 이력 건수는 아닙니다" else "선택한 분류에서 받은 목록 · 이전 기록은 아래에서 계속 확인하세요",
                    15f
                )
            )
            val visible =
                records.filter { selected == "all" || EvidenceScope.category(it) == selected }
            if (visible.isEmpty()) {
                section.addView(ui.notice("이 목록에는 해당 근거 정리 기록이 없습니다. 이전 기록이 있으면 다음 목록에서 확인하세요."))
                return
            }
            val columns =
                if (body.resources.configuration.screenWidthDp >= 1000 && body.resources.configuration.fontScale < 1.3f) 2 else 1
            for (group in visible.chunked(columns)) {
                val row = LinearLayout(body.context).apply { orientation = LinearLayout.HORIZONTAL }
                for (event in group) {
                    val category = EvidenceScope.category(event) ?: continue
                    val summary = event.optJSONObject("case_overview")
                        ?.takeIf { it.optString("category") == category }
                    val title = titles[category] ?: "근거 정리"
                    val label = EvidenceScope.helpStatus(event) ?: summary?.optString("label")
                        ?.takeIf { it.isNotBlank() } ?: "상태는 기록을 열어 확인"
                    val card = LinearLayout(body.context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(18), dp(20), dp(16))
                        background = GradientDrawable().apply {
                            setColor(ui.cardColor)
                            cornerRadius = dp(10).toFloat()
                            setStroke(
                                ui.dp(1),
                                android.graphics.Color.parseColor(if (ui.light) "#DCE4E9" else "#343B43")
                            )
                        }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { open(event.optString("id")) }
                    }
                    card.addView(text(title, 23f, true))
                    card.addView(text(label, 18f, true))
                    val stamp = event.optDouble("created", Double.NaN)
                    val date = if (stamp.isFinite() && stamp > 0) SimpleDateFormat(
                        "M월 d일 HH:mm",
                        Locale.KOREAN
                    ).format(Date((stamp * 1000).toLong())) else "저장 시각 미확인"
                    card.addView(text("서버 저장 $date", 14f))
                    val own = summary?.optJSONObject("vehicle")
                    val identity = listOf("plate", "model").mapNotNull { key ->
                        own?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
                    }
                    card.addView(
                        text(
                            identity.joinToString(" · ").ifBlank { "차량 정보는 상세에서 확인" },
                            16f
                        )
                    )
                    val steps = summary?.optJSONArray("steps")
                    val flow = if (steps != null) (0 until steps.length()).mapNotNull { index ->
                        steps.optJSONObject(index)?.let {
                            CaseFlowGraphic.Step(
                                it.optString("label"),
                                it.optString("status"),
                                it.optInt("state").coerceIn(0, 3)
                            )
                        }
                    } else emptyList()
                    if (flow.size == 3) {
                        if (body.resources.configuration.fontScale >= 1.3f) flow.forEach {
                            card.addView(
                                text("${it.label} · ${it.status}", 17f)
                            )
                        }
                        else card.addView(
                            CaseFlowGraphic(body.context, flow, ui.light, scale),
                            LinearLayout.LayoutParams(-1, dp(118))
                        )
                    } else if (category == "help") card.addView(
                        DriverEventGraphic(
                            body.context,
                            requireNotNull(EvidenceScope.helpGraphic(event)),
                            ui.light
                        ), LinearLayout.LayoutParams(-1, dp(118))
                    )
                    else card.addView(text("검토 상태를 받지 못했습니다. 상세에서 원본을 확인하세요.", 16f))
                    val source = when (event.optString("source")) {
                        "synthetic" -> "합성 시험 자료"
                        "owner_reported" -> "본인 작성 · 사실 확인 전"
                        else -> "서버 수신 기록"
                    }
                    card.addView(text("$source · ${event.optString("id").take(8)}", 14f))
                    card.addView(text("근거와 남은 확인 사항 보기", 17f, true))
                    card.contentDescription =
                        "$title, $label, 서버 저장 $date, ${identity.joinToString(", ")}, ${
                            flow.joinToString(". ") { "${it.label}: ${it.status}" }
                        }, $source, 상세 보기"
                    for (index in 0 until card.childCount) card.getChildAt(index).importantForAccessibility =
                        android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginEnd = dp(12)
                        bottomMargin = dp(18)
                    })
                }
                if (group.size < columns) row.addView(
                    android.view.View(body.context),
                    LinearLayout.LayoutParams(0, 1, 1f)
                )
                section.addView(row)
            }
        }
        show(selectedCategory ?: "all")
    }
}
