package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.io.File

class EventNotificationPolicyProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val source = JSONObject(File(targetContext.filesDir, "popup-fixture.json").readText())
            var checks = 0
            fun verify(value: Boolean, label: String) {
                check(value) { label }
                checks++
            }

            val key = DriverEventNotification.identity(source)
            verify(key != null, "valid fixture")
            verify(
                DriverEventNotification.identity(JSONObject(source.toString())) == key,
                "copy preserves identity"
            )
            for ((field, value) in listOf(
                "id" to "",
                "id" to "not-a-uuid",
                "hash" to "",
                "hash" to "xyz",
                "hash" to 123,
                "source" to "device",
                "source" to true,
                "evidence_valid" to false,
                "evidence_valid" to "true",
                "status" to "dismissed",
                "status" to "input_review_required",
                "status" to "unknown"
            )) {
                val event = JSONObject(source.toString()).put(field, value)
                verify(DriverEventNotification.identity(event) == null, "reject $field=$value")
            }
            for (field in listOf(
                "id",
                "hash",
                "source",
                "evidence_valid",
                "assessment",
                "status"
            )) {
                val event = JSONObject(source.toString())
                event.remove(field)
                verify(DriverEventNotification.identity(event) == null, "missing $field")
            }
            val changed = JSONObject(source.toString()).put("hash", "a".repeat(64))
            verify(DriverEventNotification.identity(changed) != key, "different evidence identity")
            val state = DriverEventNoticeState()
            state.accept(org.json.JSONArray(), 1000)
            state.accept(org.json.JSONArray().put(source), 1001)
            verify(state.current(1001) != null, "eligible fresh event selected")
            state.accept(
                org.json.JSONArray()
                    .put(JSONObject(source.toString()).put("evidence_valid", false)), 1002
            )
            verify(state.current(1002) == null, "invalidated event removed")
            val report = JSONObject().put("passed", checks).put("permission_changed", false)
                .put("notification_posted", false)
            File(
                targetContext.filesDir,
                "notification-policy-result.json"
            ).writeText(report.toString())
            result.putString(
                "stream",
                "PASS: $checks notification identity and event validity checks; no permission mutation or notification publication\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
