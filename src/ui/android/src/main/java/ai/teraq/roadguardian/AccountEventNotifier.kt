package ai.teraq.roadguardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject

/** 알림 처리 진입점. 접근 권한을 다시 확인하고, 푸시로 온 문장은 화면에 쓰지 않는다 */
class AccountEventNotifier internal constructor(
    private val context: Context,
    private val store: LoginStateStore = LoginStateStore(context),
    private val lookup: (String) -> AuthenticatedEventsClient.DisplayResult<JSONObject> = {
        AuthenticatedEventsClient(
            context
        ).eventForDisplay(it)
    }
) {
    companion object {
        internal fun openIntent(context: Context, id: String) =
            Intent(context, AccountEventsActivity::class.java)
                .putExtra("notification_event_id", AccountEventRoute.checked(id))
                .setData(Uri.parse("roadguardian://account-event/${AccountEventRoute.checked(id)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    enum class Result { SHOWN, INVALID_MESSAGE, ACCESS_UNAVAILABLE, ACCOUNT_CHANGED, EXPIRED, NOTIFICATIONS_DISABLED, VEHICLE_DEVICE, OUT_OF_SCOPE }

    fun deliver(message: JSONObject): Result {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Notification verification must run on a worker" }
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return Result.VEHICLE_DEVICE
        val id = try {
            require(message.optString("type") == "event_available")
            AccountEventRoute.checked(message.optString("event_id"))
        } catch (_: IllegalArgumentException) {
            return Result.INVALID_MESSAGE
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "account_events",
                "내 차량 근거 비교",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        if (!manager.areNotificationsEnabled() || (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) || manager.getNotificationChannel("account_events").importance == NotificationManager.IMPORTANCE_NONE
        )
            return Result.NOTIFICATIONS_DISABLED
        return try {
            val account = store.read().jsonSerializeString()
            val received = lookup(id)
            require(received.value.optString("id") == id && received.value.optBoolean("evidence_valid"))
            if (!received.lease.valid(
                    System.currentTimeMillis(),
                    android.os.SystemClock.elapsedRealtime()
                )
            ) return Result.EXPIRED
            if (!EvidenceScope.canNotifyComparison(received.value)) {
                val current = store.ifCurrent(account) { manager.cancel("account-event:$id", 47) }
                return if (current) Result.OUT_OF_SCOPE else Result.ACCOUNT_CHANGED
            }
            val open = openIntent(context, id)
            val pending = PendingIntent.getActivity(
                context,
                47,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val synthetic = received.value.optString("source") == "synthetic"
            val notification = Notification.Builder(context, "account_events")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(if (synthetic) "시험 비교 자료가 준비됐습니다" else "내 차량 비교 자료가 준비됐습니다")
                .setContentText("앱에서 전후 자료와 남은 확인 사항을 확인하세요.")
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build()
            var expired = false
            val current = store.ifCurrent(account) {
                if (!received.lease.valid(
                        System.currentTimeMillis(),
                        android.os.SystemClock.elapsedRealtime()
                    )
                ) expired = true
                else manager.notify("account-event:$id", 47, notification)
            }
            when {
                !current -> Result.ACCOUNT_CHANGED
                expired -> Result.EXPIRED
                else -> Result.SHOWN
            }
        } catch (_: Exception) {
            Result.ACCESS_UNAVAILABLE
        }
    }
}
