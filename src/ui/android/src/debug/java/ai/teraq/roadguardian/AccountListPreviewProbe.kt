package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle

class AccountListPreviewProbe : Instrumentation() {
    private var scale = 1f
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scale = arguments?.getString("font_scale")?.toFloatOrNull()?.coerceIn(1f, 2f) ?: 1f
        start()
    }

    override fun onStart() {
        AccountListPreviewActivity.previewFontScale = scale
        startActivitySync(
            Intent(targetContext, AccountListPreviewActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )
        )
        waitForIdleSync()
        Thread.sleep(120000)
        finish(
            Activity.RESULT_OK,
            Bundle().apply {
                putString(
                    "stream",
                    "OPENED synthetic list preview; manual inspection required.\n"
                )
            })
    }
}
