package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONArray

/** 지금 받은 페이지만 거른다. 차량 전체 이력을 뜻하지 않는다 */
object MaintenanceRecordList {
    fun render(ui: PhoneAccountUi, body: LinearLayout, events: JSONArray, open: (String) -> Unit) {
        val scale =
            if (body.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) (body.resources.configuration.screenWidthDp / 1000f).coerceIn(
                1f,
                1.65f
            ) else 1f
        val measurements = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (EvidenceScope.category(event) == "maintenance") measurements.put(event)
        }
        if (body.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            VehicleCaseWorkspace.render(
                ui,
                body,
                measurements,
                "maintenance",
                null,
                showCategories = false,
                open = open
            )
            return
        }
        body.addView(ui.text("정비 기록 선택", 24f * scale, true))
        if (measurements.length() == 0) {
            body.addView(ui.notice("이 목록에는 정비 기록이 없습니다. 이전 기록이 있으면 다음 목록에서 확인하세요."))
            return
        }
        body.addView(
            ui.text(
                "현재 목록의 정비 기록 ${measurements.length()}건 · 측정 자료와 직접 작성한 내용을 구분해 확인합니다",
                17f * scale
            )
        )
        AccountEventList.render(ui, body, measurements, scale, open)
    }
}
