package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import net.openid.appauth.AuthState
import org.json.JSONObject
import java.io.File

/** Actual Android notifications with an isolated account store and injected authorization result. */
class AccountNotificationProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        val id = "12345678-1234-1234-1234-123456789abc"
        var fixtureStore: LoginStateStore? = null
        fun awaitRemoved() {
            val deadline = SystemClock.elapsedRealtime() + 1500
            while (manager.activeNotifications.any { it.tag == "account-event:$id" } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(
                25
            )
            check(manager.activeNotifications.none { it.tag == "account-event:$id" }) { "Alert did not disappear within 1.5 seconds" }
        }
        try {
            check(manager.activeNotifications.none { it.tag?.startsWith("account-event:") == true }) { "Existing account notifications must not be disturbed" }
            val originalOther = manager.activeNotifications.map { it.tag to it.id }.toSet()
            val directory = File(
                targetContext.cacheDir,
                "notification-probe-${java.util.UUID.randomUUID()}"
            ).apply { mkdirs() }
            val context = object : ContextWrapper(targetContext) {
                override fun getNoBackupFilesDir() = directory
            }
            val store = LoginStateStore(context)
            fixtureStore = store
            store.write(AuthState())
            val event =
                JSONObject().put("id", id).put("source", "synthetic").put("evidence_valid", true)
                    .put("plate", "PRIVATE-PLATE-DO-NOT-DISPLAY")
                    .put("assessment", JSONObject().put("kind", "diagnosis"))
                    .put(
                        "case_overview",
                        JSONObject().put("schema", "road-guardian.case-overview.v1")
                            .put("category", "maintenance").put("state", "comparison_available")
                    )

            fun authorized() = AuthenticatedEventsClient.DisplayResult(
                event,
                AccountDisplayLease(
                    System.currentTimeMillis() + 600000,
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime()
                )
            )

            val message = JSONObject().put("type", "event_available").put("event_id", id)
                .put("title", "PRIVATE-PUSH-TEXT")
            val notifier = AccountEventNotifier(context, store) { authorized() }
            check(
                notifier.deliver(
                    JSONObject().put("type", "other").put("event_id", id)
                ) == AccountEventNotifier.Result.INVALID_MESSAGE
            )
            check(notifier.deliver(message) == AccountEventNotifier.Result.SHOWN)
            check(notifier.deliver(message) == AccountEventNotifier.Result.SHOWN)
            val shown = manager.activeNotifications.filter { it.tag == "account-event:$id" }
            check(shown.size == 1)
            val notification = shown.single().notification
            val content =
                notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() + "\n" +
                        notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            check(content.contains("시험 비교 자료"))
            check(!content.contains("PRIVATE-PLATE") && !content.contains("PRIVATE-PUSH-TEXT"))
            check(notification.visibility == Notification.VISIBILITY_PRIVATE)
            if (android.os.Build.VERSION.SDK_INT >= 31) check(notification.contentIntent.isImmutable)
            val route = AccountEventNotifier.openIntent(targetContext, id)
            check(route.extras?.keySet() == setOf("notification_event_id"))
            screen = startActivitySync(route)
            runOnMainSync {
                val field = AccountEventsActivity::class.java.getDeclaredField("selectedEventId")
                    .apply { isAccessible = true }
                check(field.get(screen) == id)
            }
            runOnMainSync { screen!!.finish() }
            screen = null
            store.write(AuthState())
            awaitRemoved()
            val legacyEvent =
                JSONObject(event.toString()).put("assessment", JSONObject().put("kind", "parking"))
            val excluded = AccountEventNotifier(context, store) {
                AuthenticatedEventsClient.DisplayResult(
                    legacyEvent,
                    authorized().lease
                )
            }
            check(excluded.deliver(message) == AccountEventNotifier.Result.OUT_OF_SCOPE)
            awaitRemoved()
            val changed = AccountEventNotifier(context, store) {
                store.write(AuthState.jsonDeserialize("{\"refreshToken\":\"SYNTHETIC-REPLACED-SESSION\"}"))
                authorized()
            }
            check(changed.deliver(message) == AccountEventNotifier.Result.ACCOUNT_CHANGED)
            val expired = AccountEventNotifier(context, store) {
                AuthenticatedEventsClient.DisplayResult(
                    event,
                    AccountDisplayLease(
                        System.currentTimeMillis() + 10000,
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime()
                    )
                )
            }
            check(expired.deliver(message) == AccountEventNotifier.Result.EXPIRED)
            val denied = AccountEventNotifier(context, store) {
                throw AccountAccessException(AccountAccessException.Reason.NOT_FOUND)
            }
            check(denied.deliver(message) == AccountEventNotifier.Result.ACCESS_UNAVAILABLE)
            check(manager.activeNotifications.none { it.tag?.startsWith("account-event:") == true })
            check(manager.activeNotifications.map { it.tag to it.id }.toSet() == originalOther)
            result.putString(
                "stream",
                "PASS: real generic private Android notification deduplicates by event; push prose/plate excluded; account event route selected; legacy parking excluded; account replacement, expiry and denied lookup suppress delivery; account write clears alert and other notifications remain. Isolated encrypted store with injected lookup; no real OIDC, provider push, tap or authorized HTTPS request tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            fixtureStore?.write(AuthState())
        }
    }
}
