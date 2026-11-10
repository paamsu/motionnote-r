package ai.teraq.roadguardian

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local synthetic evidence only. No production files, Activity or institution connection. */
class RoadAgencyReceiptProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        var checks = 0
        fun verify(ok: Boolean, label: String) {
            check(ok) { label }
            checks++
        }
        try {
            check(BuildConfig.DEBUG && BuildConfig.SYNTHETIC_BACKEND)
            val root =
                File(targetContext.cacheDir, "agency-probe-${UUID.randomUUID()}").apply { mkdirs() }
            val isolated = object : ContextWrapper(targetContext) {
                override fun getNoBackupFilesDir() = root
            }
            val events = JSONArray(
                targetContext.assets.open("agency-probe-v632.json").bufferedReader()
                    .use { it.readText() })
            val raw = events.toString()
            val bundle = RoadAgencyReceiptStore.snapshot("7632000000000001", "a".repeat(64), events)
            verify(
                bundle.getInt("observation_count") == 3 && bundle.getInt("vehicle_count") == 2,
                "three observations / two unique vehicles"
            )
            val sources = bundle.getJSONArray("sources")
            verify(sources.length() == 3 && (0..2).all {
                sources.getJSONObject(it).getString("event_id") == events.getJSONObject(it)
                    .getString("id") && sources.getJSONObject(it)
                    .getString("sensor_sha256") == events.getJSONObject(it).getString("hash")
            }, "all source identities")
            verify((0..2).all {
                val s = sources.getJSONObject(it)
                val e = events.getJSONObject(it)
                listOf("data", "video", "surround_views", "surround").all { k ->
                    (s.opt(k) ?: JSONObject.NULL).toString() == (e.opt(k)
                        ?: JSONObject.NULL).toString()
                }
            }, "location/sensor/media preserved")
            val withoutPhotos = JSONArray(events.toString())
            for (i in 0 until withoutPhotos.length()) for (key in listOf(
                "video",
                "surround_views",
                "surround"
            )) withoutPhotos.getJSONObject(i).remove(key)
            verify(
                RoadAgencyReceiptStore.snapshot(
                    "7632000000000002",
                    "a".repeat(64),
                    withoutPhotos
                ).getInt("photo_count") == 0 && bundle.getInt("photo_count") > 0,
                "photo-free bundle does not claim photos"
            )
            val receipt = RoadAgencyReceiptStore.submit(isolated, bundle)
            verify(
                receipt.getString("receipt")
                    .startsWith("MOCK-AG-") && receipt.getString("recipient") == "road-management-agency-mock" && !receipt.getBoolean(
                    "external_submission"
                ), "institution simulation identity"
            )
            val personal = RoadDemoReceiptStore.submit(isolated, events.getJSONObject(0), "report")
            verify(
                personal.getString("receipt")
                    .startsWith("MOCK-SR-") && personal.getString("recipient") != receipt.getString(
                    "recipient"
                ),
                "personal report remains separate"
            )
            verify(
                RoadAgencyReceiptStore.submit(isolated, bundle).toString() == receipt.toString(),
                "same bundle idempotence"
            )
            verify(
                RoadAgencyReceiptStore.read(isolated, bundle)?.toString() == receipt.toString(),
                "saved receipt reopen"
            )
            val file = File(root, "road-agency-receipts").listFiles()!!
                .single { it.name.startsWith("7632000000000001-") }
            val original = file.readBytes()
            File(root, "original-agency.json").writeBytes(original)
            val changed = JSONObject(bundle.toString()).put("membership_sha256", "b".repeat(64))
            val newer = RoadAgencyReceiptStore.submit(isolated, changed)
            verify(
                newer.getString("receipt") != receipt.getString("receipt") && RoadAgencyReceiptStore.read(
                    isolated,
                    bundle
                )?.getString("receipt") == receipt.getString("receipt") && file.readBytes()
                    .contentEquals(original),
                "new membership receives separate version and preserves previous bytes"
            )
            val altered = JSONObject(bundle.toString())
            altered.getJSONArray("sources").getJSONObject(0).put("sensor_sha256", "0".repeat(64))
            verify(
                RoadAgencyReceiptStore.read(isolated, altered) == null && file.readBytes()
                    .contentEquals(original), "different source hash cannot reuse old receipt"
            )
            val legacy = File(root, "road-agency-receipts/7632000000000001.json")
            legacy.writeBytes(original)
            verify(
                RoadAgencyReceiptStore.archive(isolated, "7632000000000001")
                    .count { it.getBoolean("available") } == 3 && legacy.readBytes()
                    .contentEquals(original), "legacy and both versions remain visible"
            )
            val corrupt =
                JSONObject(String(original, Charsets.UTF_8)).put("receipt", "MOCK-AG-00000000")
                    .toString().toByteArray(Charsets.UTF_8)
            file.writeBytes(corrupt)
            verify(
                RoadAgencyReceiptStore.read(
                    isolated,
                    bundle
                ) == null && runCatching {
                    RoadAgencyReceiptStore.submit(
                        isolated,
                        bundle
                    )
                }.isFailure && file.readBytes().contentEquals(corrupt),
                "corrupt receipt rejected and preserved"
            )
            verify(
                events.toString() == raw && File(root, "original-agency.json").readBytes()
                    .contentEquals(original) && RoadDemoReceiptStore.read(
                    isolated,
                    events.getJSONObject(0),
                    "report"
                )?.getString("receipt") == personal.getString("receipt"),
                "original fixtures and personal receipt preserved"
            )
            output.putString("result", "PASS $checks checks")
            output.putString("test_path", root.absolutePath)
            finish(-1, output)
        } catch (t: Throwable) {
            output.putString("failure", t.stackTraceToString())
            output.putInt("completed_checks", checks)
            finish(0, output)
        }
    }
}
