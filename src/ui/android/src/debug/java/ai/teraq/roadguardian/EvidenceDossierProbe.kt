package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/** Renders backend-generated fixtures; not authenticated activity navigation or a tap test. */
class EvidenceDossierProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            for (kind in listOf("crash", "road", "invalid")) {
                val invalid = kind == "invalid"
                screen = startActivitySync(
                    Intent(targetContext, AccountDetailPreviewActivity::class.java)
                        .putExtra("dossier_fixture", if (invalid) "crash" else kind)
                        .putExtra("invalid_original", invalid)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                val values = mutableListOf<String>()
                val inventoryLabels = setOf("원본 자료", "관측 시각", "관측 위치", "차량 식별", "현장 영상")
                var measuredLabels = 0
                fun collect(view: View) {
                    if (view is TextView) {
                        values.add(view.text.toString())
                        if (view.isShown && view.text.toString() in inventoryLabels) {
                            check(view.width > 0 && view.height < 90 * view.resources.displayMetrics.density)
                            measuredLabels++
                        }
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(
                        view.getChildAt(
                            i
                        )
                    )
                }
                runOnMainSync { collect(screen!!.window.decorView) }
                val labels = values.joinToString("\n")
                if (invalid) {
                    check(labels.contains("원본 확인 실패") && !labels.contains("DEMO-OWN-01"))
                    runOnMainSync { screen!!.finish() }
                    screen = null
                    continue
                }
                for (expected in listOf(
                    "추가 확인",
                    "관측 시각",
                    "관측 위치",
                    "차량 식별",
                    "현장 영상",
                    "외부 제출 미실시"
                )) check(labels.contains(expected))
                check(!labels.contains("보험·접수 자료 상세"))
                check(measuredLabels == 5)
                Thread.sleep(600)
                waitForIdleSync()
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "dossier-$kind-render-v149.png").outputStream()
                    .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            result.putString(
                "stream",
                "PASS: backend-generated road/crash dossier, missing/test-only evidence and non-submission labels; legacy insurance panel absent. Renderer only; no authentication, SDK checks or taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.javaClass.simpleName}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
