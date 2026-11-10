package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.net.Uri

/** 번호를 시스템 전화 앱에 넘긴다. 직접 전화를 걸거나 사건 정보를 보내지 않는다 */
object EmergencyContact {
    fun dialIntent(number: String): Intent {
        require(number in setOf("119", "112"))
        return Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
    }

    fun open(activity: Activity, number: String): Boolean {
        val intent = dialIntent(number)
        return try {
            activity.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
