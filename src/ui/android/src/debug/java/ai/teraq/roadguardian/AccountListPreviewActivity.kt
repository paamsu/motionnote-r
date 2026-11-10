package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic fixtures only; never substitutes for authenticated account requests. */
class AccountListPreviewActivity : Activity() {
    companion object {
        var previewFontScale = 1f
    }

    override fun attachBaseContext(base: android.content.Context) {
        val configuration = android.content.res.Configuration(base.resources.configuration)
            .apply { fontScale = previewFontScale.coerceIn(1f, 2f) }
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val ui = PhoneAccountUi(this)
        val body = ui.install()
        fun showList() {
            body.removeAllViews()
            ui.heading(body, "차량 사건 기록", "개발용 미리보기 · 모두 합성 시험 자료입니다.")
            val events = JSONArray()
            listOf("주차 중 충격 감지", "급제동 뒤 도로 위험 확인", "주차 중 반복 충격 감지").forEachIndexed { i, title ->
                events.put(
                    JSONObject().put("id", "preview-$i").put("created", 1792591200 - i * 86400)
                        .put("source", "synthetic")
                        .put("assessment", JSONObject().put("title", title))
                )
            }
            AccountEventList.render(ui, body, events) { id ->
                body.removeAllViews()
                ui.heading(body, "선택한 시험 기록", id)
                body.addView(ui.notice("목록 선택 동작 확인용입니다. 실제 사건 상세 조회가 아닙니다."))
                body.addView(ui.button("목록으로 돌아가기", true) { showList() })
            }
            body.addView(ui.button("미리보기 닫기") { finish() })
        }
        showList()
    }
}
