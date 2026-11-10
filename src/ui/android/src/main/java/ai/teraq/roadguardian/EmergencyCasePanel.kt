package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONObject
import java.util.Locale

/** 저장된 응급 상태를 시연용으로 보여 준다. 출동 관련 출처는 사건 기록에 둔다 */
object EmergencyCasePanel {
    fun render(ui: PhoneAccountUi, body: LinearLayout, event: JSONObject) {
        val automotive =
            body.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
        val scale = if (automotive) (body.resources.configuration.screenWidthDp / 1000f).coerceIn(
            1f,
            1.65f
        ) else 1f

        fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
        fun text(value: String, size: Float = 18f, bold: Boolean = false) =
            ui.text(value, size * scale, bold)

        val assessment = event.optJSONObject("assessment") ?: return
        if (assessment.optString("kind") !in setOf("emergency", "manual_help")) return
        val expected = assessment.optString("recipient")
        val number = when (expected) {
            "119-mock" -> "119"
            "112-mock" -> "112"
            else -> "수신처 미확인"
        }
        val deliveries = event.optJSONArray("dispatches")
        val delivery =
            (0 until (deliveries?.length() ?: 0)).mapNotNull { deliveries?.optJSONObject(it) }
                .firstOrNull { it.optString("recipient") == expected }
        val receipt = delivery?.optString("receipt")?.takeIf { it != "null" && it.isNotBlank() }
        val state = EmergencyDelivery.state(
            expected,
            delivery?.optString("recipient"),
            delivery?.optString("status"),
            receipt,
            event.optBoolean("evidence_valid", false)
        )
        val valid = state != "evidence_invalid"
        val known = expected in setOf("119-mock", "112-mock")
        val wide =
            body.resources.configuration.screenWidthDp >= 760 && body.resources.configuration.fontScale < 1.3f
        val summary = if (wide) LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
        } else body
        val info = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        if (wide) {
            val row = LinearLayout(body.context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                summary,
                LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginEnd = ui.dp(28) })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row)
        }
        val hero = LinearLayout(body.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        hero.addView(
            text(number, if (automotive) 64f else 42f, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        val statusIcon = when (state) {
            "acknowledged" -> "check"
            "retry", "evidence_invalid" -> "alert"
            else -> "clock"
        }
        val statusTint = when (statusIcon) {
            "check" -> PleosPalette.action(ui.light)
            "alert" -> android.graphics.Color.parseColor(if (ui.light) "#805000" else "#FFC777")
            else -> PleosPalette.muted(ui.light)
        }
        hero.addView(
            CockpitIcon(body.context, statusIcon, statusTint),
            LinearLayout.LayoutParams(dp(84), dp(84))
        )
        summary.addView(hero)
        summary.addView(
            text(
                when (state) {
                    "acknowledged" -> "접수 완료"
                    "retry" -> "전달 실패 · 재시도 필요"
                    "evidence_invalid" -> "자료 확인 실패 · 접수 판단 보류"
                    else -> "접수 확인 대기"
                }, 28f, true
            )
        )
        val steps = listOf(
            CaseFlowGraphic.Step(
                "요청",
                if (valid && known) "준비 완료" else "확인 필요",
                if (valid && known) 1 else 3
            ),
            CaseFlowGraphic.Step(
                "전달", when (state) {
                    "acknowledged" -> "전달 완료"
                    "retry" -> "실패"
                    else -> "확인 대기"
                }, when (state) {
                    "acknowledged" -> 1
                    "retry" -> 3
                    else -> 0
                }
            ),
            CaseFlowGraphic.Step(
                "접수",
                if (state == "acknowledged") "접수 완료" else "미확인",
                if (state == "acknowledged") 1 else 0
            )
        )
        summary.addView(
            CaseFlowGraphic(body.context, steps, ui.light, scale),
            LinearLayout.LayoutParams(-1, dp(118))
        )
        if (!valid) return
        val data = event.optJSONObject("data")
        val context = data?.optJSONObject("context")
        val own = context?.optJSONObject("own_vehicle")
        fun value(obj: JSONObject?, key: String) =
            obj?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: "미확인"

        val vehicle = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        vehicle.addView(text("내 차량", 16f))
        vehicle.addView(text(value(own, "plate"), 27f, true))
        vehicle.addView(text("${value(own, "model")} · ${value(own, "color")}", 18f))
        val location = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        location.addView(text("기록 위치", 16f))
        location.addView(text(EventLocationPanel.displayAddress(event) ?: "지도에서 위치 확인", 22f, true))
        val fix = data?.optJSONObject("location")
        val lat = fix?.optDouble("lat", Double.NaN) ?: Double.NaN
        val lon = fix?.optDouble("lon", Double.NaN) ?: Double.NaN
        val accuracy = fix?.optDouble("accuracy_m", Double.NaN) ?: Double.NaN
        val coordinate =
            if (lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0) String.format(
                Locale.KOREAN,
                "%.5f, %.5f",
                lat,
                lon
            ) else "좌표 미확인"
        val locationDetails =
            LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        locationDetails.addView(text(coordinate, 17f))
        ui.disclosure(location, "좌표 확인", locationDetails)
        locationDetails.addView(
            text(
                if (accuracy.isFinite() && accuracy >= 0) "위치 오차 약 ${accuracy.toInt()} m" else "위치 오차 미확인",
                16f
            )
        )
        for (child in listOf(vehicle, location)) info.addView(
            child,
            if (info.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f)
                .apply { marginEnd = ui.dp(16) } else LinearLayout.LayoutParams(-1, -2))
        if (!wide) body.addView(info)
        if (state == "acknowledged") {
            val receiptDetails =
                LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
            receiptDetails.addView(text("접수 번호 $receipt", 17f, true))
            ui.disclosure(body, "접수 정보", receiptDetails)
        }
    }
}
