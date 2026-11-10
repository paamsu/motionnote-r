package ai.teraq.roadguardian

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface

/** 차량에서는 OEM 글꼴을, 휴대폰에서는 앱에 넣은 Asta Sans(OFL)를 쓴다 */
internal object PleosTypography {
    private val family = Typeface.create("sans-serif", Typeface.NORMAL)
    private var regular = Typeface.create(family, 400, false)
    private var medium = Typeface.create(family, 500, false)
    fun initialize(context: Context) {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        regular = requireNotNull(context.resources.getFont(R.font.asta_regular))
        medium = requireNotNull(context.resources.getFont(R.font.asta_medium))
    }

    fun face(emphasis: Boolean = false) = if (emphasis) medium else regular
}
