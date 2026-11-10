package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import java.io.File

/** Capture unconfigured account screens only; no login, token write or control activation. */
class AccountScreenProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(BuildConfig.OIDC_ISSUER.isEmpty() && BuildConfig.AUTH_API_URL.isEmpty())
            for ((name, target) in listOf(
                "login" to LoginActivity::class.java,
                "events" to AccountEventsActivity::class.java
            )) {
                val activity = startActivitySync(
                    Intent(
                        targetContext,
                        target
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                waitForIdleSync()
                Thread.sleep(1000)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.cacheDir, "account-$name-v62.png").outputStream()
                    .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                runOnMainSync { activity.finish() }
                waitForIdleSync()
            }
            result.putString(
                "stream",
                "CAPTURED: unconfigured login and events screens. No button interaction or real login tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
