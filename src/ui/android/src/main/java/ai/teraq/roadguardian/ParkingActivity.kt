package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 마지막 주차 기록. 확인한 로컬 사진이 시연 기록보다 우선한다 */
open class ParkingActivity : Activity() {
    private val client = BackendClient()
    private val worker = Executors.newSingleThreadExecutor()
    private var parking: JSONObject? = null
    private var message = "주차 기록을 불러오는 중입니다."
    private var busy = false
    private var recordError = false
    private var generation = 0
    private var loadTask: java.util.concurrent.Future<*>? = null
    private val fromHistory get() = intent.getStringExtra("parking_record_origin") == "history"
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        val current = ++generation
        val id = intent.getStringExtra("parking_record_id")
        parking = null
        recordError = false
        busy = true
        message = "주차 기록을 불러오는 중입니다."
        render()
        loadTask = worker.submit {
            val read = try {
                readSavedRecord(id)
            } catch (_: Exception) {
                ParkingPhotoStore.ReadResult(null, "주차 기록을 읽지 못했습니다.")
            }
            runOnUiThread {
                if (current != generation || isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                parking = read.record
                recordError = read.error != null
                message =
                    read.error ?: if (parking == null) "저장된 주차 위치가 없습니다. 층과 기둥 표지가 보이는 사진을 저장해 주세요."
                    else if (id != null) {
                        if (fromHistory) "목록에서 선택한 주차 기록입니다." else "알림이 생성될 때의 주차 기록입니다."
                    } else "휴대폰에 저장한 주차 위치입니다."
                render()
            }
        }
    }

    protected open fun readSavedRecord(id: String?): ParkingPhotoStore.ReadResult {
        val store = ParkingPhotoStore(applicationContext)
        if (id == null) return store.latestResult()
        val record = store.record(id)
        return ParkingPhotoStore.ReadResult(
            record,
            if (record == null) "선택한 주차 기록을 열지 못했습니다." else null
        )
    }

    override fun onPause() {
        generation++
        loadTask?.cancel(true)
        loadTask = null
        parking = null
        busy = false
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        val ui = PhoneAccountUi(this)
        val column = ui.install()
        fun button(label: String, primary: Boolean = false, action: () -> Unit) =
            ui.button(label, primary, action).apply { isEnabled = !busy }

        val compact = resources.configuration.screenWidthDp >= 600 &&
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                resources.configuration.fontScale < 1.5f
        val title =
            if (fromHistory) "이전 주차 위치" else if (intent.hasExtra("parking_record_id")) "알림의 주차 위치" else "마지막 주차 위치"
        val back = ui.button(if (fromHistory) "‹ 주차 기록 목록으로" else "‹ 내 차량으로") { finish() }
        if (compact) {
            val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            header.addView(
                back,
                LinearLayout.LayoutParams(dp(210), -2).apply { marginEnd = dp(20) })
            header.addView(ui.text(title, 26f, true), LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(header)
        } else column.addView(back)
        val local = parking?.optString("source") == "user_confirmed_photo"
        if (!compact) ui.heading(
            column, title,
            if (recordError) "저장 기록을 확인해 주세요." else if (local) "사진으로 남겨 둔 내 차 위치" else if (parking == null) "층과 기둥 표지를 사진으로 남겨 두세요." else "가상 주차 사례"
        )
        parking?.let { record ->
            val location = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(14).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
            }
            if (!compact) {
                location.addView(ui.text("주차 층", 15f).apply { setTextColor(ui.muted) })
                location.addView(
                    ui.text(record.getString("floor"), 48f, true)
                        .apply { setTextColor(Color.parseColor("#285FA6")) })
                location.addView(ui.text("기둥·구역", 15f).apply { setTextColor(ui.muted) })
                location.addView(ui.text(record.getString("landmark"), 28f, true))
            } else {
                location.addView(
                    ui.text(
                        "주차 층  ${record.getString("floor")}  ·  기둥·구역  ${
                            record.getString(
                                "landmark"
                            )
                        }", 28f, true
                    ).apply { setTextColor(Color.parseColor("#285FA6")) })
            }
            val parked = record.getDouble("parked_at")
            val age = ParkingAge.label(
                parked,
                System.currentTimeMillis(),
                java.time.ZoneId.systemDefault()
            )
            val date = SimpleDateFormat(
                "M월 d일 HH:mm",
                Locale.KOREAN
            ).format(Date((parked * 1000).toLong()))
            location.addView(
                ui.text(
                    if (compact) "$age 주차 · $date 저장" else "$age 주차\n$date 저장",
                    16f
                ).apply { setTextColor(ui.muted) })
            val compare = if (local) button("주변 사진으로 현재 위치 확인", true) {
                startActivity(
                    android.content.Intent(this, PhotoParkingActivity::class.java)
                        .putExtra("compare", true)
                        .putExtra("parking_record_id", record.getString("id"))
                )
            } else null
            if (compact && compare != null) {
                val summary =
                    LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                summary.addView(location, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = dp(20)
                    bottomMargin = dp(16)
                })
                summary.addView(compare, LinearLayout.LayoutParams(dp(250), -2))
                column.addView(summary)
            } else {
                column.addView(location)
                compare?.let { column.addView(it) }
            }
            column.addView(ui.text("주차 당시 사진", 21f, true))
            if (local) {
                val photos = record.getJSONArray("photos")
                column.addView(
                    ParkingPhotoGallery.create(
                        this,
                        (0 until photos.length()).map { java.io.File(photos.getString(it)) },
                        "주차 사진",
                        ui.muted
                    ),
                    LinearLayout.LayoutParams(-1, dp(238)).apply { bottomMargin = dp(12) })
                column.addView(
                    ui.text(
                        if (photos.length() > 1) "옆으로 밀어 사진을 넘기고, 눌러서 크게 보세요." else "사진을 누르면 크게 볼 수 있습니다.",
                        16f
                    ).apply { setTextColor(ui.muted) })
                column.addView(ui.text("이 휴대전화에 보관한 사진입니다.", 15f).apply { setTextColor(ui.muted) })
            } else {
                column.addView(ImageView(this).apply {
                    setImageResource(R.drawable.parking_scene_v1)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "가상 주차장 B2와 24번 기둥을 나타낸 AI 생성 장면"
                }, LinearLayout.LayoutParams(-1, dp(210)))
                column.addView(ui.notice("AI 생성 참고 장면입니다. 실제 내 차의 주차 사진이 아닙니다."))
                val signs = EditText(this).apply {
                    hint = "현재 층, 기둥 번호"
                    setSingleLine(true)
                    textSize = 20f
                    setTextColor(ui.ink)
                    setHintTextColor(ui.muted)
                    minHeight = dp(56)
                }
                column.addView(signs)
                column.addView(button("가상 사례의 현재 위치 확인") {
                    val values =
                        signs.text.toString().split(',', ' ', '\n').filter { it.isNotBlank() }
                    if (values.isEmpty()) signs.error = "보이는 표지를 입력하세요"
                    else request(
                        "/api/demo/parking/locate",
                        JSONObject().put("signs", JSONArray(values))
                    )
                })
            }
        }
        if (recordError || parking == null || !local) column.addView(ui.notice(message))
        else if (intent.hasExtra("parking_record_id")) column.addView(
            ui.text(
                if (fromHistory) "이전에 저장한 위치입니다. 지금 주차한 위치와 다를 수 있습니다." else "알림이 생성될 때의 기록입니다. 이후 차량을 옮겼다면 새 위치를 저장해 주세요.",
                16f
            ).apply { setTextColor(ui.muted) })
        column.addView(button(if (local) "새 주차 위치 사진으로 저장" else "주차 위치 사진으로 저장", !local) {
            startActivity(android.content.Intent(this, PhotoParkingActivity::class.java))
        })
        if (!fromHistory) column.addView(button("이전 주차 기록 보기") {
            startActivity(android.content.Intent(this, ParkingHistoryActivity::class.java))
        })
        if (BuildConfig.DEBUG && parking == null && !recordError) column.addView(button("3일 전 가상 주차 기록 만들기") {
            request("/api/demo/parking/record", JSONObject())
        })
    }

    private fun request(path: String, body: JSONObject? = null) {
        if (!BuildConfig.DEBUG) return
        if (busy) return
        busy = true
        val current = generation
        worker.execute {
            var failed = false
            val response = try {
                client.request(path, body)
            } catch (_: Exception) {
                failed = true
                "null"
            }
            val result = try {
                if (response.trim() == "null") null else JSONObject(response)
            } catch (_: Exception) {
                failed = true
                null
            }
            val localRead = if (!path.endsWith("/locate")) readSavedRecord(null) else null
            runOnUiThread {
                if (current == generation && !isFinishing && !isDestroyed) {
                    busy = false
                    if (failed) message = "주차 기록 서버에 연결하지 못했습니다. 다시 화면을 열어 확인하세요."
                    else if (path.endsWith("/locate")) {
                        message = when (result?.optString("status")) {
                            "same_floor" -> if (result.optBoolean("landmark_matches")) "주차 당시 층과 기둥 표지가 같습니다. 주변 사진도 확인하세요." else "주차 당시와 같은 층입니다. 24번 기둥을 찾아보세요."
                            "different_floor" -> "입력한 현재 층은 ${result.optString("current_floor")}입니다. 차량은 ${
                                parking?.optString(
                                    "floor"
                                )
                            }에 기록되어 있습니다."

                            "conflicting_signs" -> "서로 다른 층이 입력되었습니다. 현재 보이는 층을 다시 확인하세요."
                            else -> "현재 층을 확인할 수 없습니다. 층 표지판을 확인하세요."
                        }
                    } else {
                        val read = localRead!!
                        recordError = read.error != null
                        parking = if (recordError) null else read.record ?: result
                        message = read.error
                            ?: if (parking == null) "저장된 주차 위치가 없습니다." else "현재 휴대폰 위치는 아직 확인되지 않았습니다."
                    }
                    render()
                }
            }
        }
    }
}
