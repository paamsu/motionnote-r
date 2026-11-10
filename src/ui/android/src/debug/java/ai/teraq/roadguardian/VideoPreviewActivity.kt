package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import java.io.File

/** Debug-only synthetic preview using the production display component. */
class VideoPreviewActivity : Activity() {
    private var panel: AccountVideoPanel? = null
    private var file: File? = null
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val ui = PhoneAccountUi(this)
        val body = ui.install()
        ui.heading(body, "근거 영상", "개발용 화면 · 합성 영상 재생 확인")
        val content = AccountVideoPanel(this, ui, body)
        panel = content
        body.addView(ui.button("미리보기 닫기") { finish() })
        val clip = File.createTempFile("video-preview-", ".mp4", cacheDir)
        file = clip
        assets.open("synthetic-playback-v73.mp4")
            .use { input -> clip.outputStream().use { input.copyTo(it) } }
        content.show(clip, true)
    }

    override fun onPause() {
        panel?.stop()
        super.onPause()
        finish()
    }

    override fun onDestroy() {
        panel?.stop()
        file?.delete()
        super.onDestroy()
    }
}
