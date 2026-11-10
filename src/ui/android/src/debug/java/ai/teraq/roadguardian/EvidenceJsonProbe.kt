package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

class EvidenceJsonProbe : Instrumentation() {
    override fun onCreate(a: Bundle?) {
        super.onCreate(a)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val cases = JSONObject(
                File(
                    targetContext.filesDir,
                    "plate-video-boundary-cases.json"
                ).readText()
            )
            val event = cases.getJSONObject("valid")
            check(PlateVideoAnalysis.checked(event) != null && PlateAnalysis.checked(event) != null)
            check(PlateVideoAnalysis.checked(JSONObject(event.toString())) == null)
            val copied = EvidenceJson.copy(event)
            check(PlateVideoAnalysis.checked(copied) != null && PlateAnalysis.checked(copied) != null)
            val saved = File(targetContext.filesDir, "evidence-json-preserved.json")
            saved.writeText(EvidenceJson.encode(event))
            val restored = JSONObject(saved.readText())
            check(PlateVideoAnalysis.checked(restored) != null && PlateAnalysis.checked(restored) != null)
            copied.getJSONObject("plate_video_analysis").getJSONArray("frames").getJSONObject(0)
                .put("t", 9)
            check(PlateVideoAnalysis.checked(copied) == null && PlateVideoAnalysis.checked(event) != null)
            for (name in listOf("corrupt", "missing")) check(
                PlateVideoAnalysis.checked(
                    JSONObject(
                        EvidenceJson.encode(cases.getJSONObject(name))
                    )
                ) == null
            )
            val numbers = JSONObject().put("int", 1).put("double", 1.0).put("zero", -0.0)
                .put("large", 9007199254740993L).put("nested", JSONArray().put(2.0))
            val round = JSONObject(EvidenceJson.encode(numbers))
            check(round.get("int") is Int && round.get("double") is Double && round.get("large") == 9007199254740993L)
            check(
                java.lang.Double.doubleToRawLongBits(round.getDouble("zero")) == java.lang.Double.doubleToRawLongBits(
                    -0.0
                )
            )
            val state = DriverEventNoticeState()
            state.accept(JSONArray(), 0)
            val notice =
                JSONObject().put("id", UUID.randomUUID().toString()).put("source", "synthetic")
                    .put("evidence_valid", true).put("status", "evidence_ready")
                    .put("hash", "a".repeat(64))
                    .put("assessment", JSONObject().put("kind", "pothole")).put("proof", numbers)
            state.accept(JSONArray().put(notice), 1)
            val first = checkNotNull(state.current(2))
            check(first.getJSONObject("proof").get("double") is Double)
            first.getJSONObject("proof").put("double", 3.0)
            numbers.put("double", 4.0)
            check(state.current(3)!!.getJSONObject("proof").getDouble("double") == 1.0)
            val deep = JSONObject()
            var node = deep
            repeat(66) {
                val next = JSONObject()
                node.put("child", next)
                node = next
            }
            check(runCatching { EvidenceJson.copy(deep) }.isFailure)
            File(
                targetContext.filesDir,
                "evidence-json-result.json"
            ).writeText(
                JSONObject().put("pass", true).put("legacy_roundtrip_rejected", true)
                    .put("typed_copy_and_disk_roundtrip_valid", true)
                    .put("mutated_inputs_rejected", true).put("driver_copy_isolated", true)
                    .put("number_types_preserved", true).toString(2)
            )
            result.putString(
                "stream",
                "PASS: exact observation hashes survive typed copy and disk snapshot; corrupt data remains rejected.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
