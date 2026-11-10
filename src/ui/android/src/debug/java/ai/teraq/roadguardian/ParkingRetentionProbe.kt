package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.util.UUID

/** Isolated synthetic storage test with an injected clock; not three days of elapsed time. */
class ParkingRetentionProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val scratch = File(
                targetContext.cacheDir,
                "parking-retention-${UUID.randomUUID()}"
            ).apply { mkdirs() }
            val isolated = object : ContextWrapper(targetContext) {
                override fun getFilesDir() = scratch
            }
            val now = System.currentTimeMillis()
            val threeDaysAgo = now - 3 * 86400000L
            val store = ParkingPhotoStore(isolated) { threeDaysAgo }
            val photos = (0..2).map { index ->
                val fixture = File(scratch, "fixture-$index.png")
                val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
                fixture.outputStream()
                    .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                store.importPhoto(Uri.fromFile(fixture))
            }
            val old = store.save("B2", "24", photos, "synthetic storage fixture")
            val reopened = ParkingPhotoStore(isolated)
            val read = checkNotNull(reopened.latestResult().record)
            check(read.getString("id") == old.getString("id"))
            check(read.getString("photo_integrity") == "verified")
            check(read.getJSONArray("photos").length() == 3)
            check(
                ParkingAge.label(
                    read.getDouble("parked_at"),
                    now,
                    java.time.ZoneId.systemDefault()
                ) == "3일 전"
            )
            val newer = reopened.save("B3", "25", listOf(photos[0]), "new synthetic record")
            check(reopened.latest()!!.getString("id") == newer.getString("id"))
            val previous = checkNotNull(reopened.comparisonRecord(old.getString("id")).record)
            check(previous.getString("floor") == "B2" && previous.getString("landmark") == "24")
            check(
                previous.getJSONArray("photos")
                    .length() == 3 && previous.getString("photo_integrity") == "verified"
            )
            check(
                previous.getJSONArray("photo_sha256").toString() == old.getJSONArray("photo_sha256")
                    .toString()
            )
            val records = File(scratch, "parking-photos")
            val oldFile = File(records, "${old.getString("id")}.json")
            val original = oldFile.readText()
            val history = reopened.history()
            check(history.error == null && history.unreadable == 0 && history.records.size == 2)
            check(
                history.records.map { it.getString("id") } == listOf(
                    newer.getString("id"),
                    old.getString("id")
                )
            )
            check(history.records.all { it.getString("photo_integrity") == "not_checked" })
            check(reopened.latest()!!.getString("id") == newer.getString("id"))
            // A notification ID must never resolve to a different internally named record.
            oldFile.writeText(newer.toString())
            check(reopened.record(old.getString("id")) == null)
            check(reopened.history().let { it.records.size == 1 && it.unreadable == 1 })
            check(oldFile.readText() == newer.toString()) // Reject without deleting evidence.
            oldFile.writeText(original)
            val latestFile = File(records, "latest.json")
            val intact = latestFile.readText()
            for (field in listOf("id", "landmark", "place", "parked_at")) {
                val damaged = org.json.JSONObject(intact).apply { remove(field) }
                latestFile.writeText(damaged.toString())
                check(reopened.latestResult().error != null)
            }
            latestFile.writeText(intact)
            val outside = File(scratch, "fixture-0.png")
            check(runCatching {
                reopened.save(
                    "B1",
                    "99",
                    listOf(outside),
                    "outside managed photo directory"
                )
            }.isFailure)
            check(latestFile.readText() == intact)
            check(reopened.latest()!!.getString("id") == newer.getString("id"))
            // Listing metadata is not photo verification; selection must still detect changed bytes.
            val originalPhoto = photos[1].readBytes()
            photos[1].appendBytes(byteArrayOf(1))
            check(reopened.history().records.size == 2)
            check(reopened.comparisonRecord(old.getString("id")).error != null)
            photos[1].writeBytes(originalPhoto)
            check(reopened.comparisonRecord(old.getString("id")).record != null)
            result.putString(
                "stream",
                "PASS: three-photo retention and original record ID; history ordered newest first without latest alias duplication; unreadable record counted and preserved; metadata listing does not bypass photo hash verification on selection; missing fields and outside photos rejected without replacing latest record. Injected 3-day clock, no real 72-hour wait or UI interactions.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
