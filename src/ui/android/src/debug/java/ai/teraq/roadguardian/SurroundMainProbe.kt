package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Main with isolated local persisted synthetic atlas; no direct taps. */
class SurroundMainProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9470")
            val events = JSONArray(client.request("/api/events"))
            val records = (0 until events.length()).map { events.getJSONObject(it) }
            val attached = records.first {
                it.optJSONObject("surround")?.optBoolean("integrity_valid") == true
            }
            val missing = records.first { it.optJSONObject("surround") == null }
            val corrupt = records.first {
                it.optJSONObject("surround")?.optBoolean("integrity_valid") == false
            }
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = screen
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            fun method(n: String) =
                MainActivity::class.java.getDeclaredMethod(n).apply { isAccessible = true }

            fun labels(): List<String> {
                val out = mutableListOf<String>()
                fun visit(v: View) {
                    if (v is TextView && v.isShown) out.add(v.text.toString())
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return out
            }

            fun await(checker: () -> Boolean) {
                repeat(200) {
                    var done = false
                    runOnMainSync { done = checker() }
                    if (done) return
                    Thread.sleep(50)
                }
                var debug = emptyList<String>()
                runOnMainSync { debug = labels() }
                error("Main state timeout: $debug")
            }
            await { !field("busy").getBoolean(a) && (field("uxMonitor").get(a) as? VehicleUxMonitor)?.restricted == false }
            runOnMainSync {
                (field("timer").get(a) as android.os.Handler).removeCallbacks(
                    field("feedPoll").get(
                        a
                    ) as Runnable
                )
                field("client").set(a, client)
                field("selected").set(a, "기록")
            }
            val report = JSONArray()
            val invalidMissing = JSONObject(missing.toString()).put("evidence_valid", false)
            val invalidAttached = JSONObject(attached.toString()).put("evidence_valid", false)
            for ((name, event) in listOf(
                "saved" to attached,
                "missing" to missing,
                "corrupt" to corrupt,
                "invalid-missing" to invalidMissing,
                "invalid-attached" to invalidAttached,
                "reopened" to attached
            )) {
                runOnMainSync {
                    field("opened").set(a, event)
                    method("render").invoke(a)
                    if (name in listOf("saved", "reopened")) check("센서 기록 보관 · 사진 확인 중" in labels())
                }
                val expected = when (name) {
                    "invalid-missing", "invalid-attached" -> "기록 확인 필요"
                    "missing" -> "센서 기록 보관 · 사진 미첨부"
                    "corrupt" -> "센서 기록 보관 · 사진 확인 실패"
                    else -> "센서·합성 이미지 보관 완료"
                }
                await { expected in labels() }
                waitForIdleSync()
                Thread.sleep(900)
                runOnMainSync {
                    val texts = labels()
                    if (name.startsWith("invalid")) check(texts.none {
                        it.contains("센서 기록 보관") || it.contains(
                            "센서·합성 이미지 보관 완료"
                        )
                    })
                    if (name in listOf("missing", "corrupt")) check("센서·합성 이미지 보관 완료" !in texts)
                    report.put(
                        JSONObject().put("case", name).put("event_id", event.getString("id"))
                            .put("visible_texts", JSONArray(texts))
                    )
                }
                checkNotNull(uiAutomation.takeScreenshot()).let { b ->
                    File(targetContext.filesDir, "surround-$name-v224.png").outputStream()
                        .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    b.recycle()
                }
            }
            // Switch away while an image transfer may still be pending; old success must not replace the new event.
            runOnMainSync {
                field("opened").set(a, attached)
                method("render").invoke(a)
                field("opened").set(a, missing)
                method("render").invoke(a)
            }
            Thread.sleep(1500)
            runOnMainSync { check("센서 기록 보관 · 사진 미첨부" in labels() && "센서·합성 이미지 보관 완료" !in labels()) }
            runOnMainSync {
                field("opened").set(a, attached)
                method("render").invoke(a)
                callActivityOnPause(a)
            }
            Thread.sleep(1500)
            runOnMainSync {
                check("센서·합성 이미지 보관 완료" !in labels())
                callActivityOnResume(a)
            }
            await { "센서·합성 이미지 보관 완료" in labels() }
            val zip = File(targetContext.filesDir, "surround-export-v224.zip")
            zip.writeBytes(client.evidencePackage(attached.getString("id")))
            val verified = EvidencePackageVerifier.verify(zip, attached.getString("id"))
            check(verified.files >= 5)
            File(
                targetContext.filesDir,
                "surround-main-v224.json"
            ).writeText(
                JSONObject().put("states", report).put("switch_discards_previous", true)
                    .put("pause_discards_previous", true).put("archive_verified", true).put(
                    "scope",
                    "synthetic persisted atlas and Main methods; no direct taps, camera acquisition or road inference"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: persisted atlas, valid missing/corrupt, invalid attached/missing, loading/reopen, event switch, pause/resume and Android ZIP verifier.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
