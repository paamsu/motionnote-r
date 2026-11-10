package ai.teraq.roadguardian

import android.app.*
import android.content.Context
import android.content.Intent
import org.json.JSONObject

object ParkingReminder {
    fun show(context: Context, record: JSONObject, id: Int = 42) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "parking_cue",
                "내 차 위치 안내",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val test = record.optString("source") == "synthetic_fixture"
        val destination = Intent(context, ParkingActivity::class.java)
            .putExtra("parking_record_id", record.optString("id"))
            .setData(android.net.Uri.parse("roadguardian://parking/${record.optString("id")}"))
        val open = PendingIntent.getActivity(
            context,
            12,
            destination,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val location = "${record.optString("floor")} · ${record.optString("landmark")}"
        val parkedAt = record.optDouble("parked_at", Double.NaN)
        val savedTime = if (parkedAt.isFinite() && parkedAt > 0)
            java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREAN)
                .format(java.util.Date((parkedAt * 1000).toLong()))
        else "기록 시각 확인 필요"
        val notification = Notification.Builder(context, "parking_cue")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (test) "시험 알림 · 하강 신호 감지" else "내 차를 찾으러 가시나요?")
            .setContentText("${if (test) "가상 위치" else "저장 위치"} $location")
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("${if (test) "가상 시험 위치" else "저장 위치"}: $location\n$savedTime 주차 기록\n주차 사진으로 내 차 위치를 확인하세요.")
            )
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true)
        // 시험 데이터가 실제 주차 기록을 열지 않게 한다
        if (!test) notification.setContentIntent(open)
        manager.notify(id, notification.build())
    }
}
