package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** 사건 종류를 나타내는 고정 그림. 애니메이션이나 사진 근거는 넣지 않는다 */
class DriverEventGraphic(
    context: Context,
    private var kind: String,
    private val light: Boolean,
    private val voltageLow: Boolean = false
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = false
        isFocusable = false
        contentDescription = when (kind) {
            "visibility_change" -> "전방 밝기 변화 · 원인 확인 중"
            "washer" -> "워셔 사용 기록 그래픽"
            "speed_bump" -> "방지턱 후보 그래픽"
            "record_saved" -> "기록 저장 완료 표시"
            "timeline_waiting" -> "응답 대기 시계"
            "timeline_responded" -> "응답 확인 표시"
            "timeline_cancelled" -> "요청 취소 표시"
            "pothole" -> "도로 충격 기록 그래픽"
            "crash" -> "사고 기록 그래픽"
            "review" -> "입력 확인 그래픽"
            "violation" -> "구역 관측 그래픽"
            "reckless" -> "주행 변화 그래픽"
            "wanted" -> "시험 번호 대조 그래픽"
            "observation" -> "측정 기록 그래픽"
            "rain" -> "시야 저하 그래픽"
            "help_112" -> "112 도움 요청"
            "help_119" -> "119 도움 요청"
            "timeline_timeout" -> "운전자 응답 시간 초과"
            "voltage_low" -> "저전압 경고"
            "voltage_repeat" -> "저전압 반복 경고"
            "voltage_normal" -> "전압 이상 없음"
            "voltage_recovered" -> "재측정 전압 회복"
            "emergency", "manual_help" -> "도움 요청 그래픽"
            else -> "전압 측정 그래픽"
        }
    }

    fun showCompletion() {
        kind = "record_saved"
        contentDescription = "처리 완료"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        val scale = minOf(width / 240f, height / 170f)
        canvas.scale(scale, scale)
        paint.color = PleosPalette.ink(light)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (kind) {
            "visibility_change" -> {
                // 앞유리와 바뀌는 조명을 크게 그리고, 시계는 작은 상태 표시로 둔다
                val glass = Path().apply {
                    moveTo(-78f, 44f)
                    lineTo(-58f, -40f)
                    quadTo(-4f, -60f, 50f, -40f)
                    lineTo(70f, 44f)
                    close()
                }
                canvas.drawPath(glass, paint)
                canvas.drawCircle(-10f, -5f, 20f, paint)
                for (i in 0..7) {
                    val angle = i * Math.PI / 4
                    val x = kotlin.math.cos(angle).toFloat()
                    val y = kotlin.math.sin(angle).toFloat()
                    canvas.drawLine(
                        -10f + x * 30f,
                        -5f + y * 30f,
                        -10f + x * 39f,
                        -5f + y * 39f,
                        paint
                    )
                }
                paint.color = PleosPalette.surface(light)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(65f, 42f, 28f, paint)
                paint.color = PleosPalette.ink(light)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                canvas.drawCircle(65f, 42f, 22f, paint)
                canvas.drawLine(65f, 29f, 65f, 42f, paint)
                canvas.drawLine(65f, 42f, 74f, 48f, paint)
            }

            "washer" -> {
                val glass = Path().apply {
                    moveTo(-66f, 40f)
                    lineTo(-48f, -40f)
                    quadTo(0f, -57f, 48f, -40f)
                    lineTo(66f, 40f)
                    close()
                }
                canvas.drawPath(glass, paint)
                canvas.drawLine(0f, 31f, -37f, -13f, paint)
                for (x in listOf(-18f, 0f, 18f)) canvas.drawLine(x, -23f, x + 4f, -32f, paint)
            }

            "speed_bump" -> {
                val road = Path().apply {
                    moveTo(-90f, 30f)
                    lineTo(-52f, 30f)
                    cubicTo(-32f, -28f, 32f, -28f, 52f, 30f)
                    lineTo(90f, 30f)
                }
                canvas.drawPath(road, paint)
                canvas.drawLine(-90f, 52f, 90f, 52f, paint)
                canvas.drawLine(-10f, 10f, 10f, 10f, paint)
            }

            "record_saved", "timeline_waiting", "timeline_responded", "timeline_cancelled" -> {
                canvas.drawCircle(0f, 0f, 52f, paint)
                when (kind) {
                    "timeline_waiting" -> {
                        canvas.drawLine(0f, -29f, 0f, 0f, paint)
                        canvas.drawLine(0f, 0f, 23f, 14f, paint)
                    }

                    "record_saved", "timeline_responded" -> {
                        canvas.drawLine(-25f, 0f, -7f, 19f, paint)
                        canvas.drawLine(-7f, 19f, 29f, -20f, paint)
                    }

                    else -> {
                        canvas.drawLine(-20f, -20f, 20f, 20f, paint)
                        canvas.drawLine(-20f, 20f, 20f, -20f, paint)
                    }
                }
            }

            "violation" -> {
                canvas.drawCircle(0f, -8f, 48f, paint)
                canvas.drawLine(-34f, 26f, 34f, -42f, paint)
                canvas.drawLine(0f, 40f, 0f, 66f, paint)
            }

            "reckless" -> {
                canvas.drawLine(-65f, -58f, -65f, 58f, paint)
                canvas.drawLine(65f, -58f, 65f, 58f, paint)
                val route = Path().apply {
                    moveTo(-22f, 56f)
                    cubicTo(-22f, 20f, 30f, 22f, 30f, -7f)
                    cubicTo(30f, -30f, -20f, -27f, -20f, -55f)
                }
                canvas.drawPath(route, paint)
                canvas.drawLine(-20f, -55f, -32f, -42f, paint)
                canvas.drawLine(-20f, -55f, -7f, -42f, paint)
            }

            "wanted" -> {
                canvas.drawRoundRect(-76f, -35f, 76f, 35f, 10f, 10f, paint)
                for (x in listOf(-44f, -14f, 16f, 46f)) canvas.drawLine(x, -12f, x, 12f, paint)
            }

            "rain" -> {
                canvas.drawArc(-65f, -45f, 65f, 50f, 190f, 160f, false, paint)
                canvas.drawLine(-64f, 15f, 64f, 15f, paint)
                for (x in listOf(-40f, 0f, 40f)) canvas.drawLine(x, 32f, x - 12f, 57f, paint)
            }

            "help_112", "help_119" -> {
                canvas.drawRoundRect(-84f, -56f, 84f, 56f, 12f, 12f, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = 64f
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = PleosTypography.face(true)
                canvas.drawText(if (kind == "help_112") "112" else "119", 0f, 23f, paint)
            }

            "timeline_timeout" -> {
                canvas.drawArc(-57f, -57f, 57f, 57f, 35f, 290f, false, paint)
                canvas.drawLine(0f, -31f, 0f, 0f, paint)
                canvas.drawLine(0f, 0f, 21f, 13f, paint)
                paint.color = Color.rgb(190, 115, 0)
                canvas.drawLine(54f, 22f, 54f, 43f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(54f, 55f, 3f, paint)
            }

            "emergency", "manual_help" -> {
                canvas.drawCircle(0f, 0f, 57f, paint)
                canvas.drawLine(-26f, 0f, 26f, 0f, paint)
                canvas.drawLine(0f, -26f, 0f, 26f, paint)
            }

            "observation" -> {
                canvas.drawRoundRect(-44f, -58f, 44f, 58f, 8f, 8f, paint)
                for (y in listOf(-27f, -3f, 21f)) canvas.drawLine(-22f, y, 22f, y, paint)
            }

            "review" -> {
                canvas.drawCircle(0f, 0f, 52f, paint)
                canvas.drawLine(0f, -28f, 0f, 8f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(0f, 28f, 4f, paint)
            }

            "voltage_low", "voltage_repeat", "voltage_normal", "voltage_recovered" -> {
                // 단자 두 개로 보조 배터리를 나타내고, 관측 두 번으로 반복과 회복을 구분한다
                fun battery(x: Float, size: Float, low: Boolean) {
                    canvas.save()
                    canvas.translate(x, 0f)
                    canvas.scale(size, size)
                    paint.color = PleosPalette.ink(light)
                    paint.style = Paint.Style.STROKE
                    canvas.drawRoundRect(-56f, -32f, 56f, 42f, 5f, 5f, paint)
                    canvas.drawRect(-40f, -43f, -22f, -32f, paint)
                    canvas.drawRect(22f, -43f, 40f, -32f, paint)
                    canvas.drawLine(-42f, -16f, -28f, -16f, paint)
                    canvas.drawLine(28f, -16f, 42f, -16f, paint)
                    canvas.drawLine(35f, -23f, 35f, -9f, paint)
                    paint.color = if (low) Color.rgb(190, 115, 0) else PleosPalette.ink(light)
                    if (low) {
                        canvas.drawLine(0f, -8f, 0f, 15f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(0f, 28f, 3f, paint)
                    } else {
                        canvas.drawLine(-18f, 8f, -4f, 22f, paint)
                        canvas.drawLine(-4f, 22f, 22f, -4f, paint)
                    }
                    canvas.restore()
                    paint.style = Paint.Style.STROKE
                }

                val paired = kind in setOf("voltage_repeat", "voltage_recovered")
                if (paired) {
                    battery(-68f, .7f, true)
                    battery(68f, .7f, kind == "voltage_repeat")
                    paint.color = PleosPalette.ink(light)
                    canvas.drawLine(-17f, 0f, 17f, 0f, paint)
                    canvas.drawLine(17f, 0f, 8f, -9f, paint)
                    canvas.drawLine(17f, 0f, 8f, 9f, paint)
                } else battery(0f, 1f, kind == "voltage_low")
            }

            "diagnosis" -> {
                canvas.drawRoundRect(-75f, -42f, 65f, 42f, 8f, 8f, paint)
                canvas.drawLine(74f, -15f, 74f, 15f, paint)
                paint.color = if (voltageLow) Color.rgb(190, 115, 0) else PleosPalette.ink(light)
                paint.style = Paint.Style.FILL
                val bolt = Path().apply {
                    moveTo(3f, -30f)
                    lineTo(-25f, 6f)
                    lineTo(-6f, 6f)
                    lineTo(-15f, 30f)
                    lineTo(20f, -10f)
                    lineTo(0f, -10f)
                    close()
                }
                canvas.drawPath(bolt, paint)
            }

            "pothole" -> {
                canvas.drawLine(-40f, -60f, -85f, 60f, paint)
                canvas.drawLine(40f, -60f, 85f, 60f, paint)
                canvas.drawLine(0f, -55f, 0f, -32f, paint)
                canvas.drawLine(0f, -12f, 0f, 2f, paint)
                canvas.drawOval(-24f, 20f, 24f, 42f, paint)
                val crack = Path().apply {
                    moveTo(-15f, 29f)
                    lineTo(-4f, 26f)
                    lineTo(3f, 35f)
                    lineTo(14f, 29f)
                }
                canvas.drawPath(crack, paint)
            }

            "crash" -> {
                canvas.drawRoundRect(-60f, -38f, 60f, 38f, 14f, 14f, paint)
                val roof = Path().apply {
                    moveTo(-35f, -38f)
                    lineTo(-22f, -65f)
                    lineTo(22f, -65f)
                    lineTo(35f, -38f)
                }
                canvas.drawPath(roof, paint)
                canvas.drawCircle(-34f, 43f, 12f, paint)
                canvas.drawCircle(34f, 43f, 12f, paint)
                canvas.drawLine(82f, -40f, 102f, -60f, paint)
                canvas.drawLine(88f, -16f, 111f, -16f, paint)
            }
        }
        canvas.restore()
    }
}
