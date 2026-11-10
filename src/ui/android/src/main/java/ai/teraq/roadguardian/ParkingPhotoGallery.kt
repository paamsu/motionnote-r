package ai.teraq.roadguardian

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import java.io.File

/** 표지판 가장자리가 보이게 하고, 주차·비교 화면에서 사진 순서를 유지한다 */
object ParkingPhotoGallery {
    fun compare(context: android.app.Activity, saved: List<File>, current: List<File>) {
        if (saved.isEmpty() || current.isEmpty()) return
        if (context.fragmentManager.findFragmentByTag("parking-photo-comparison") != null) return
        ParkingComparisonDialog().apply {
            arguments = android.os.Bundle().apply {
                putStringArrayList("saved", ArrayList(saved.map { it.absolutePath }))
                putStringArrayList("current", ArrayList(current.map { it.absolutePath }))
            }
        }.show(context.fragmentManager, "parking-photo-comparison")
    }

    internal fun comparisonDialog(
        context: android.app.Activity,
        saved: List<File>,
        current: List<File>,
        indices: IntArray,
        sourceNote: String? = null
    ): android.app.Dialog {
        val ui = PhoneAccountUi(context)
        val metrics = context.resources.displayMetrics
        val landscape = metrics.widthPixels > metrics.heightPixels
        val car =
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
        val scale = if (car) (context.resources.configuration.screenWidthDp / 1100f).coerceIn(
            1f,
            1.65f
        ) else 1f

        fun dp(n: Int) = (n * metrics.density * scale).toInt()
        val panels = LinearLayout(context).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun panel(title: String, files: List<File>, slot: Int) {
            var index = indices[slot].coerceIn(0, files.lastIndex)
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val caption = ui.text("", (if (car) 19f else 16f) * scale)
            val picture = ParkingPhotoView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(12, 14, 17))
            }

            fun update() {
                indices[slot] = index
                caption.text = "$title · ${index + 1}/${files.size}"
                picture.load(files[index])
                picture.contentDescription = caption.text
            }
            group.addView(caption)
            group.addView(picture, LinearLayout.LayoutParams(-1, 0, 1f))
            if (files.size > 1) {
                val controls = LinearLayout(context)
                controls.addView(
                    ui.button("이전 사진") {
                        index = (index + files.size - 1) % files.size
                        update()
                    }.apply { contentDescription = "$title 이전 사진" },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                controls.addView(
                    ui.button("다음 사진") {
                        index = (index + 1) % files.size
                        update()
                    }.apply { contentDescription = "$title 다음 사진" },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                group.addView(controls)
            }
            panels.addView(
                group,
                if (landscape) LinearLayout.LayoutParams(
                    0,
                    -1,
                    1f
                ) else LinearLayout.LayoutParams(-1, 0, 1f)
            )
            update()
        }
        panel("주차 당시", saved, 0)
        panel("지금 주변", current, 1)
        val dialog =
            android.app.Dialog(context, android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        root.addView(ui.text("두 시점의 표지 비교", (if (car) 28f else 20f) * scale, true))
        if (!sourceNote.isNullOrBlank()) root.addView(
            ui.text(
                sourceNote,
                (if (car) 17f else 16f) * scale
            )
        )
        val largeType = context.resources.configuration.fontScale >= 1.5f
        root.addView(
            panels,
            if (largeType) LinearLayout.LayoutParams(
                -1,
                dp(if (landscape) 360 else 680)
            ) else LinearLayout.LayoutParams(-1, 0, 1f)
        )
        root.addView(ui.button("닫기") { dialog.dismiss() })
        if (largeType) dialog.setContentView(ScrollView(context).apply {
            isFillViewport = true
            addView(root)
        })
        else dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (metrics.widthPixels * .96).toInt(),
                (metrics.heightPixels * .92).toInt()
            )
        }
        return dialog
    }

    fun create(
        context: Context,
        files: List<File>,
        caption: String,
        captionColor: Int = Color.LTGRAY
    ): HorizontalScrollView {
        fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        fun image(file: File, edge: Int) = ParkingPhotoView(context, edge).apply {
            load(file)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(12, 14, 17))
        }

        val row = LinearLayout(context)
        files.forEachIndexed { index, file ->
            val title = "$caption ${index + 1} / ${files.size}"
            val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val picture = image(file, 800).apply {
                contentDescription = "$title, 크게 보기"
                isFocusable = true
                setOnClickListener {
                    val full = image(file, 1600).apply { contentDescription = title }
                    val dialog = AlertDialog.Builder(context).setTitle(title)
                        .setView(full).setPositiveButton("닫기", null).create()
                    dialog.setOnShowListener {
                        val metrics = context.resources.displayMetrics
                        dialog.window?.setLayout(
                            (metrics.widthPixels * .94).toInt(),
                            (metrics.heightPixels * .85).toInt()
                        )
                        full.layoutParams = full.layoutParams.apply {
                            height = (metrics.heightPixels * .62).toInt()
                        }
                    }
                    dialog.show()
                }
            }
            card.addView(picture, LinearLayout.LayoutParams(-1, 0, 1f))
            card.addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTextColor(captionColor)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(28)))
            row.addView(card, LinearLayout.LayoutParams(dp(280), -1).apply { marginEnd = dp(10) })
        }
        return HorizontalScrollView(context).apply {
            addView(row)
            isHorizontalScrollBarEnabled = true
        }
    }
}
