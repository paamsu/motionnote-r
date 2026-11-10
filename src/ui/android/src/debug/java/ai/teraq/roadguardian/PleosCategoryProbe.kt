package ai.teraq.roadguardian

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import java.io.File

/** Installed-emulator experiment only. This category is not a verified public SDK contract. */
class PleosCategoryProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val out = Bundle()
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        val tag = "lab-pleos-category-v446"
        fun capture(stage: String) {
            val b = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "category-$stage.png").outputStream()
                .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        try {
            check(DriverEventNotification.available(targetContext))
            capture("before")
            runOnMainSync {
                manager.createNotificationChannel(
                    NotificationChannel(
                        "lab_category_v446",
                        "알림 경로 시험",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
                val dismiss =
                    Intent(targetContext, DriverEventNotificationDismiss::class.java).setData(
                        android.net.Uri.parse("roadguardian-notice://dismiss/$tag")
                    ).putExtra("notification_tag", tag)
                val close = PendingIntent.getBroadcast(
                    targetContext,
                    446,
                    dismiss,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val graphic = DriverEventGraphic(targetContext, "pothole", true)
                graphic.measure(
                    View.MeasureSpec.makeMeasureSpec(192, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(192, View.MeasureSpec.EXACTLY)
                )
                graphic.layout(0, 0, 192, 192)
                val b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                graphic.draw(Canvas(b))
                val notice = Notification.Builder(targetContext, "lab_category_v446")
                    .setSmallIcon(R.mipmap.ic_launcher).setLargeIcon(b)
                    .setContentTitle("포트홀 감지").setContentText("기록 확보 완료")
                    .setCategory("connect.system")
                    .setVisibility(Notification.VISIBILITY_PUBLIC).setTimeoutAfter(15000)
                    .setAutoCancel(true)
                    .setContentIntent(close)
                    .addAction(Notification.Action.Builder(null, "확인", close).build()).build()
                manager.notify(tag, DriverEventNotification.NOTIFICATION_ID, notice)
                b.recycle()
            }
            Thread.sleep(1500)
            capture("visible")
            check(manager.activeNotifications.any { it.tag == tag })
            Thread.sleep(4500)
            capture("later")
            Thread.sleep(11000)
            capture("returned")
            check(manager.activeNotifications.none { it.tag == tag })
            out.putString(
                "stream",
                "PASS: experimental category posted and expired; inspect actual overlay captures separately\n"
            )
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("stream", t.stackTraceToString())
            finish(Activity.RESULT_CANCELED, out)
        } finally {
            manager.cancel(tag, DriverEventNotification.NOTIFICATION_ID)
        }
    }
}
