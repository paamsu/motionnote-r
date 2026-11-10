package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 로컬 주차 이력. 둘러봐도 최신 주차 기록은 바뀌지 않는다 */
open class ParkingHistoryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var history: ParkingPhotoStore.HistoryResult? = null
    private var page = 0
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        page = state?.getInt("page", 0) ?: 0
        render()
        worker.execute {
            val result = try {
                ParkingPhotoStore(this).history()
            } catch (_: Exception) {
                ParkingPhotoStore.HistoryResult(emptyList(), error = "주차 기록 목록을 읽지 못했습니다.")
            }
            runOnUiThread {
                if (!isDestroyed) {
                    history = result
                    page = page.coerceIn(0, ((result.records.size - 1) / 20).coerceAtLeast(0))
                    render()
                }
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("page", page)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    protected open fun recordIntent(id: String) = Intent(this, ParkingActivity::class.java)
        .putExtra("parking_record_id", id).putExtra("parking_record_origin", "history")

    private fun render() {
        val ui = PhoneAccountUi(this)
        val body = ui.install()
        body.addView(ui.button("‹ 주차 위치로") { finish() })
        ui.heading(body, "이전 주차 기록", "날짜와 층을 골라 당시 사진을 확인하세요.")
        val result = history
        if (result == null) {
            body.addView(ui.notice("주차 기록을 불러오는 중입니다."))
            return
        }
        result.error?.let {
            body.addView(ui.notice(it))
            return
        }
        if (result.unreadable > 0) body.addView(ui.notice("확인하지 못한 기록 ${result.unreadable}건이 있습니다. 저장된 파일은 보존했습니다."))
        if (result.records.isEmpty()) {
            body.addView(ui.notice("이전에 저장한 주차 기록이 없습니다."))
            return
        }
        val start = page * 20
        val end = minOf(start + 20, result.records.size)
        body.addView(
            ui.text("${result.records.size}건 중 ${start + 1}–${end}번째 · 최신순", 16f)
                .apply { setTextColor(ui.muted) })
        val format = SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREAN)
        for (record in result.records.subList(start, end)) {
            val date = format.format(Date((record.getDouble("parked_at") * 1000).toLong()))
            val floor = record.getString("floor")
            val landmark = record.getString("landmark")
            val count = record.getJSONArray("photos").length()
            body.addView(ui.button("$floor · $landmark\n$date · 사진 ${count}장") {
                startActivity(recordIntent(record.getString("id")))
            }.apply {
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            })
        }
        if (page > 0) body.addView(ui.button("더 최근 기록") {
            page--
            render()
        })
        if (end < result.records.size) body.addView(ui.button("더 이전 기록") {
            page++
            render()
        })
    }
}
