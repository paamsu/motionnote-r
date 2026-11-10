package ai.teraq.roadguardian

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import org.json.JSONObject

/** Activity를 띄우지 않고 시스템 알림만 올린다. 헤드업 표시는 OEM 정책을 따른다 */
object DriverEventNotification {
    const val CHANNEL = "driver_event_brief"
    const val NOTIFICATION_ID = 349
    fun identity(event: JSONObject): String? {
        if (!DriverEventNoticeState.isEligible(event)) return null
        val post =
            event.optJSONObject("repair")?.optJSONObject("post_repair")?.optString("hash").orEmpty()
        return event.getString("id") + ":" + event.getString("hash") + ":" + post + DriverEventNoticeState.helpIdentitySuffix(
            event
        ) + WantedAutoNotice.suffix(event) + TrafficZoneAutoNotice.suffix(event)
    }

    private val published = mutableSetOf<String>()
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    @Synchronized
    fun show(context: Context, event: JSONObject): Boolean {
        val key = identity(event) ?: return false
        if (key in published) return true
        if (!available(context)) return false
        val brief = DriverBriefPanel.verifiedBrief(event) ?: return false
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "차량 이벤트 안내",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "현재 화면 위에 사건과 기록 상태를 짧게 표시" })
        if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
        val dismiss = Intent(context, DriverEventNotificationDismiss::class.java)
            .setData(
                android.net.Uri.Builder().scheme("roadguardian-notice").authority("dismiss")
                    .appendPath(key).build()
            )
            .putExtra("notification_tag", key)
        val close = PendingIntent.getBroadcast(
            context,
            0,
            dismiss,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (brief.second.title) {
            "포트홀 감지" -> "센서 기록 확보"
            "주차 중 충격 기록" -> "충격 기록 보관 · 정차 후 확인"
            "응답 시간 초과", "112 도움 요청", "119 도움 요청" -> when (DriverEventNoticeState.helpDeliveryState(
                event
            )) {
                "acknowledged" -> "도움 요청 접수 완료 · 기록 보관"
                "retry" -> "전달 확인 필요 · 기록 보관"
                else -> "도움 요청 확인 중 · 기록 보관"
            }

            "사고 감지" -> "정차 후 보험사 연결 안내"
            "저전압 확인" -> "정차 후 근처 서비스센터 안내"
            "저전압 다시 감지" -> "서비스센터 방문을 추천합니다"
            "전방 시야 저하" -> "속도를 줄이고 거리를 확보하세요"
            else -> brief.second.next
        }
        val graphic = DriverEventGraphic(context, brief.first, true, brief.second.voltageLow)
        graphic.measure(
            View.MeasureSpec.makeMeasureSpec(192, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(192, View.MeasureSpec.EXACTLY)
        )
        graphic.layout(0, 0, 192, 192)
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        graphic.draw(Canvas(bitmap))
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(bitmap).setContentTitle(brief.second.title).setContentText(text)
            // 설치된 Pleos 에뮬레이터에서 확인한 경로. 공개 SDK가 보장하는 동작은 아니다
            // 시연 빌드에서만 켠다. 일반 빌드는 표준 상태를 쓴다
            .setCategory(if ((BuildConfig.DEBUG || BuildConfig.CONTEST_DEMO) && BuildConfig.SYNTHETIC_BACKEND && BuildConfig.PLEOS_DEMO_HEADS_UP) "connect.system" else Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setTimeoutAfter(15_000).setAutoCancel(true)
            .setContentIntent(close)
            .addAction(Notification.Action.Builder(null, "확인", close).build()).build()
        return try {
            manager.notify(key, NOTIFICATION_ID, notification)
            published.add(key)
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            bitmap.recycle()
        }
    }
}

class DriverEventNotificationDismiss : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("notification_tag")?.let {
            context.getSystemService(NotificationManager::class.java)
                .cancel(it, DriverEventNotification.NOTIFICATION_ID)
        }
    }
}
