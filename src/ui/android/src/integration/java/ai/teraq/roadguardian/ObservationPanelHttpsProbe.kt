package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import java.io.File

/** Reads the actual panel from HTTPS, then verifies account change removes private values. No taps. */
class ObservationPanelHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            val fixture = IntegrationIdentity.install(targetContext, "read_only_token")
            val car =
                targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)

            fun labels(): String {
                val list = mutableListOf<String>()
                runOnMainSync {
                    fun collect(view: View) {
                        if (view is TextView) list.add(view.text.toString())
                        view.contentDescription?.let { list.add(it.toString()) }
                        if (view is ViewGroup) for (i in 0 until view.childCount) collect(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    collect(screen!!.window.decorView)
                }
                return list.joinToString("\n")
            }

            fun await(value: String) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 20000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (labels().contains(value)) return
                    Thread.sleep(50)
                }
                error("Expected observation UI: $value; labels=${labels()}")
            }
            if (!car) {
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        AccountEventsActivity::class.java
                    ).putExtra("notification_event_id", fixture.getString("event_id"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                await("전송 측 표시")
                check(labels().contains("기어 P"))
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            screen = startActivitySync(
                Intent(
                    targetContext,
                    ObservationPanelPreviewActivity::class.java
                ).putExtra("event_id", fixture.getString("event_id"))
                    .putExtra("night", car)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            await("전송 측 표시")
            val shown = labels()
            check(shown.contains("기어 P") && shown.contains("전송 측 표시") && shown.contains("원본·ZIP은 바뀌지 않습니다"))
            check(!shown.contains("현재 차량 관측 확인") && !shown.contains("이 사건에 추가 관측 저장"))
            fun presentedBounds(): android.graphics.Rect? {
                var found: android.graphics.Rect? = null
                runOnMainSync {
                    fun find(view: View) {
                        if (view is TextView && view.text.toString().startsWith("서버 보관 ")) {
                            val bounds = android.graphics.Rect()
                            if (!view.getGlobalVisibleRect(bounds) || bounds.isEmpty) return
                            val screenAt = IntArray(2)
                            val windowAt = IntArray(2)
                            view.getLocationOnScreen(screenAt)
                            view.getLocationInWindow(windowAt)
                            bounds.offset(screenAt[0] - windowAt[0], screenAt[1] - windowAt[1])
                            found = bounds
                        }
                        if (view is ViewGroup) for (i in 0 until view.childCount) find(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    find(screen!!.window.decorView)
                }
                return found
            }

            var rendered: Bitmap? = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val bounds = presentedBounds()
                if (bounds == null) {
                    Thread.sleep(50)
                    continue
                }
                val candidate = checkNotNull(uiAutomation.takeScreenshot())
                val colors = mutableSetOf<Int>()
                val left = bounds.left.coerceIn(0, candidate.width)
                val right = bounds.right.coerceIn(left, candidate.width)
                val top = bounds.top.coerceIn(0, candidate.height)
                val bottom = bounds.bottom.coerceIn(top, candidate.height)
                for (y in top until bottom step ((bottom - top) / 40).coerceAtLeast(1)) for (x in left until right step ((right - left) / 160).coerceAtLeast(
                    1
                )) colors.add(candidate.getPixel(x, y))
                if (colors.size > 4) {
                    rendered = candidate
                    break
                }
                candidate.recycle()
                Thread.sleep(50)
            }
            val bitmap =
                checkNotNull(rendered) { "Stored observation data not presented on display" }
            File(targetContext.filesDir, "sdk-observation-panel-v156.png").outputStream()
                .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
            await("계정 변경으로 관측을 화면에서 지웠습니다")
            check(!labels().contains("기어 P") && !labels().contains("보관한 관측"))
            result.putString(
                "stream",
                "PASS: actual HTTPS private observation panel, recorded P and missing speed, read-only controls, account-change clearing. Integration renderer only; no production vehicle navigation, capture preview or save tap.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
        }
    }
}
