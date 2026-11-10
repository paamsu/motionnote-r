package ai.teraq.roadguardian

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.concurrent.Executors

/** 첨부 사진 보기 전용. 포그라운드, 기어, 주행 제한 확인은 화면을 띄운 쪽이 한다 */
class CasePhotosPanel(
    context: Context,
    private val ui: PhoneAccountUi,
    private val eventId: String,
    private val allowed: () -> Boolean
) : LinearLayout(context) {
    private data class Loaded(
        val records: AuthenticatedEventsClient.DisplayResult<List<CasePhoto>>,
        val photos: List<Pair<CasePhoto, ByteArray>>
    )

    private var worker: java.util.concurrent.ExecutorService? = null
    private val timer = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var session = ""
    private var lease: AccountDisplayLease? = null
    private var cleared = false
    private val bitmaps = mutableListOf<Bitmap>()
    private val car = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val scale =
        if (car) (resources.configuration.screenWidthDp / 1100f).coerceIn(1f, 1.65f) else 1f
    private val watch = object : Runnable {
        override fun run() {
            if (!allowed() || (session.isNotEmpty() && (runCatching {
                    AuthenticatedEventsClient(
                        context
                    ).sessionFingerprint() != session
                }.getOrDefault(true)
                        || lease?.valid(
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime()
                ) == false))
            ) clearPrivate()
            if (isAttachedToWindow) timer.postDelayed(this, 500)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, ui.dp(16), 0, ui.dp(12))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        worker = Executors.newSingleThreadExecutor()
        load()
        timer.post(watch)
    }

    override fun onDetachedFromWindow() {
        generation++
        timer.removeCallbacksAndMessages(null)
        worker?.shutdownNow()
        worker = null
        release()
        session = ""
        lease = null
        super.onDetachedFromWindow()
    }

    private fun text(
        parent: LinearLayout,
        value: String,
        size: Float = 18f,
        bold: Boolean = false
    ) {
        parent.addView(ui.text(value, size * scale, bold))
    }

    private fun release() {
        removeAllViews()
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
    }

    private fun heading(value: String) {
        release()
        text(this, "이 사건의 첨부 사진", 23f, true)
        text(this, value, 17f)
    }

    private fun clearPrivate() {
        if (cleared) return
        cleared = true
        generation++
        session = ""
        lease = null
        heading("표시 조건이나 계정이 바뀌어 사진을 지웠습니다.")
    }

    private fun load() {
        if (!allowed()) {
            clearPrivate()
            return
        }
        cleared = false
        val attempt = ++generation
        heading("본인 계정의 사진을 확인하고 있습니다.")
        worker?.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(context)
                val features = client.capabilitiesForDisplay()
                if (features.value.known && !features.value.vehicleAllowed) throw AccountAccessException(
                    AccountAccessException.Reason.FORBIDDEN
                )
                if (!features.value.photosRead) throw AccountAccessException(AccountAccessException.Reason.NOT_SUPPORTED)
                val records = client.photosForDisplay(eventId)
                if (records.sessionFingerprint != features.sessionFingerprint) throw AccountAccessException(
                    AccountAccessException.Reason.CHANGED
                )
                val photos = records.value.map { photo ->
                    val bytes = client.photoForDisplay(photo)
                    if (bytes.sessionFingerprint != records.sessionFingerprint) throw AccountAccessException(
                        AccountAccessException.Reason.CHANGED
                    )
                    photo to bytes.value
                }
                Loaded(records, photos)
            }
            post {
                if (!isAttachedToWindow || attempt != generation || !allowed()) return@post
                result.fold(onSuccess = { loaded ->
                    val received = loaded.records
                    if (!received.lease.valid(
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime()
                        ) || runCatching { AuthenticatedEventsClient(context).sessionFingerprint() != received.sessionFingerprint }.getOrDefault(
                            true
                        )
                    ) {
                        clearPrivate()
                        return@fold
                    }
                    session = received.sessionFingerprint
                    lease = received.lease
                    if (loaded.photos.size >= 2) {
                        release()
                        addView(CasePhotoComparison(ui, loaded.photos) {
                            allowed() && session.isNotEmpty() && lease?.valid(
                                System.currentTimeMillis(),
                                SystemClock.elapsedRealtime()
                            ) == true &&
                                    runCatching { AuthenticatedEventsClient(context).sessionFingerprint() == session }.getOrDefault(
                                        false
                                    )
                        })
                    } else {
                        heading("사용자 첨부 · 촬영 시점·내용 미확인")
                        text(this, "보관한 사진 ${loaded.photos.size}/4", 20f, true)
                        if (loaded.photos.isEmpty()) text(this, "아직 첨부한 사진이 없습니다.")
                        else addPhoto(this, loaded.photos.first(), "첫 번째 사진")
                    }
                    text(this, "사진 추가·묶음 저장은 휴대전화에서", 16f)
                }, onFailure = { failure ->
                    val access = failure as? AccountAccessException
                    val unsupported = access?.reason == AccountAccessException.Reason.NOT_SUPPORTED
                    val message = when (access?.reason) {
                        AccountAccessException.Reason.NOT_SUPPORTED -> "이 서비스는 첨부 사진 조회를 지원하지 않습니다."
                        AccountAccessException.Reason.FORBIDDEN -> "이 계정에는 사진 조회 권한이 없습니다. 계정 연결을 확인해 주세요."
                        AccountAccessException.Reason.INTEGRITY -> "사진 원본을 확인하지 못해 표시하지 않았습니다."
                        else -> access?.message ?: "사진을 확인하지 못했습니다. 기록을 다시 조회해 주세요."
                    }
                    heading(message)
                    if (!unsupported) addView(ui.button("사진 다시 조회") { load() })
                })
            }
        }
    }

    private fun addPhoto(parent: LinearLayout, item: Pair<CasePhoto, ByteArray>, label: String) {
        val bitmap = BitmapFactory.decodeByteArray(
            item.second,
            0,
            item.second.size,
            BitmapFactory.Options().apply { inSampleSize = 2 })
        if (bitmap == null) {
            text(parent, "사진 표시 실패 · 휴대전화에서 원본 보관 상태를 확인해 주세요.")
            return
        }
        bitmaps.add(bitmap)
        val row = LinearLayout(context)
        val image = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "$label · 사용자가 첨부한 사진 · 촬영 시점 미확인"
        }
        val details = LinearLayout(context).apply { orientation = VERTICAL }
        text(details, label, 19f, true)
        text(details, item.first.caption.ifBlank { "작성한 설명 없음" }, 20f)
        text(details, "변환 사진 · 센서 원본 아님", 16f)
        row.addView(image)
        row.addView(details)
        var lastWide: Boolean? = null
        fun arrange(width: Int) {
            val wide =
                width / resources.displayMetrics.density >= 760 && resources.configuration.fontScale < 1.3f
            if (lastWide == wide) return
            lastWide = wide
            row.orientation = if (wide) HORIZONTAL else VERTICAL
            image.layoutParams =
                if (wide) LayoutParams(0, ui.dp((360 * scale).toInt()), 1.4f).apply {
                    marginEnd = ui.dp(24)
                } else LayoutParams(-1, ui.dp((230 * scale).toInt()))
            details.layoutParams = if (wide) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2)
        }
        arrange((resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt())
        row.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            if (right > left) arrange(
                right - left
            )
        }
        parent.addView(row, LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) })
    }
}
