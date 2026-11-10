package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** 화면 크기 대신 기기 종류로 시작 화면을 고른다 */
class LauncherActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val target =
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) MainActivity::class.java else OwnerActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
