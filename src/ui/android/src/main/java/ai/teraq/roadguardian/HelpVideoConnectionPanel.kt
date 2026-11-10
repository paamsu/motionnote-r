package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONObject

/** 저장된 장면으로 하는 통화 연습. 실제 카메라, 전화, 외부 수신자는 없다 */
object HelpVideoConnectionPanel {
    fun eligible(event: JSONObject): Boolean =
        BuildConfig.CONTEST_DEMO && event.optString("source") == "synthetic" &&
                event.opt("evidence_valid") == true && event.optJSONObject("assessment")
            ?.optString("kind") in setOf("manual_help", "emergency") &&
                DriverEventNoticeState.helpDeliveryState(event) == "acknowledged" &&
                event.optJSONObject("video")?.let {
                    it.optString("source") == "synthetic_scene_fixture" && it.opt("integrity_valid") == true && it.opt(
                        "camera_evidence"
                    ) == false
                } == true

    fun session(event: JSONObject): JSONObject? {
        if (!eligible(event)) return null
        val s = event.optJSONObject("help_video_session") ?: return null
        val v = event.getJSONObject("video")
        if (s.optString("schema") != "road-guardian.help-video-session.v1" || s.optString("event_id") != event.optString(
                "id"
            ) ||
            s.optString("original_hash") != event.optString("hash") || s.optString("recipient") != event.getJSONObject(
                "assessment"
            ).optString("recipient") ||
            s.optString("video_sha256") != v.optString("sha256") || s.optLong(
                "video_bytes",
                -1
            ) != v.optLong("bytes", -2) ||
            s.optString("source") != "stored_synthetic_video" || s.optString("state") != "connected_mock" || s.optString(
                "delivery_state"
            ) != "acknowledged_mock" ||
            s.opt("external_connection") != false || s.opt("live_camera") != false || s.opt("camera_evidence") != false || !s.optString(
                "session_id"
            ).matches(Regex("[0-9a-f-]{36}"))
        ) return null
        val deliveries = event.optJSONArray("dispatches") ?: return null
        if ((0 until deliveries.length()).none {
                val d = deliveries.optJSONObject(it)
                d != null && d.optString("id") == s.optString("dispatch_id") && d.optString("event_id") == event.optString(
                    "id"
                ) && d.optString("recipient") == s.optString("recipient") && d.optString("status") == "acknowledged" && d.optString(
                    "receipt"
                ) == s.optString("mock_receipt")
            }) return null
        return s
    }

    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        event: JSONObject,
        allowed: () -> Boolean,
        onConnect: () -> Unit,
        onOpen: () -> Unit
    ) {
        if (!eligible(event)) return
        val ready = session(event) != null
        val number = event.getJSONObject("assessment").getString("recipient").substringBefore('-')
        body.addView(ui.text("$number 영상 연결", 30f, true))
        body.addView(ui.text(if (ready) "현장 기록 전달 완료" else "현장 영상으로 상황 전달", 24f))
        body.addView(
            ui.button(
                if (ready) "영상 연결 화면" else "영상으로 연결",
                true
            ) { if (allowed()) if (ready) onOpen() else onConnect() })
        val provenance = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        provenance.addView(ui.text("저장된 합성 영상으로 구성한 영상통화 시연 · 실제 기관 연결 없음", 18f))
        ui.disclosure(body, "연결 기록", provenance)
    }
}
