package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import java.io.File

/** Real local video preparation and expiry timer; download result is a synthetic fixture. */
class AccountVideoExpiryProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun field(owner: Class<*>, name: String) =
        owner.getDeclaredField(name).apply { isAccessible = true }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            check(BuildConfig.OIDC_ISSUER.isBlank()) { "Probe requires unconfigured test account" }
            val screen = startActivitySync(
                Intent(targetContext, AccountVideoActivity::class.java)
                    .putExtra("event_id", "12345678-1234-1234-1234-123456789abc")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            activity = screen
            // Wait for the expected unconfigured request to settle before supplying the local fixture.
            Thread.sleep(500)
            waitForIdleSync()
            val accept = AccountVideoActivity::class.java.getDeclaredMethod(
                "acceptDownload",
                AuthenticatedEventsClient.DisplayResult::class.java
            ).apply { isAccessible = true }

            fun clip(): File =
                File.createTempFile("expiry-fixture-", ".mp4", targetContext.cacheDir)
                    .also { file ->
                        targetContext.assets.open("synthetic-playback-v73.mp4")
                            .use { input -> file.outputStream().use { input.copyTo(it) } }
                    }

            val video = clip()
            val lease = AccountDisplayLease(
                System.currentTimeMillis() + 34000,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime()
            )
            runOnMainSync {
                accept.invoke(
                    screen,
                    AuthenticatedEventsClient.DisplayResult(
                        AuthenticatedEventsClient.Video(
                            video,
                            true
                        ), lease
                    )
                )
            }
            val panel =
                field(AccountVideoActivity::class.java, "panel").get(screen) as AccountVideoPanel
            val deadline = SystemClock.elapsedRealtime() + 2500
            var prepared = false
            while (!prepared && SystemClock.elapsedRealtime() < deadline) {
                runOnMainSync {
                    prepared = field(AccountVideoPanel::class.java, "ready").getBoolean(panel)
                }
                if (!prepared) Thread.sleep(50)
            }
            check(prepared) { "Synthetic video did not prepare before expiry" }
            runOnMainSync {
                val player = field(
                    AccountVideoPanel::class.java,
                    "player"
                ).get(panel) as android.media.MediaPlayer
                player.start()
                check(player.isPlaying) { "Synthetic playback did not start" }
            }
            Thread.sleep(4500)
            waitForIdleSync()
            runOnMainSync {
                check(!field(AccountVideoPanel::class.java, "ready").getBoolean(panel))
                check(field(AccountVideoPanel::class.java, "player").get(panel) == null)
                check(
                    (field(
                        AccountVideoPanel::class.java,
                        "frame"
                    ).get(panel) as View).visibility == View.GONE
                )
                check(
                    (field(
                        AccountVideoPanel::class.java,
                        "play"
                    ).get(panel) as View).visibility == View.GONE
                )
                check(
                    (field(
                        AccountVideoPanel::class.java,
                        "status"
                    ).get(panel) as TextView).text.contains("로그인 시간이 만료")
                )
                check(field(AccountVideoActivity::class.java, "file").get(screen) == null)
            }
            check(!video.exists()) { "Expired private cache retained" }
            val late = clip()
            runOnMainSync {
                accept.invoke(
                    screen,
                    AuthenticatedEventsClient.DisplayResult(
                        AuthenticatedEventsClient.Video(late, true),
                        lease
                    )
                )
            }
            check(!late.exists()) { "Late download not discarded" }
            result.putString(
                "stream",
                "PASS: local synthetic video prepared and MediaPlayer played; actual timer stopped panel, hid frame/controls, cleared file and rejected late download. Playback UI input, real authentication/network not tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
