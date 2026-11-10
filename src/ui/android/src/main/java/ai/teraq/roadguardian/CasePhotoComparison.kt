package ai.teraq.roadguardian

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 같은 기록에 붙은 확인된 사진만 비교한다 */
class CasePhotoComparison(
    private val ui: PhoneAccountUi,
    private val photos: List<Pair<CasePhoto, ByteArray>>,
    private val allowed: () -> Boolean
) : LinearLayout(ui.context) {
    private val images = mutableMapOf<String, Bitmap>()
    private var selection: PhotoPairSelection? = null
    private var chooser: Dialog? = null
    private val cards = LinearLayout(context)
    private val metadata = LinearLayout(context).apply { orientation = VERTICAL }
    private var wide = false
    private val car =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
    private val scale =
        if (car) (resources.configuration.screenWidthDp / 1100f).coerceIn(1f, 1.65f) else 1f
    private val notice = ui.text("촬영 시각·동일 장소 미확인 · A/B는 비교용 구분", 18f * scale)

    init {
        orientation = VERTICAL
        try {
            require(photos.map { it.first.eventId }.distinct().size == 1)
            for ((photo, raw) in photos) {
                photo.verifiedBytes(raw)
                images[photo.id] = checkNotNull(
                    BitmapFactory.decodeByteArray(
                        raw,
                        0,
                        raw.size,
                        BitmapFactory.Options().apply { inSampleSize = 2 })
                )
            }
            selection = PhotoPairSelection(
                photos.map { it.first.id },
                photos.associate { it.first.id to it.first.sha256 })
            addView(ui.text("사진 두 장 비교", 23f * scale, true))
            addView(notice)
            val controls = LinearLayout(context)
            for ((slot, label) in listOf(0 to "A 선택", -1 to "A ↔ B", 1 to "B 선택")) {
                controls.addView(ui.button(label) {
                    if (!isAttachedToWindow || selection == null || !allowed()) {
                        clearPrivate()
                        return@button
                    }
                    if (slot < 0) {
                        selection!!.swap()
                        render()
                    } else choose(slot)
                }.apply {
                    contentDescription =
                        if (slot < 0) "사진 A와 B 자리 바꾸기" else "사진 ${if (slot == 0) "A" else "B"} 선택"
                }, LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
            }
            addView(controls)
            addView(cards, LayoutParams(-1, -2))
            ui.disclosure(this, "사진 보관 정보", metadata)
            render()
        } catch (failure: Exception) {
            clearPrivate(if (failure is IllegalArgumentException && failure.message == "Two different photo contents required") "같은 이미지뿐입니다. 다른 사진을 추가해 주세요." else "비교할 사진을 확인하지 못했습니다.")
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 그리기 전에 패널 너비를 먼저 구해 첫 화면이 세로로 쌓이지 않게 한다
        val next = MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density >= 760
        if (next != wide) {
            wide = next
            if (selection != null && images.isNotEmpty()) render()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun choose(slot: Int) {
        chooser?.dismiss()
        val dialog = Dialog(context, android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            background = GradientDrawable().apply {
                setColor(ui.cardColor)
                cornerRadius = ui.dp(14).toFloat()
            }
        }
        content.addView(ui.text("사진 ${if (slot == 0) "A" else "B"} 선택", 23f * scale, true))
        val issue = ui.text("", 16f * scale)
        for ((index, item) in photos.withIndex()) content.addView(
            ui.button(
                "사진 ${index + 1}",
                selection!!.get(slot) == item.first.id
            ) {
                if (!isAttachedToWindow || selection == null || !allowed()) {
                    clearPrivate()
                    return@button
                }
                if (selection!!.select(slot, item.first.id)) {
                    dialog.dismiss()
                    chooser = null
                    render()
                } else issue.text = "같은 사진입니다. 다른 사진을 선택하세요."
            }.apply {
                // 확인된 비트맵을 다시 쓴다. 선택지에 번호와 사진을 함께 보여 준다
                images[item.first.id]?.let { bitmap ->
                    val previewHeight = ui.dp(if (car) 72 else 48)
                    val previewWidth =
                        (previewHeight * bitmap.width.toFloat() / bitmap.height).toInt()
                            .coerceAtMost(ui.dp(if (car) 128 else 86))
                    val fittedHeight =
                        (previewWidth * bitmap.height.toFloat() / bitmap.width).toInt()
                    val preview =
                        android.graphics.drawable.BitmapDrawable(resources, bitmap).apply {
                            setBounds(0, 0, previewWidth, fittedHeight)
                        }
                    setCompoundDrawablesRelative(preview, null, null, null)
                    compoundDrawablePadding = ui.dp(16)
                }
            })
        content.addView(issue)
        content.addView(ui.button("취소") {
            dialog.dismiss()
            chooser = null
        })
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        chooser = dialog
        dialog.show()
        dialog.window?.setLayout(
            minOf(
                ui.dp(520),
                (width * .95f).toInt()
            ).coerceAtLeast(ui.dp(240)), -2
        )
    }

    private fun render() {
        if (selection == null || images.isEmpty()) return
        if (!allowed()) {
            clearPrivate()
            return
        }
        cards.removeAllViews()
        metadata.removeAllViews()
        cards.orientation = if (wide == true) HORIZONTAL else VERTICAL
        notice.text = PhotoCaptureContext.comparison(
            photos.first { it.first.id == selection!!.get(0) }.first.captureContext,
            photos.first { it.first.id == selection!!.get(1) }.first.captureContext
        )
        for (slot in 0..1) {
            val item = photos.first { it.first.id == selection!!.get(slot) }.first
            val number = photos.indexOfFirst { it.first.id == item.id } + 1
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                background = GradientDrawable().apply {
                    setColor(ui.cardColor)
                    cornerRadius = ui.dp(14).toFloat()
                }
            }
            card.addView(
                ui.text(
                    "사진 ${if (slot == 0) "A" else "B"} · 목록 $number/${photos.size}",
                    19f * scale,
                    true
                ).apply { layoutParams = LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) } })
            card.addView(ImageView(context).apply {
                setImageBitmap(images[item.id])
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription =
                    "사진 ${if (slot == 0) "A" else "B"} · 목록 $number/${photos.size} · ${item.captureContext?.sourceLabel ?: "촬영 시각 미확인"}"
            }, LayoutParams(-1, ui.dp(((if (wide == true) 240 else 160) * scale).toInt())))
            card.addView(
                ui.text(item.caption.ifBlank { "작성한 설명 없음" }, 17f * scale).apply {
                    layoutParams = LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) }
                })
            val time = SimpleDateFormat(
                "M월 d일 HH:mm",
                Locale.KOREAN
            ).format(Date((item.receivedAt * 1000).toLong()))
            item.captureContext?.let {
                metadata.addView(
                    ui.text(
                        "사진 ${if (slot == 0) "A" else "B"} · ${it.detail()}\n촬영 사실·보수 완료를 인증하지 않습니다.",
                        14f * scale
                    )
                )
            }
            metadata.addView(
                ui.text(
                    "사진 ${if (slot == 0) "A" else "B"} · 서버 보관 $time · 촬영 시각 아님",
                    14f * scale
                )
            )
            cards.addView(
                card,
                if (wide == true) LayoutParams(0, -2, 1f).apply {
                    marginEnd = ui.dp(8)
                } else LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        }
    }

    private fun clearPrivate(message: String = "표시 조건이나 계정이 바뀌어 비교 사진을 지웠습니다.") {
        chooser?.dismiss()
        chooser = null
        removeAllViews()
        images.values.forEach { if (!it.isRecycled) it.recycle() }
        images.clear()
        selection = null
        addView(ui.notice(message))
    }

    override fun onDetachedFromWindow() {
        chooser?.dismiss()
        chooser = null
        cards.removeAllViews()
        images.values.forEach { if (!it.isRecycled) it.recycle() }
        images.clear()
        selection = null
        super.onDetachedFromWindow()
    }
}
