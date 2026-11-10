package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.util.Locale
import kotlin.math.min

/** 지난 값만 보여 준다. 저장된 관측을 현재 차량 상태로 표시하지 않는다 */
class ObservationSignalGraphic(
    context: Context,
    private val sample: SdkObservationRecord.Sample,
    private val light: Boolean
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gear = when (sample.gear) {
        "GEAR_PARK" -> "P"
        "GEAR_REVERSE" -> "R"
        "GEAR_NEUTRAL" -> "N"
        "GEAR_DRIVE" -> "D"
        else -> "—"
    }
    private val speed = sample.speed?.let { String.format(Locale.KOREAN, "%.1f", it) } ?: "—"

    init {
        contentDescription =
            "기록 당시 관측. 기어 ${if (gear == "—") "미확인" else gear}. 속도 ${if (speed == "—") "미확인" else "$speed km/h"}. 현재 차량 상태가 아닙니다."
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / 1000f, height / 180f)
        canvas.save()
        canvas.translate((width - 1000 * scale) / 2, (height - 180 * scale) / 2)
        canvas.scale(scale, scale)
        val ink = Color.parseColor(if (light) "#172B3A" else "#F5F5F5")
        val muted = Color.parseColor(if (light) "#526879" else "#BDBDBD")
        val accent = PleosPalette.action(light)
        fun text(
            value: String,
            x: Float,
            y: Float,
            size: Float,
            color: Int = ink,
            bold: Boolean = false
        ) {
            paint.color = color
            paint.textSize = size
            paint.typeface = PleosTypography.face(bold)
            canvas.drawText(value, x, y, paint)
        }
        text("기록 당시 기어", 24f, 30f, 21f, muted)
        text(gear, 24f, 122f, 82f, accent, true)
        text("기록 당시 속도", 520f, 30f, 21f, muted)
        text(speed, 520f, 122f, 70f, ink, true)
        text(if (sample.speed == null) "미확인" else "km/h", 825f, 122f, 23f, muted)
        paint.color = Color.parseColor(if (light) "#DEDEDE" else "#3B434B")
        paint.strokeWidth = 1.5f
        canvas.drawLine(470f, 15f, 470f, 145f, paint)
        canvas.restore()
    }
}
