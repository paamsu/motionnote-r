package ai.teraq.roadguardian

import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/** 사진 중심 기록 목록. 상태와 정보는 모두 받은 기록 그대로 쓴다 */
object VehicleOverviewPanel {
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        events: JSONArray,
        category: String?,
        onCategory: ((String) -> Unit)?,
        selection: VehicleOverviewSelection = VehicleOverviewSelection(),
        open: (String) -> Unit
    ) {
        val records = (0 until events.length()).mapNotNull { events.optJSONObject(it) }
            .filter { EvidenceScope.category(it) != null }
        val root = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(root, LinearLayout.LayoutParams(-1, -2))
        category?.let { selection.setCategory(it) }
        var selected = selection.category
        val categories = linkedMapOf(
            "all" to "전체",
            "road" to "도로",
            "maintenance" to "정비",
            "incident" to "충돌"
        ).apply { if (records.any { EvidenceScope.category(it) == "help" }) put("help", "도움 요청") }

        fun label(value: String, size: Float, bold: Boolean = false) =
            ui.text(value, size, bold).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }
            }

        fun rebuild() {
            root.removeAllViews()
            val visible =
                records.filter { selected == "all" || EvidenceScope.category(it) == selected }
            val position = selection.resolve(visible.map { it.getString("id") })
            val choices = categories.keys.toList()
            val filter = Spinner(body.context).apply {
                contentDescription = "기록 분류"
                adapter = object : ArrayAdapter<String>(
                    body.context,
                    android.R.layout.simple_spinner_item,
                    categories.values.toList()
                ) {
                    override fun getView(
                        index: Int,
                        recycled: View?,
                        parent: android.view.ViewGroup
                    ): View = (super.getView(index, recycled, parent) as TextView).apply {
                        typeface = PleosTypography.face()
                        setTextColor(ui.ink)
                        textSize = 20f
                    }

                    override fun getDropDownView(
                        index: Int,
                        recycled: View?,
                        parent: android.view.ViewGroup
                    ): View = (super.getDropDownView(index, recycled, parent) as TextView).apply {
                        typeface = PleosTypography.face()
                        setTextColor(ui.ink)
                        setBackgroundColor(ui.cardColor)
                        textSize = 22f
                        setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
                    }
                }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setSelection(choices.indexOf(selected).coerceAtLeast(0))
                minimumHeight = ui.dp(56)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        index: Int,
                        id: Long
                    ) {
                        val next = choices[index]
                        if (next != selected) {
                            selection.setCategory(next)
                            if (onCategory != null) onCategory(next) else {
                                selected = next
                                rebuild()
                            }
                        }
                    }
                }
            }
            root.addView(
                filter,
                LinearLayout.LayoutParams(ui.dp(180), ui.dp(56)).apply { gravity = Gravity.START })
            if (visible.isEmpty()) {
                root.addView(ui.notice("이 목록에는 해당 기록이 없습니다."))
                return
            }
            val event = visible[position]
            val overview = event.optJSONObject("case_overview")?.takeIf {
                it.optString("schema") == "road-guardian.case-overview.v1" && it.optString("category") == EvidenceScope.category(
                    event
                )
            }
            val title = when (EvidenceScope.category(event)) {
                "road" -> "도로 관측"
                "maintenance" -> "정비 비교"
                "help" -> "도움 요청"
                else -> "사건 근거"
            }
            val state = when (event.opt("evidence_valid")) {
                false -> "invalid"
                true -> overview?.optString("state")
                else -> null
            }
            val kind = event.optJSONObject("assessment")?.optString("kind")
            val graphicKind = when {
                event.opt("source") != "synthetic" || event.opt("evidence_valid") != true || event.optString(
                    "status"
                ) == "dismissed" -> null

                event.optString("status") == "input_review_required" -> "review"
                EvidenceScope.category(event) == "help" -> EvidenceScope.helpGraphic(event)
                kind == "parking" -> "crash"
                DriverBriefPanel.verifiedBrief(event) != null -> DriverBriefPanel.verifiedBrief(
                    event
                )?.first

                kind in setOf("reckless", "violation", "wanted") -> kind
                kind in setOf("pothole", "crash", "diagnosis") -> kind
                else -> null
            }
            val brief = graphicKind?.let { EventBriefState.from(event) }
            val headline = when (state) {
                "comparison_available" -> if (EvidenceScope.category(event) == "maintenance") "재측정 비교 자료 준비" else "후속 관측 비교 자료 준비"
                "awaiting_followup" -> if (EvidenceScope.category(event) == "maintenance") "재측정 자료 대기" else "후속 관측 자료 대기"
                "needs_review" -> "추가 자료 확인 필요"
                "invalid" -> "자료 확인 실패"
                "dismissed" -> "검토에서 제외한 기록"
                "owner_recorded" -> "본인 작성 · 사실 확인 전"
                else -> "$title · 상태 미확인"
            }
            root.addView(
                label(
                    if (EvidenceScope.category(event) == "help") "${
                        event.getJSONObject("assessment").getString("recipient")
                            .removeSuffix("-mock")
                    } 도움 요청" else brief?.title ?: headline, 32f, true
                )
            )
            val next = when (state) {
                "comparison_available" -> "원본에서 비교 조건을 확인하세요"
                "awaiting_followup" -> if (EvidenceScope.category(event) == "maintenance") "재측정 자료를 기다립니다" else "이후 관측을 기다립니다"
                "needs_review" -> "빠진 자료를 확인하세요"
                "invalid" -> "자료를 다시 확인해야 합니다"
                "dismissed" -> "제외한 기록입니다"
                "owner_recorded" -> "작성한 내용과 첨부를 확인하세요"
                else -> "원본에서 상태를 확인하세요"
            }
            val identity = overview?.optJSONObject("vehicle")
            val supplied = listOf("plate", "model").mapNotNull { key ->
                identity?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
            }
            if (brief == null) root.addView(
                label(
                    "기록 차량 · " + supplied.joinToString(" · ").ifBlank { "정보 미확인" }, 18f
                ).apply { setTextColor(ui.muted) })
            val large = body.resources.configuration.fontScale >= 1.3f
            val height = (body.resources.configuration.screenHeightDp * .38f).toInt()
                .coerceIn(if (large) 180 else 220, if (large) 240 else 340)
            val stage = FrameLayout(body.context)
            val illustration = if (brief != null) DriverEventGraphic(
                body.context,
                requireNotNull(graphicKind),
                ui.light,
                brief.voltageLow
            ).apply {
                contentDescription = "${brief.title}. 상세 기록 보기"
                isClickable = true
                isFocusable = true
                setOnClickListener { open(event.getString("id")) }
            } else ImageView(body.context).apply {
                setImageResource(R.drawable.generic_vehicle_v179)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "차종 공통 입체 차량 이미지. 기록 원본 보기"
                isClickable = true
                isFocusable = true
                setOnClickListener { open(event.getString("id")) }
            }
            stage.addView(illustration, FrameLayout.LayoutParams(-1, -1).apply {
                gravity = Gravity.CENTER
                setMargins(ui.dp(76), 0, ui.dp(76), 0)
            })
            fun arrow(value: String, description: String, delta: Int, gravityValue: Int) {
                val control = ui.neutralTextButton(value) {
                    selection.select(visible[position + delta].getString("id"))
                    rebuild()
                }.apply {
                    minHeight = ui.dp(88)
                    minimumHeight = minHeight
                    setPadding(0, 0, 0, 0)
                    contentDescription = description
                    isEnabled = position + delta in visible.indices
                    textSize = 42f
                }
                stage.addView(
                    control,
                    FrameLayout.LayoutParams(
                        ui.dp(76),
                        ui.dp(88),
                        gravityValue or Gravity.CENTER_VERTICAL
                    )
                )
            }
            arrow("‹", "앞 기록", -1, Gravity.START)
            arrow("›", "다음 기록", 1, Gravity.END)
            root.addView(stage, LinearLayout.LayoutParams(-1, ui.dp(height)))
            root.addView(
                label(
                    EvidenceScope.helpStatus(event) ?: EventBriefState.roadComparisonLabel(event)
                    ?: brief?.next ?: next, 22f, true
                )
            )
            val provenance = when (event.optString("source")) {
                "synthetic" -> ""
                "owner_reported" -> "본인 작성 기록"
                else -> "서버 수신 기록"
            }
            val imageNote = listOfNotNull(
                if (brief == null) "차종 공통 이미지" else null,
                provenance.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (imageNote.isNotBlank()) root.addView(
                label(
                    imageNote,
                    14f
                ).apply { setTextColor(ui.muted) })
            val actionLabel = brief?.action ?: "상세 기록"
            val action = ui.neutralTextButton(actionLabel) { open(event.getString("id")) }.apply {
                contentDescription = "현재 받은 기록 ${position + 1}, ${visible.size}건 중. $actionLabel"
            }
            root.addView(action, LinearLayout.LayoutParams(-1, -2))
        }
        rebuild()
    }
}
