package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 합성 비교 출처 읽기 전용. 포그라운드와 차량 제한은 호출한 쪽이 맡는다 */
object RoadObservationSource {
    fun reference(parent: JSONObject): Pair<String, String>? {
        if (parent.opt("evidence_valid") != true || parent.optString("source") != "synthetic") return null
        val c = parent.optJSONObject("road_followup_case") ?: return null
        if (c.optString("stage") !in setOf("repeat_signal", "no_repeat_signal")) return null
        val id = c.optString("followup_id")
        val hash = c.optString("followup_sha256")
        return if (id.matches(Regex("[0-9a-f-]{36}")) && hash.matches(Regex("[0-9a-f]{64}"))) id to hash else null
    }

    fun load(client: BackendClient, parent: JSONObject): Pair<JSONObject, JSONObject> {
        val expected = checkNotNull(reference(parent))
        val fresh = JSONObject(client.request("/api/events/${parent.getString("id")}"))
        check(
            fresh.optString("id") == parent.getString("id") && fresh.optString("hash") == parent.optString(
                "hash"
            ) && reference(fresh) == expected
        )
        val source = JSONObject(client.request("/api/events/${expected.first}"))
        check(
            source.optString("id") == expected.first && source.optString("hash") == expected.second && source.opt(
                "evidence_valid"
            ) == true && source.optString("source") == "synthetic"
        )
        val data = source.getJSONObject("data")
        check(data.getString("source") == "synthetic")
        val samples = data.getJSONArray("samples")
        check(samples.length() > 1)
        for (i in 0 until samples.length()) {
            val s = samples.getJSONObject(i)
            check(
                s.getDouble("vertical_g").isFinite() && s.getDouble("speed_kph")
                    .isFinite() && s.getDouble("speed_kph") >= 0
            )
        }
        check(
            data.getDouble("capture_started_at")
                .isFinite() && data.getDouble("capture_started_at") > 0
        )
        check(data.getDouble("sample_hz").isFinite() && data.getDouble("sample_hz") > 0)
        check(data.getJSONObject("units").getString("vertical_g") == "g, gravity removed")
        val location = data.getJSONObject("location")
        check(
            location.getString("road_id").isNotBlank() && location.getDouble("heading").isFinite()
        )
        // 로딩 중에 연결이 바뀌었는지 보려고 출처 요청 뒤에 부모 기록을 다시 읽는다
        val finalParent = JSONObject(client.request("/api/events/${parent.getString("id")}"))
        check(
            finalParent.optString("id") == fresh.getString("id") && finalParent.optString("hash") == fresh.optString(
                "hash"
            ) && reference(finalParent) == expected
        )
        return finalParent to source
    }

    fun render(ui: PhoneAccountUi, body: LinearLayout, source: JSONObject) {
        val scale =
            if (body.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE))
                (body.resources.configuration.screenWidthDp / 1000f).coerceIn(1f, 1.65f) else 1f
        body.addView(ui.text("이후 관측 원본", 30f * scale, true))
        body.addView(ui.text("합성 센서 입력 · 사진에서 추정한 값이 아닙니다", 17f * scale))
        val data = source.getJSONObject("data")
        val samples = data.getJSONArray("samples")
        var peak = 0.0
        val speeds = mutableListOf<Double>()
        for (i in 0 until samples.length()) {
            val s = samples.getJSONObject(i)
            peak = maxOf(peak, kotlin.math.abs(s.getDouble("vertical_g")))
            speeds.add(s.getDouble("speed_kph"))
        }
        speeds.sort()
        val middle = speeds.size / 2
        val speed =
            if (speeds.size % 2 == 0) (speeds[middle - 1] + speeds[middle]) / 2 else speeds[middle]
        val at = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss z",
            Locale.KOREAN
        ).format(Date((data.getDouble("capture_started_at") * 1000).toLong()))
        val location = data.getJSONObject("location")
        for (line in listOf(
            "관측 시작  $at",
            "수직 신호 최댓값  ${String.format(Locale.KOREAN, "%.3f g", peak)}",
            "중앙 속도  ${String.format(Locale.KOREAN, "%.1f km/h", speed)}",
            "표본 ${samples.length()}개 · ${data.getDouble("sample_hz")} Hz",
            "도로 ${location.getString("road_id")} · 방향 ${location.getDouble("heading")}°"
        )) body.addView(ui.text(line, 21f * scale, true))
        body.addView(ui.text("작성된 위치·시각·측정 조건입니다. 실제 촬영·도로 보수·안전 확인을 뜻하지 않습니다.", 17f * scale))
        val detail = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        detail.addView(
            ui.text(
                "기록 ID\n${source.getString("id")}\n원본 SHA-256\n${source.getString("hash")}",
                16f * scale
            )
        )
        ui.disclosure(body, "원본 식별 정보", detail)
    }
}
