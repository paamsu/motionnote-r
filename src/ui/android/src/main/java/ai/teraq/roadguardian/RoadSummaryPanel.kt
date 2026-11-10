package ai.teraq.roadguardian

import android.content.pm.PackageManager
import android.widget.LinearLayout
import android.view.Gravity
import org.json.JSONObject

/** 공모전용 집계 화면. 숫자는 실제 서로 다른 차량 수나 기관 접수를 뜻하지 않는다 */
object RoadSummaryPanel {
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        data: JSONObject,
        client: BackendClient? = null,
        allowed: () -> Boolean = { false },
        openGroup: ((String) -> Unit)? = null
    ) {
        val car = body.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val scale = if (car) (body.resources.configuration.screenWidthDp / 1100f).coerceIn(
            1f,
            1.65f
        ) else 1f

        fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
        fun text(s: String, size: Float = 18f, bold: Boolean = false) =
            ui.text(s, size * scale, bold)
        body.addView(
            ui.text(
                "도로 관측 모아 보기",
                if (car && body.resources.configuration.screenWidthDp >= 760) 44.2f else 34f,
                true
            ).apply { gravity = Gravity.CENTER })
        val groups = data.optJSONArray("groups")
        fun count(key: String) = data.optInt(key, -1)
        val included = count("included_count")
        val pending = count("pending_count")
        val excluded = count("excluded_count")
        val total = count("input_count")
        val valid =
            data.optString("schema") == "road-guardian.demo-road-summary.v1" && data.optString("source") == "synthetic" && data.opt(
                "external_submission"
            ) == false && data.opt("repair_verified") == false && groups != null &&
                    listOf(
                        included,
                        pending,
                        excluded,
                        total,
                        count("group_count")
                    ).all { it in 0..100000 } && included + pending + excluded == total && count("group_count") == groups.length() &&
                    (0 until groups.length()).all {
                        val g = groups.optJSONObject(it)
                        g != null && g.optInt(
                            "report_count",
                            -1
                        ) > 0 && g.optInt(
                            "fixture_vehicle_count",
                            -1
                        ) in 1..g.optInt("report_count", 0) && (!g.has("id") || g.optString("id")
                            .matches(Regex("[0-9a-f]{16}")))
                    } &&
                    (0 until groups.length()).sumOf {
                        groups.getJSONObject(it).getInt("report_count")
                    } == included
        if (!valid) {
            body.addView(ui.notice("집계 자료 확인 실패 · 이전 수치를 표시하지 않습니다"))
            return
        }
        val details = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(
            ui.text(
                if (groups!!.length() > 0) "도로 ${groups.length()}곳 · 기록 ${included}건" else "묶을 수 있는 관측 없음",
                ui.evidenceCaptionSize,
                true
            ).apply { gravity = Gravity.CENTER })
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            if (client != null && g.has("id")) body.addView(
                RoadSummaryPreview(
                    body.context,
                    ui,
                    g.getString("id"),
                    client,
                    allowed
                ), LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = ui.dp(20)
                    bottomMargin = ui.dp(12)
                })
            if (openGroup != null && g.has("id")) body.addView(
                ui.button(
                    "도로 ${i + 1} · 원본 ${
                        g.getInt(
                            "report_count"
                        )
                    }건 보기"
                ) { openGroup(g.getString("id")) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
            details.addView(text("묶음 ${i + 1} · 관측 ${g.getInt("report_count")}건", 22f, true))
        }
        details.addView(text("받은 관측 ${total}건 · 검토된 자료 ${included}건", 22f, true))
        details.addView(text("확인 대기 ${pending}건 · 집계 제외 ${excluded}건", 22f, true))
        details.addView(
            text(
                "같은 도로·방향 구간·100 m 격자 기준. 합성 관측 자료이며 기관 제공은 공모전 모의 처리이며 보수 확인은 아닙니다.",
                18f
            )
        )
        details.addView(
            text(
                "가상 차량 ID 수는 실제 독립 차량 수가 아닙니다. 확인 대기·만료·원본 오류는 묶음에 넣지 않습니다. 원본 관측은 합치거나 지우지 않습니다.",
                18f
            )
        )
        details.addView(
            text(
                "45° 방향 구간과 100 m 격자는 개발용 묶음 기준입니다. 실제 동일 포트홀·차로·도로 보수 여부를 확인한 결과가 아닙니다.",
                18f
            )
        )
        ui.disclosure(body, "묶음 기준과 출처", details)
    }
}
