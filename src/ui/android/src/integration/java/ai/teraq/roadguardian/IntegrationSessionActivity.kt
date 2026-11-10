package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import net.openid.appauth.AuthState

/** Test-only lifecycle entry; MainActivity still enforces current SDK PARK and OEM UX restrictions. */
class IntegrationSessionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        openSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSession()
    }

    private fun openSession() {
        check(packageName == "ai.teraq.roadguardian.integration")
        if (intent.getBooleanExtra("end_session", false)) LoginStateStore(this).write(AuthState())
        else {
            val fixture = IntegrationIdentity.install(
                this,
                intent.getStringExtra("test_token_key") ?: "token"
            )
            java.io.File(filesDir, "session-prepared-v147.json").writeText(
                org.json.JSONObject()
                    .put("source", "synthetic").put("prepared_at", System.currentTimeMillis())
                    .put("expires_at", fixture.getLong("expires_at"))
                    .put("synthetic_backend", false).toString(2)
            )
        }
        val photoEvent =
            intent.getStringExtra("photo_event_id")?.let { AccountEventRoute.checked(it) }
        val destination = if (photoEvent != null && !intent.getBooleanExtra("end_session", false))
            Intent(this, AccountEventsActivity::class.java).putExtra(
                "notification_event_id",
                photoEvent
            )
        else Intent(this, MainActivity::class.java)
        startActivity(destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
