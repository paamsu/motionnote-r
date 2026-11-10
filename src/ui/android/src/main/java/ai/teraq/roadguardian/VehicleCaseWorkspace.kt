package ai.teraq.roadguardian

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 차량 화면 구성: 최신 기록, 관련 기록 순. 우선순위를 따로 만들지 않는다 */
object VehicleCaseWorkspace {
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        events: JSONArray,
        selectedCategory: String?,
        onCategory: ((String) -> Unit)?,
        showCategories: Boolean = true,
        selection: VehicleOverviewSelection = VehicleOverviewSelection(),
        open: (String) -> Unit
    ) {
        if (showCategories) {
            VehicleOverviewPanel.render(
                ui,
                body,
                events,
                selectedCategory,
                onCategory,
                selection,
                open
            )
            return
        }
        val records = (0 until events.length()).mapNotNull { events.optJSONObject(it) }
            .filter { EvidenceScope.category(it) != null }
        val root = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(root, LinearLayout.LayoutParams(-1, -2))
        var selected = selectedCategory ?: "all"
        var expanded = false
        var measuredWidth = 0
        fun rebuild(width: Int) {
            root.removeAllViews()
            val widthDp = width / body.resources.displayMetrics.density
            val scale = (widthDp / 1100f).coerceIn(1f, 1.65f)
            val wide = widthDp >= 920 && body.resources.configuration.fontScale < 1.3f
            fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
            fun text(value: String, size: Float = 18f, bold: Boolean = false) =
                ui.text(value, size * scale, bold).apply {
                    typeface = PleosTypography.face(bold)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                }

            val accent = PleosPalette.action(ui.light)
            val line = PleosPalette.line(ui.light)
            fun surface(focus: Boolean = false) = GradientDrawable().apply {
                setColor(PleosPalette.surface(ui.light))
                cornerRadius = dp(16).toFloat()
                if (focus) setStroke(dp(2), accent)
            }

            fun interactive(view: LinearLayout, description: String, action: () -> Unit) {
                view.isClickable = true
                view.isFocusable = true
                view.contentDescription = description
                view.background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), surface(true))
                    addState(intArrayOf(android.R.attr.state_pressed), surface(true))
                    addState(intArrayOf(), surface())
                }
                view.setOnClickListener { action() }
                fun hideLabels(parent: android.view.ViewGroup) {
                    for (index in 0 until parent.childCount) {
                        val child = parent.getChildAt(index)
                        child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        if (child is android.view.ViewGroup) hideLabels(child)
                    }
                }
                hideLabels(view)
            }

            fun title(event: JSONObject) =
                if (event.optString("source") == "owner_reported") event.optJSONObject("assessment")
                    ?.optString("title") ?: "직접 남긴 기록" else when (EvidenceScope.category(event)) {
                    "road" -> "도로 근거 검토"
                    "maintenance" -> "정비 전후 비교"
                    "help" -> "도움 요청"
                    else -> "충돌 근거 정리"
                }

            fun stamp(event: JSONObject): String {
                val value = event.optDouble("created", Double.NaN)
                return if (value.isFinite() && value > 0) SimpleDateFormat(
                    "M월 d일 HH:mm",
                    Locale.KOREAN
                ).format(Date((value * 1000).toLong())) else "시각 미확인"
            }

            fun summary(event: JSONObject) = event.optJSONObject("case_overview")
                ?.takeIf { it.optString("category") == EvidenceScope.category(event) }

            fun identity(event: JSONObject): String {
                val vehicle = summary(event)?.optJSONObject("vehicle")
                return listOf("plate", "model").mapNotNull {
                    vehicle?.optString(it)?.takeIf { it.isNotBlank() && it != "null" }
                }.joinToString(" · ").ifBlank { "차량 정보는 상세에서 확인" }
            }

            fun source(event: JSONObject) = when (event.optString("source")) {
                "synthetic" -> "합성 시험 자료"
                "owner_reported" -> "본인 작성 · 사실 확인 전"
                else -> "서버 수신 기록"
            }

            fun description(event: JSONObject) = listOf(
                title(event),
                EvidenceScope.helpStatus(event) ?: summary(event)?.optString("label") ?: "상세 확인 필요",
                identity(event),
                "서버 저장 ${stamp(event)}",
                source(event),
                "근거 보기"
            ).joinToString(", ")

            fun icon(event: JSONObject) = when (EvidenceScope.category(event)) {
                "road" -> "도로"
                "maintenance" -> "차량"
                else -> "기록"
            }

            val tabs = LinearLayout(body.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((key, label) in linkedMapOf(
                "all" to "전체",
                "road" to "도로",
                "maintenance" to "정비",
                "incident" to "충돌"
            )) {
                val count = records.count { key == "all" || EvidenceScope.category(it) == key }
                val tab =
                    ui.button(if (onCategory == null) "$label $count" else label, selected == key) {
                        if (onCategory != null) onCategory(key) else {
                            selected = key
                            expanded = false
                            rebuild(measuredWidth)
                        }
                    }.apply {
                        textSize = 20f * scale
                        minHeight = dp(54)
                        minimumHeight = dp(54)
                        isSelected = selected == key
                        setTextColor(if (selected == key) accent else ui.muted)
                        backgroundTintList = null
                        fun tabSurface(focused: Boolean) = GradientDrawable().apply {
                            setColor(if (selected == key) PleosPalette.selected(ui.light) else ui.cardColor)
                            cornerRadius = dp(6).toFloat()
                            setStroke(
                                if (selected == key || focused) dp(2) else ui.dp(1),
                                if (selected == key || focused) accent else line
                            )
                        }
                        background = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), tabSurface(true))
                            addState(intArrayOf(android.R.attr.state_pressed), tabSurface(true))
                            addState(intArrayOf(), tabSurface(false))
                        }
                        contentDescription = "$label 분류${if (selected == key) ", 선택됨" else ""}"
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                    }
                tabs.addView(tab, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = dp(10)
                    bottomMargin = dp(8)
                })
            }
            if (showCategories) root.addView(tabs)
            val categoryLabel = when (selected) {
                "road" -> "도로"
                "maintenance" -> "정비"
                "incident" -> "충돌"
                "help" -> "도움 요청"
                else -> "근거"
            }
            root.addView(
                text(
                    if (!showCategories) "현재 받은 $categoryLabel 기록 ${
                        records.count {
                            selected == "all" || EvidenceScope.category(
                                it
                            ) == selected
                        }
                    }건" else if (onCategory == null) "현재 받은 목록의 근거 정리 후보 · 전체 차량 이력 건수는 아닙니다" else "선택한 분류에서 받은 목록 · 이전 기록은 아래에서 계속 확인하세요",
                    14f
                ).apply { setTextColor(ui.muted) })
            val visible =
                records.filter { selected == "all" || EvidenceScope.category(it) == selected }
            if (visible.isEmpty()) {
                root.addView(ui.notice("이 목록에는 해당 근거 기록이 없습니다. 이전 시기의 기록도 확인해 보세요."))
                return
            }
            val row = LinearLayout(body.context).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.TOP
            }
            val event = visible.first()
            val hero = LinearLayout(body.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(26), dp(20), dp(26), dp(20))
            }

            hero.addView(text(title(event), 30f, true))
            val visual = LinearLayout(body.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            visual.addView(
                EvidenceScope.helpGraphic(event)
                    ?.let { DriverEventGraphic(body.context, it, ui.light) }
                    ?: CockpitIcon(body.context, icon(event), ui.ink),
                LinearLayout.LayoutParams(dp(104), dp(104)).apply { marginEnd = dp(18) })
            val headline = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
            headline.addView(
                text(
                    EvidenceScope.helpStatus(event) ?: summary(event)?.optString("label")
                        ?.takeIf { it.isNotBlank() } ?: "상세에서 상태 확인",
                    23f,
                    true
                )
            )
            visual.addView(headline, LinearLayout.LayoutParams(0, -2, 1f))
            hero.addView(visual)
            val heroVehicle = summary(event)?.optJSONObject("vehicle")
            val heroIdentity = listOf("plate", "model").mapNotNull {
                heroVehicle?.optString(it)
                    ?.takeIf { value -> value.isNotBlank() && value != "null" }
            }
            if (heroIdentity.isEmpty()) hero.addView(text("차량 정보는 상세에서 확인", 17f))
            else heroIdentity.forEach { hero.addView(text(it, 17f)) }
            val steps = summary(event)?.optJSONArray("steps")
            val flow = (0 until (steps?.length() ?: 0)).mapNotNull {
                steps?.optJSONObject(it)?.let { step ->
                    CaseFlowGraphic.Step(
                        step.optString("label"),
                        step.optString("status"),
                        step.optInt("state").coerceIn(0, 3)
                    )
                }
            }
            if (flow.size == 3) {
                if (body.resources.configuration.fontScale >= 1.3f) flow.forEach {
                    hero.addView(
                        text(
                            "${it.label} · ${it.status}",
                            19f
                        )
                    )
                }
                else hero.addView(
                    CaseFlowGraphic(body.context, flow, ui.light, scale),
                    LinearLayout.LayoutParams(-1, dp(118))
                )
            }
            hero.addView(text("서버 저장 ${stamp(event)}", 15f).apply { setTextColor(ui.muted) })
            hero.addView(
                text(
                    "${source(event)} · ${event.optString("id").take(8)}",
                    15f
                ).apply { setTextColor(ui.muted) })
            hero.addView(text("근거와 남은 확인 사항 보기", 18f, true).apply { setTextColor(accent) })
            interactive(
                hero,
                description(event) + ", " + flow.joinToString(", ") { "${it.label}: ${it.status}" }) {
                open(
                    event.optString("id")
                )
            }
            row.addView(
                hero,
                if (wide) LinearLayout.LayoutParams(0, -2, 1.35f)
                    .apply { marginEnd = dp(18) } else LinearLayout.LayoutParams(-1, -2)
                    .apply { bottomMargin = dp(16) })
            val remaining = visible.drop(1)
            if (remaining.isNotEmpty()) {
                val list = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }

                for (other in if (expanded) remaining else remaining.take(3)) {
                    val tile = LinearLayout(body.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(16), dp(18), dp(14))
                    }
                    tile.addView(
                        EvidenceScope.helpGraphic(other)
                            ?.let { DriverEventGraphic(body.context, it, ui.light) } ?: CockpitIcon(
                            body.context,
                            icon(other),
                            ui.ink
                        ),
                        LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
                    val labels =
                        LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
                    labels.addView(text(title(other), 21f, true))
                    labels.addView(
                        text(
                            EvidenceScope.helpStatus(other) ?: summary(other)?.optString("label")
                                ?.takeIf { it.isNotBlank() } ?: "상태 확인 필요", 17f
                        )
                    )
                    labels.addView(text(identity(other), 15f))
                    labels.addView(
                        text(
                            "서버 저장 ${stamp(other)}",
                            14f
                        ).apply { setTextColor(ui.muted) })

                    tile.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    interactive(tile, description(other)) { open(other.optString("id")) }
                    list.addView(
                        tile,
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                }
                if (remaining.size > 3) list.addView(ui.button(if (expanded) "목록 접기" else "현재 목록 ${remaining.size - 3}건 더 보기") {
                    expanded = !expanded
                    rebuild(measuredWidth)
                }.apply { textSize = 18f * scale })
                row.addView(
                    list,
                    if (wide) LinearLayout.LayoutParams(
                        0,
                        -2,
                        1f
                    ) else LinearLayout.LayoutParams(-1, -2)
                )
            }
            root.addView(row)
        }
        measuredWidth =
            (body.resources.configuration.screenWidthDp * body.resources.displayMetrics.density).toInt()
        rebuild(measuredWidth)
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != measuredWidth) {
                measuredWidth = width
                root.post { if (root.isAttachedToWindow) rebuild(width) }
            }
        }
    }
}
