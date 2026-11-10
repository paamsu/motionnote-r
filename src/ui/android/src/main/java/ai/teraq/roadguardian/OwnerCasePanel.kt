package ai.teraq.roadguardian

import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 작성자와 기록 상태를 먼저 보여 주고, 긴 내용은 펼쳐서 본다 */
object OwnerCasePanel {
    fun render(ui: PhoneAccountUi, body: LinearLayout, event: JSONObject): Boolean {
        if (event.optString("source") != "owner_reported") return false
        val data = event.optJSONObject("data")
        if (!event.optBoolean("evidence_valid") || data?.optString("schema") != "road-guardian.owner-record.v1") {
            body.addView(ui.notice("작성 내용의 저장 상태를 확인하지 못해 기록 표시를 보류합니다."))
            return true
        }
        val car = body.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val scale = if (car) (body.resources.configuration.screenWidthDp / 1100f).coerceIn(
            1f,
            1.65f
        ) else 1f
        val large = body.resources.configuration.fontScale >= 1.3f
        fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
        fun text(parent: LinearLayout, value: String, size: Float = 18f, bold: Boolean = false) {
            parent.addView(
                ui.text(value, size * scale, bold).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
                })
        }

        fun date(value: Double) = if (value.isFinite() && value > 0) SimpleDateFormat(
            "M월 d일 HH:mm XXX",
            Locale.KOREAN
        ).format(Date((value * 1000).toLong())) else "미기재"

        fun supplied(obj: JSONObject?, key: String) =
            obj?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: "미기재"

        fun box() = LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(ui.cardColor)
                cornerRadius = dp(8).toFloat()
            }
        }

        val category = when (data.optString("category")) {
            "road" -> "도로 관련 자료"
            "maintenance" -> "정비 관련 자료"
            else -> "충돌 관련 자료"
        }
        ui.evidenceHeading(body, data.optString("title"), "$category · 본인 작성")
        val columns = LinearLayout(body.context)
        val identity = box()
        val identityRow = LinearLayout(body.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val illustration =
            if (!large) VehicleIdentityOutline(body.context, null, ui.light, true) else null
        if (illustration != null) identityRow.addView(
            illustration,
            LinearLayout.LayoutParams(dp(if (car) 152 else 112), dp(104))
                .apply { marginEnd = dp(16) })
        val labels = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        val own = data.optJSONObject("context")?.optJSONObject("own_vehicle")
        text(labels, supplied(own, "plate"), 26f, true)
        text(labels, supplied(own, "model"), 20f)
        if (supplied(own, "color") != "미기재") text(labels, supplied(own, "color"), 16f)
        identityRow.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        identity.addView(identityRow)
        if (!car && illustration != null) {
            var stacked: Boolean? = null
            identityRow.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                if (right > left) {
                    val plate = labels.getChildAt(0) as android.widget.TextView
                    val available =
                        right - left - identityRow.paddingLeft - identityRow.paddingRight - dp(128)
                    val vertical = plate.paint.measureText(plate.text.toString()) > available
                    if (stacked != vertical) {
                        stacked = vertical
                        identityRow.orientation =
                            if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                        illustration.layoutParams =
                            LinearLayout.LayoutParams(dp(112), dp(104)).apply {
                                marginEnd = if (vertical) 0 else dp(16)
                                bottomMargin = if (vertical) dp(10) else 0
                                gravity = Gravity.CENTER_HORIZONTAL
                            }
                        labels.layoutParams = if (vertical) LinearLayout.LayoutParams(
                            -1,
                            -2
                        ) else LinearLayout.LayoutParams(0, -2, 1f)
                    }
                }
            }
        }
        text(identity, "직접 입력 · 차량 확인 전", 15f)
        val status = box()
        text(status, "보관 상태", 18f, true)
        val steps = listOf(
            CaseFlowGraphic.Step("작성 내용", "저장 확인", 1),
            CaseFlowGraphic.Step("추가 관측", "별도 자료", 0),
            CaseFlowGraphic.Step("외부 제출", "미실시", 0)
        )
        if (large) steps.forEach { text(status, "${it.label} · ${it.status}", 18f) }
        else status.addView(
            CaseFlowGraphic(body.context, steps, ui.light, scale),
            LinearLayout.LayoutParams(-1, dp(118))
        )
        text(status, "자동 사고·정비 판정 미실시", 16f, true)
        columns.addView(identity)
        columns.addView(status)
        var lastWide: Boolean? = null
        fun arrange(width: Int) {
            val wide = width / body.resources.displayMetrics.density >= 760 && !large
            if (lastWide == wide) return
            lastWide = wide
            columns.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            identity.layoutParams = if (wide) LinearLayout.LayoutParams(0, -2, 1f)
                .apply { marginEnd = dp(16) } else LinearLayout.LayoutParams(-1, -2)
                .apply { bottomMargin = dp(12) }
            status.layoutParams =
                if (wide) LinearLayout.LayoutParams(0, -2, 1.15f) else LinearLayout.LayoutParams(
                    -1,
                    -2
                )
        }
        arrange((body.resources.configuration.screenWidthDp * body.resources.displayMetrics.density).toInt())
        columns.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            if (right > left) arrange(
                right - left
            )
        }
        body.addView(columns, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(12)
        })
        val details = LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        text(details, "작성 시각 · ${date(data.optDouble("reported_at", Double.NaN))}", 16f)
        text(details, "서버 보관 · ${date(event.optDouble("created", Double.NaN))}", 16f)
        text(details, "작성한 설명", 19f, true)
        details.addView(ui.notice(data.optString("description").takeIf { it.isNotBlank() }
            ?: "작성한 설명이 없습니다."))
        text(details, "입력한 차량 정보와 시각은 소유 관계나 영상 인식으로 확인한 결과가 아닙니다.", 16f)
        text(details, "차종 공통 그림입니다. 실제 차량 외관과 다를 수 있으며 영상 인식 결과가 아닙니다.", 16f)
        text(details, "읽은 작성 내용과 서버에 기록된 해시가 일치합니다. 센서 원본·현장 사실·접촉 관계·책임을 확인한 자료는 아닙니다.", 16f)
        ui.disclosure(body, "작성 내용과 출처", details)
        body.post { (body.parent as? android.widget.ScrollView)?.scrollTo(0, 0) }
        return true
    }
}
