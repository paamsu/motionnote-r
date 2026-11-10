package ai.teraq.roadguardian

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

/** 휴대폰 전용 개인 영상 재생. 토큰과 원격 주소는 플레이어에 넘기지 않는다 */
class AccountVideoActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var closing = false
    private var file: File? = null
    private var panel: AccountVideoPanel? = null
    private var displayLease: AccountDisplayLease? = null
    private val timer = android.os.Handler(android.os.Looper.getMainLooper())
    private val checkExpiry = object : Runnable {
        override fun run() {
            if (closing) return
            if (displayLease?.valid(
                    System.currentTimeMillis(),
                    android.os.SystemClock.elapsedRealtime()
                ) != true
            ) {
                expireVideo()
                return
            }
            timer.postDelayed(this, 1000)
        }
    }

    private fun expireVideo() {
        closing = true
        displayLease = null
        timer.removeCallbacks(checkExpiry)
        panel?.fail("로그인 시간이 만료되어 영상을 닫았습니다. 계정을 다시 연결해 주세요.")
        file?.delete()
        file = null
        worker.shutdownNow()
    }

    private fun acceptDownload(download: AuthenticatedEventsClient.DisplayResult<AuthenticatedEventsClient.Video>) {
        if (closing || isDestroyed) {
            download.value.file.delete()
            return
        }
        file = download.value.file
        displayLease = download.lease
        if (!download.lease.valid(
                System.currentTimeMillis(),
                android.os.SystemClock.elapsedRealtime()
            )
        ) {
            expireVideo()
            return
        }
        panel?.show(download.value.file, download.value.synthetic)
        timer.removeCallbacks(checkExpiry)
        timer.post(checkExpiry)
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val id = intent.getStringExtra("event_id") ?: return finish()
        val ui = PhoneAccountUi(this)
        val body = ui.install()
        ui.heading(body, "근거 영상", "접근 권한과 영상 파일을 확인합니다.")
        val content = AccountVideoPanel(this, ui, body)
        panel = content
        body.addView(ui.button("사건 기록으로 돌아가기") { finish() })
        worker.execute {
            val result = runCatching { AuthenticatedEventsClient(this).videoForDisplay(id) }
            runOnUiThread {
                if (closing || isDestroyed) {
                    result.getOrNull()?.value?.file?.delete()
                    return@runOnUiThread
                }
                result.fold(onSuccess = { download ->
                    acceptDownload(download)
                }, onFailure = { error ->
                    val issue = error as? AccountAccessException ?: AccountAccessException(
                        AccountAccessException.Reason.UNAVAILABLE
                    )
                    content.fail(issue.message ?: "영상을 확인하지 못했습니다.")
                })
            }
        }
    }

    override fun onPause() {
        timer.removeCallbacksAndMessages(null)
        displayLease = null
        closing = true
        panel?.stop()
        file?.delete()
        file = null
        worker.shutdownNow()
        super.onPause()
        finish()
    }

    override fun onDestroy() {
        closing = true
        timer.removeCallbacksAndMessages(null)
        displayLease = null
        panel?.stop()
        file?.delete()
        worker.shutdownNow()
        super.onDestroy()
    }
}
