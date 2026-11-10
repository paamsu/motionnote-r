package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Records production renderers with explicit synthetic fixtures. No taps, calls or dispatch. */
class ScenarioFilmProbe : Instrumentation() {
    private var tag = "v171"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        tag = arguments?.getString("tag") ?: "v171"
        require(tag.matches(Regex("v[0-9]{1,6}")))
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            data class Scene(
                val name: String,
                val type: Class<out Activity>,
                val key: String,
                val value: String,
                val expected: String
            )

            val legacyScenes = listOf(
                Scene(
                    "crash",
                    AccountDetailPreviewActivity::class.java,
                    "dossier_fixture",
                    "crash",
                    "DEMO-OWN-01"
                ),
                Scene(
                    "emergency-waiting",
                    EmergencyCasePreviewActivity::class.java,
                    "case",
                    "waiting",
                    "접수 확인 대기"
                ),
                Scene(
                    "emergency-retry",
                    EmergencyCasePreviewActivity::class.java,
                    "case",
                    "retry",
                    "전달 실패"
                ),
                Scene(
                    "emergency-mock",
                    EmergencyCasePreviewActivity::class.java,
                    "case",
                    "rescue",
                    "모의 접수 확인"
                ),
                Scene(
                    "road",
                    AccountDetailPreviewActivity::class.java,
                    "dossier_fixture",
                    "road",
                    "추가 확인"
                ),
                Scene(
                    "maintenance",
                    DiagnosticPreviewActivity::class.java,
                    "case",
                    "recovered",
                    "전압"
                )
            )
            val scenes = if (tag == "v174" || tag == "v185") listOf(
                Scene(
                    "workspace",
                    ShowcasePreviewActivity::class.java,
                    "case",
                    "workspace",
                    "현재 목록의 첫 기록"
                ),
                Scene(
                    "crash",
                    AccountDetailPreviewActivity::class.java,
                    "dossier_fixture",
                    "crash",
                    "DEMO-OWN-01"
                ),
                Scene(
                    "road",
                    AccountDetailPreviewActivity::class.java,
                    "dossier_fixture",
                    "road",
                    "기록 위치"
                ),
                Scene(
                    "road-followup",
                    ShowcasePreviewActivity::class.java,
                    "case",
                    "followup",
                    "보수 확인"
                ),
                Scene(
                    "maintenance",
                    DiagnosticPreviewActivity::class.java,
                    "case",
                    "recovered",
                    "전압"
                )
            ) else legacyScenes
            val evidence = JSONArray()
            Thread.sleep(2000)
            for (scene in scenes) {
                screen = startActivitySync(
                    Intent(targetContext, scene.type).putExtra(scene.key, scene.value)
                        .putExtra("night", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                Thread.sleep(800)
                waitForIdleSync()
                val labels = mutableListOf<String>()
                runOnMainSync {
                    fun visit(view: View) {
                        if (view is TextView && view.isShown) labels.add(view.text.toString())
                        if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    visit(checkNotNull(screen).window.decorView)
                }
                check(labels.any { it.contains(scene.expected) }) { "Scene missing: ${scene.name}" }
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "scenario-${scene.name}-$tag.png").outputStream()
                    .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                evidence.put(
                    JSONObject().put("scene", scene.name).put("fixture_key", scene.value)
                        .put("expected_label_present", true).put("synthetic", true)
                )
                Thread.sleep(4000)
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            File(
                targetContext.filesDir,
                "scenario-film-$tag.json"
            ).writeText(
                JSONObject().put("scenes", evidence).put("button_taps", false)
                    .put("real_agency_contact", false).put("real_vehicle", false).toString(2)
            )
            result.putString(
                "stream",
                "PASS: ${scenes.size} real Android renderer scenes and captured frames; synthetic fixture transitions, no automatic detection, taps, agency contact or real-vehicle claim.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
