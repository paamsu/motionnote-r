package ai.teraq.roadguardian

import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject

/** 차량 정보 카드 목록. 차량 위치나 접촉 상황을 재구성하지 않는다 */
object IncidentVehiclesPanel {
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        event: JSONObject,
        scale: Float,
        detailTarget: LinearLayout? = null
    ) {
        if (!event.optBoolean("evidence_valid") || event.optJSONObject("assessment")
                ?.optString("kind") != "crash"
        ) return
        val context = event.optJSONObject("data")?.optJSONObject("context")
        val synthetic =
            event.optString("source") == "synthetic" || context?.optString("source") == "scenario_fixture"
        val own = context?.optJSONObject("own_vehicle")
        val actors = context?.optJSONArray("actors")
        val count = actors?.length() ?: 0
        val panel = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(panel, LinearLayout.LayoutParams(-1, -2))
        var sharedLimits: LinearLayout? = null
        var lastWidth = -1
        var initial = true
        fun supplied(obj: JSONObject?, key: String) =
            obj?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: "미확인"

        fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
        fun rebuild(width: Int) {
            panel.removeAllViews()
            val wide =
                width / body.resources.displayMetrics.density >= 920 && body.resources.configuration.fontScale < 1.3f
            val cards = LinearLayout(body.context).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }

            fun text(
                parent: LinearLayout,
                value: String,
                size: Float = 18f,
                bold: Boolean = false
            ) {
                parent.addView(
                    ui.text(value, size * scale, bold).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                    })
            }

            fun card(
                title: String,
                vehicle: JSONObject?,
                area: String?,
                note: String
            ): LinearLayout {
                val box = LinearLayout(body.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                    background = GradientDrawable().apply {
                        setColor(ui.cardColor)
                        cornerRadius = dp(8).toFloat()
                    }
                }
                text(box, title, 18f, true)
                val row = LinearLayout(body.context).apply { orientation = LinearLayout.HORIZONTAL }
                if (body.resources.configuration.fontScale < 1.3f) {
                    row.addView(
                        VehicleIdentityOutline(body.context, area, ui.light),
                        LinearLayout.LayoutParams(dp(110), dp(150)).apply { marginEnd = dp(20) })
                }
                val identity =
                    LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
                text(identity, supplied(vehicle, "plate"), 25f, true)
                text(identity, supplied(vehicle, "model"), 20f)
                text(identity, supplied(vehicle, "color"), 16f)
                row.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(row)
                text(box, note, 17f)
                val fixture = synthetic || vehicle?.optString("source") == "scenario_fixture"
                text(box, if (fixture) "가상 차량 · 영상 인식 미실행" else "원본 대조 필요", 15f)
                return box
            }

            val area = supplied(context?.optJSONObject("impact"), "area")
            val ownCard =
                card("내 차량", own, area, "${if (synthetic) "가상 충격 위치" else "기록된 충격 위치"} · $area")
            val nearby = if (count == 0) LinearLayout(body.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply {
                    setColor(ui.cardColor)
                    cornerRadius = dp(8).toFloat()
                }
                text(this, "기록된 주변 차량", 18f, true)
                text(this, "주변 차량 정보 없음", 25f, true)
                text(this, "기록이 없다는 뜻이며 주변에 차량이 없었다는 뜻은 아닙니다.", 17f)
            } else card(
                if (count == 1) "기록된 주변 차량" else "주변 차량 ${count}대 중 첫 기록",
                actors?.optJSONObject(0),
                null,
                "접촉 관계 미확정"
            )
            val params =
                if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            cards.addView(
                ownCard,
                LinearLayout.LayoutParams(params)
                    .apply { if (wide) marginEnd = dp(16) else bottomMargin = dp(12) })
            cards.addView(nearby, LinearLayout.LayoutParams(params))
            panel.addView(cards)
            if (count > 1) text(panel, "나머지 ${count - 1}대는 차량·상황 상세에서 확인할 수 있습니다.", 16f)
            val limits = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
            text(
                limits,
                "차종 공통 도식입니다. 차량 간 위치·거리·접촉을 나타내지 않습니다. 충격 표시는 기록에 적힌 위치이며 영상 인식 결과가 아닙니다.",
                16f
            )
            if (detailTarget != null) {
                sharedLimits?.let { detailTarget.removeView(it) }
                detailTarget.addView(limits)
                sharedLimits = limits
            } else ui.disclosure(panel, "차량 도식과 출처", limits)
        }
        panel.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastWidth) {
                lastWidth = width
                panel.post {
                    if (panel.isAttachedToWindow && panel.width == width) {
                        rebuild(width)
                        if (initial) {
                            initial = false
                            body.post {
                                (body.parent as? android.widget.ScrollView)?.scrollTo(
                                    0,
                                    0
                                )
                            }
                        }
                    }
                }
            }
        }
        rebuild((body.resources.configuration.screenWidthDp * body.resources.displayMetrics.density).toInt())
    }
}

/** 비율이 고정된 차량 실루엣. 글자는 모두 줄바꿈되는 TextView로 둔다 */
internal class VehicleIdentityOutline(
    context: android.content.Context,
    private val area: String?,
    private val light: Boolean,
    private val illustrated: Boolean = false
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    companion object {
        private var artwork: android.graphics.Bitmap? = null
        private var topArtwork: android.graphics.Bitmap? = null

        @Synchronized
        private fun topArtwork(resources: android.content.res.Resources): android.graphics.Bitmap? {
            if (topArtwork == null) topArtwork = android.graphics.BitmapFactory.decodeResource(
                resources, R.drawable.generic_vehicle_top_v189,
                android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inScaled = false
                })
            return topArtwork
        }

        @Synchronized
        private fun artwork(resources: android.content.res.Resources): android.graphics.Bitmap? {
            if (artwork == null) artwork = android.graphics.BitmapFactory.decodeResource(
                resources, R.drawable.generic_vehicle_v179,
                android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inScaled = false
                })
            return artwork
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (illustrated && area == null) artwork(resources)?.let { image ->
            val fit = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
            val w = image.width * fit
            val h = image.height * fit
            paint.isFilterBitmap = true
            canvas.drawBitmap(
                image,
                null,
                RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2),
                paint
            )
            return
        }
        // 위에서 본 공통 그림은 앞이 위쪽이다. 충격 위치는 따로 겹쳐 그린다
        val topImage = topArtwork(resources)
        if (topImage != null) {
            val fit = minOf(width.toFloat() / topImage.width, height.toFloat() / topImage.height)
            val w = topImage.width * fit
            val h = topImage.height * fit
            paint.isFilterBitmap = true
            canvas.drawBitmap(
                topImage,
                null,
                RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2),
                paint
            )
        } else {
            // 그림을 읽지 못하면 정해진 기본 그림을 쓴다
            canvas.save()
            canvas.scale(width / 110f, height / 150f)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor(if (light) "#D6DEE3" else "#11171C")
            canvas.drawOval(RectF(21f, 14f, 93f, 146f), paint)
            paint.color = Color.parseColor(if (light) "#27353E" else "#0A0D10")
            for (x in listOf(19f, 78f)) for (y in listOf(31f, 103f)) canvas.drawRoundRect(
                RectF(
                    x,
                    y,
                    x + 13f,
                    y + 26f
                ), 4f, 4f, paint
            )
            val body = Path().apply {
                moveTo(55f, 7f)
                cubicTo(31f, 7f, 25f, 16f, 24f, 35f)
                lineTo(22f, 111f)
                cubicTo(23f, 134f, 29f, 141f, 55f, 142f)
                cubicTo(81f, 141f, 87f, 134f, 88f, 111f)
                lineTo(86f, 35f)
                cubicTo(85f, 16f, 79f, 7f, 55f, 7f)
                close()
            }
            paint.shader = LinearGradient(
                22f,
                0f,
                88f,
                0f,
                intArrayOf(
                    Color.parseColor("#6C7E88"),
                    Color.parseColor("#E3EBEF"),
                    Color.parseColor("#AABBC5"),
                    Color.parseColor("#536973")
                ),
                floatArrayOf(0f, .28f, .7f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(body, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = .9f
            paint.color = Color.parseColor(if (light) "#536B78" else "#BED0DA")
            canvas.drawPath(body, paint)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f,
                38f,
                0f,
                111f,
                Color.parseColor("#203843"),
                Color.parseColor("#506A77"),
                Shader.TileMode.CLAMP
            )
            val glazing = Path().apply {
                moveTo(33f, 43f)
                quadTo(55f, 34f, 77f, 43f)
                lineTo(80f, 102f)
                quadTo(55f, 119f, 30f, 102f)
                close()
            }
            canvas.drawPath(glazing, paint)
            paint.shader = null
            paint.color = Color.parseColor("#BBCBD4")
            val roof = Path().apply {
                moveTo(37f, 60f)
                quadTo(55f, 54f, 73f, 60f)
                lineTo(74f, 94f)
                quadTo(55f, 102f, 36f, 94f)
                close()
            }
            canvas.drawPath(roof, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = .8f
            paint.color = Color.parseColor("#647F8D")
            canvas.drawLine(32f, 28f, 78f, 28f, paint)
            canvas.drawLine(32f, 121f, 78f, 121f, paint)
            canvas.drawLine(28f, 54f, 28f, 103f, paint)
            canvas.drawLine(82f, 54f, 82f, 103f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#B6C7D0")
            canvas.drawRoundRect(RectF(15f, 52f, 25f, 61f), 3f, 3f, paint)
            canvas.drawRoundRect(RectF(85f, 52f, 95f, 61f), 3f, 3f, paint)
            paint.color = Color.parseColor("#F3F8FB")
            canvas.drawRoundRect(RectF(29f, 19f, 41f, 24f), 2f, 2f, paint)
            canvas.drawRoundRect(RectF(69f, 19f, 81f, 24f), 2f, 2f, paint)
            paint.color = Color.parseColor("#995959")
            canvas.drawRoundRect(RectF(27f, 128f, 41f, 132f), 2f, 2f, paint)
            canvas.drawRoundRect(RectF(69f, 128f, 83f, 132f), 2f, 2f, paint)
            canvas.restore()
        }
        val cx = width * .5f
        val top = height * .08f
        val bottom = height * .92f
        val half = width * .29f
        val value = area ?: return
        val front = value.contains("전면") || value.contains("앞")
        val rear = value.contains("후면") || value.contains("후방") || value.contains("뒤")
        val left = value.contains("좌") || value.contains("왼")
        val right = value.contains("우") || value.contains("오른")
        val unknown = listOf("미확인", "불명", "알 수 없", "미상").any { value.contains(it) }
        if (unknown || (!(front || rear) && !(left || right))) return
        val x = if (right && !left) cx + half else if (left && !right) cx - half else cx
        val y =
            if (front && !rear) top + height * .07f else if (rear && !front) bottom - height * .07f else height * .5f
        paint.color = Color.parseColor(if (light) "#A46617" else "#FFC777")
        paint.alpha = 55
        canvas.drawCircle(x, y, width * .16f, paint)
        paint.alpha = 255
        canvas.drawCircle(x, y, width * .065f, paint)
    }
}
