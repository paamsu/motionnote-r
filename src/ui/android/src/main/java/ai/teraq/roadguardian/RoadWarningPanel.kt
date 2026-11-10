package ai.teraq.roadguardian

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout
import org.json.JSONObject

/** 합성 경고 상세 공통 화면. 차로나 실시간 경로를 단정하지 않는다 */
object RoadWarningPanel {
    fun render(
        activity: Activity,
        parent: LinearLayout,
        warning: JSONObject,
        light: Boolean,
        now: Double
    ) {
        val ui = PhoneAccountUi(activity, light)
        val accent = Color.parseColor(if (light) "#805000" else "#FFC777")
        val advice = warning.optJSONObject("advisory")
        parent.addView(
            ui.text(
                advice?.optString("title") ?: warning.optString("message"),
                28f,
                true
            )
        )
        parent.addView(
            ui.text("${warning.optInt("distance_m")} m 앞", 42f, true)
                .apply { setTextColor(accent) })
        parent.addView(
            ui.text("약 ${warning.optInt("eta_seconds")}초 후 · 합성 주행 시험", 17f)
                .apply { setTextColor(ui.muted) })
        parent.addView(ui.text(advice?.optString("instruction") ?: "전방 도로 상태를 확인하세요.", 23f, true))
        parent.addView(
            ui.text(
                "관측 차량 ${warning.optInt("independent_vehicle_count")}대 / 기록 ${
                    warning.optInt(
                        "report_count"
                    )
                }건", 18f
            )
        )
        fun minutes(seconds: Double) = kotlin.math.ceil(seconds.coerceAtLeast(0.0) / 60).toInt()
        val age = ((now - warning.optDouble("latest_at", now)).coerceAtLeast(0.0) / 60).toInt()
        val remaining = minutes(warning.optDouble("expires_at", now) - now)
        val remainingLabel = if (remaining >= 60) "약 ${
            kotlin.math.ceil(remaining / 60.0).toInt()
        }시간" else "${remaining}분"
        parent.addView(
            ui.text(
                "${if (age == 0) "방금 관측" else "${age}분 전 관측"} · 유효 시간 $remainingLabel 남음",
                16f
            ).apply { setTextColor(ui.muted) })
        parent.addView(
            ui.text(advice?.optString("direction") ?: "접근 방향 확인 필요", 16f)
                .apply { setTextColor(ui.muted) })
        parent.addView(
            ui.text(advice?.optString("basis") ?: "관측 근거 확인 필요", 16f)
                .apply { setTextColor(ui.muted) })
        parent.addView(
            ui.text(advice?.optString("lane_status") ?: "차로 정보 없음", 16f)
                .apply { setTextColor(ui.muted) })
    }
}
