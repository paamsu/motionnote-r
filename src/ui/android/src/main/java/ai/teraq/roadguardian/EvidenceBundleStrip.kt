package ai.teraq.roadguardian

import android.view.Gravity
import android.widget.LinearLayout
import org.json.JSONObject

/** 서버가 확인한 저장 첨부 목록을 짧게 보여 준다 */
object EvidenceBundleStrip {
    fun items(event: JSONObject): List<Pair<String, String>> {
        if (event.opt("evidence_valid") != true) return emptyList()
        val id = event.optString("id")
        val hash = event.optString("hash")
        if (!hash.matches(Regex("[0-9a-f]{64}"))) return emptyList()
        val rows = mutableListOf<Pair<String, String>>()
        val surround = runCatching {
            SyntheticSurroundViews.metadata(event)
                ?.let { SyntheticSurroundViews.validate(it, id, hash) }
        }.getOrNull()
        val video = event.optJSONObject("video")
        val photo = video?.optJSONObject("input_photo")
        val photoValid =
            video?.opt("integrity_valid") == true && photo?.opt("integrity_valid") == true &&
                    photo.optString("event_id") == id && photo.optString("sensor_sha256") == hash &&
                    video.optString("input_kind") != "authored_temporal_scene"
        if (!surround.isNullOrEmpty()) rows.add("photo" to "사진 ${surround.size}장")
        else if (photoValid) rows.add("photo" to "사진")
        if (video?.opt("integrity_valid") == true) rows.add("video" to "영상")
        if (EventLocationPanel.location(event) != null) rows.add("pin" to "위치")
        if (event.optJSONObject("assessment")?.optString("kind") == "diagnosis") {
            val checked = DiagnosticEvidenceReview.checkedCase(event)
            if (checked != null) rows.add("signal" to if (DiagnosticEvidenceReview.canOpen(event)) "전후 측정" else "측정")
        } else if (event.optJSONObject("data")?.optJSONArray("samples")?.length()
                ?.let { it > 0 } == true
        ) rows.add("signal" to "측정")
        return rows
    }

    fun render(
        ui: PhoneAccountUi,
        host: LinearLayout,
        event: JSONObject,
        compact: Boolean = false
    ) {
        val items = items(event)
        if (items.isEmpty()) return
        val row = LinearLayout(host.context).apply {
            gravity = Gravity.CENTER
            isBaselineAligned = false
            contentDescription = "보관 자료: " + items.joinToString(" · ") { it.second }
        }
        for ((icon, label) in items) {
            val cell = LinearLayout(host.context).apply {
                orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            cell.addView(
                CockpitIcon(host.context, icon, ui.ink),
                LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
            )
            cell.addView(
                ui.text(
                    label,
                    if (host.resources.configuration.screenWidthDp >= 760) 22f else 16f
                ).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(if (compact) -2 else -1, -2)
                    .apply { if (compact) marginStart = ui.dp(8) })
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        host.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(8)
            bottomMargin = ui.dp(16)
        })
    }
}
