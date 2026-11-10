package ai.teraq.roadguardian

import android.content.Context
import android.graphics.*
import android.view.View
import java.util.Locale
import kotlin.math.*

/** 벡터로 그린 안내 그림. 화면 낭독기용 설명을 함께 단다 */
class CaseFlowGraphic(
    context: Context,
    private val steps: List<Step>,
    private val light: Boolean,
    private val uiScale: Float = 1f
) : View(context) {
    data class Step(val label: String, val status: String, val state: Int)
    companion object {
        const val WAITING = 0
        const val DONE = 1
        const val ACTIVE = 2
        const val BLOCKED = 3
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = steps.joinToString(". ") { "${it.label}: ${it.status}" }
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dp(n: Float) = n * resources.displayMetrics.density * uiScale
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ink = PleosPalette.ink(light)
        val muted = PleosPalette.muted(light)
        val blue = PleosPalette.action(light)
        val amber = Color.parseColor(if (light) "#805000" else "#FFC777")
        val spacing = width.toFloat() / steps.size
        val y = dp(32f)
        val radius = dp(20f)
        paint.strokeWidth = dp(1f)
        paint.color = Color.parseColor(if (light) "#D2DEE7" else "#343B43")
        canvas.drawLine(spacing / 2, y, width - spacing / 2, y, paint)
        for ((i, step) in steps.withIndex()) {
            val x = spacing * (i + .5f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = when (step.state) {
                DONE, ACTIVE -> blue
                BLOCKED -> amber
                else -> Color.parseColor(if (light) "#DFE6EC" else "#252B32")
            }
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.FILL
            paint.color = when (step.state) {
                DONE, ACTIVE -> blue
                BLOCKED -> amber
                else -> muted
            }
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(20f)
            paint.typeface = PleosTypography.face(true)
            if (step.state == DONE) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2f)
                paint.strokeCap = Paint.Cap.ROUND
                val tick = Path().apply {
                    moveTo(x - dp(8f), y)
                    lineTo(x - dp(2f), y + dp(6f))
                    lineTo(x + dp(9f), y - dp(7f))
                }
                canvas.drawPath(tick, paint)
                paint.style = Paint.Style.FILL
            } else canvas.drawText(
                if (step.state == BLOCKED) "!" else "${i + 1}",
                x,
                y + dp(7f),
                paint
            )
            paint.color = ink
            paint.textSize = 15f * resources.displayMetrics.scaledDensity * uiScale
            canvas.drawText(step.label, x, dp(77f), paint)
            paint.color = muted
            paint.textSize = 13f * resources.displayMetrics.scaledDensity * uiScale
            canvas.drawText(step.status, x, dp(101f), paint)
        }
    }
}

class VoltageComparisonGraphic(
    context: Context,
    private val before: Double?,
    private val baseline: Double?,
    private val after: Double?,
    private val recovered: Boolean,
    private val light: Boolean,
    private val uiScale: Float = 1f
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(n: Float) = n * resources.displayMetrics.density * uiScale
    private fun number(value: Double?) =
        value?.let { String.format(Locale.KOREAN, "%.2f V", it) } ?: "미확인"

    init {
        contentDescription =
            "같은 조건의 전압 비교. 이전 측정 ${number(before)}. 기준 ${number(baseline)}. 재측정 ${number(after)}. 합성 시험 자료."
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ink = PleosPalette.ink(light)
        val muted = PleosPalette.muted(light)
        val amber = Color.parseColor(if (light) "#A46617" else "#FFC777")
        val blue = PleosPalette.action(light)
        val green = PleosPalette.action(light)
        val valid = listOfNotNull(before, baseline, after).filter { it.isFinite() }
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = PleosTypography.face(true)
        paint.color = ink
        paint.textSize = 16f * resources.displayMetrics.scaledDensity * uiScale
        canvas.drawText("같은 조건의 전압 비교", dp(12f), dp(24f), paint)
        if (valid.isEmpty()) {
            paint.color = muted
            canvas.drawText("비교할 수치 없음", dp(12f), dp(90f), paint)
            return
        }
        val low = min(0.0, floor((valid.minOrNull() ?: 0.0) / 2) * 2)
        val high = max(16.0, ceil((valid.maxOrNull() ?: 16.0) / 2) * 2)
        val left = dp(48f)
        val right = width - dp(14f)
        val top = dp(80f)
        val bottom = height - dp(34f)
        fun y(value: Double) = bottom - ((value - low) / (high - low) * (bottom - top)).toFloat()
        paint.typeface = PleosTypography.face()
        paint.textSize = 11f * resources.displayMetrics.scaledDensity * uiScale
        for (value in listOf(low, high)) {
            paint.color = muted
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${value.toInt()} V", left - dp(8f), y(value) + dp(4f), paint)
            paint.color = PleosPalette.line(light)
            paint.strokeWidth = dp(1f)
            canvas.drawLine(left, y(value), right, y(value), paint)
        }
        val span = right - left
        val positions = listOf(left + span * .26f, left + span * .74f)
        for ((i, value) in listOf(before, after).withIndex()) {
            val x = positions[i]
            paint.textAlign = Paint.Align.CENTER
            if (value != null && value.isFinite()) {
                paint.color = if (i == 0 || !recovered) amber else green
                val bar = RectF(
                    x - span * .14f,
                    min(y(value), y(0.0)),
                    x + span * .14f,
                    max(y(value), y(0.0))
                )
                canvas.drawRoundRect(bar, dp(2f), dp(2f), paint)
                paint.color = ink
                paint.textSize = 19f * resources.displayMetrics.scaledDensity * uiScale
                canvas.drawText(number(value), x, top - dp(9f), paint)
            } else {
                paint.color = muted
                paint.textSize = 16f * resources.displayMetrics.scaledDensity * uiScale
                canvas.drawText("미측정", x, bottom - dp(16f), paint)
            }
            paint.color = muted
            paint.textSize = 14f * resources.displayMetrics.scaledDensity * uiScale
            canvas.drawText(if (i == 0) "이전 측정" else "재측정", x, height - dp(9f), paint)
        }
        if (baseline != null && baseline.isFinite()) {
            paint.color = blue
            paint.strokeWidth = dp(2f)
            paint.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
            canvas.drawLine(left, y(baseline), right, y(baseline), paint)
            paint.pathEffect = null
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = 12f * resources.displayMetrics.scaledDensity * uiScale
            canvas.drawText("기준 ${number(baseline)}", right, dp(44f), paint)
        }
    }
}

class VehicleImpactGraphic(
    context: Context,
    private val area: String,
    private val synthetic: Boolean,
    private val light: Boolean
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(n: Float) = n * resources.displayMetrics.density

    init {
        contentDescription =
            "차량 위에서 본 도식. 충격 위치 $area. ${if (synthetic) "가상 설정" else "서버에 기록된 위치"}. 접촉과 책임 미확정."
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ink = PleosPalette.ink(light)
        val muted = PleosPalette.muted(light)
        val cx = width * .30f
        val top = dp(30f)
        val bottom = height - dp(20f)
        val half = dp(36f)
        paint.color = Color.parseColor(if (light) "#E3E8EB" else "#20272E")
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(cx - half, top, cx + half, bottom), dp(24f), dp(24f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = Color.parseColor(if (light) "#617B83" else "#91B7BD")
        canvas.drawRoundRect(RectF(cx - half, top, cx + half, bottom), dp(24f), dp(24f), paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor(if (light) "#7B999F" else "#91B7BD")
        canvas.drawRoundRect(
            RectF(
                cx - half + dp(10f),
                top + dp(32f),
                cx + half - dp(10f),
                top + dp(58f)
            ), dp(5f), dp(5f), paint
        )
        canvas.drawRoundRect(
            RectF(
                cx - half + dp(10f),
                bottom - dp(42f),
                cx + half - dp(10f),
                bottom - dp(24f)
            ), dp(5f), dp(5f), paint
        )
        paint.color = muted
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        canvas.drawText("전면", cx, dp(17f), paint)
        val known = area.contains("전면") || area.contains("후") || area.contains("측")
        if (known) {
            val x = if (area.contains("우")) cx + half else if (area.contains("좌")) cx - half else cx
            val y =
                if (area.contains("전면")) top + dp(12f) else if (area.contains("후")) bottom - dp(12f) else (top + bottom) / 2
            paint.color = Color.parseColor(if (light) "#A46617" else "#FFC777")
            paint.alpha = 65
            canvas.drawCircle(x, y, dp(23f), paint)
            paint.alpha = 255
            canvas.drawCircle(x, y, dp(10f), paint)
            paint.strokeWidth = dp(2f)
            canvas.drawLine(x, y, width * .53f, y, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        paint.color = ink
        paint.typeface = PleosTypography.face(true)
        paint.textSize = 21f * resources.displayMetrics.scaledDensity
        canvas.drawText(area, width * .56f, dp(74f), paint)
        paint.color = muted
        paint.textSize = 14f * resources.displayMetrics.scaledDensity
        canvas.drawText(if (synthetic) "가상 충격 위치" else "기록된 충격 위치", width * .56f, dp(101f), paint)
        canvas.drawText("접촉·책임 미확정", width * .56f, dp(126f), paint)
    }
}
