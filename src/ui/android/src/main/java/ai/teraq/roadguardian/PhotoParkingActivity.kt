package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

/** 고른 사진을 기기에서 인식한 뒤 사용자가 확인한다 */
open class PhotoParkingActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui by lazy { PhoneAccountUi(this) }
    private lateinit var store: ParkingPhotoStore
    private var photos = listOf<File>()
    private var recognized = ""
    private var status = "층 표지와 기둥이 보이는 사진을 골라 주세요. 최대 4장을 함께 확인합니다."
    private var floor = ""
    private var landmark = ""
    private var floorEdited = false
    private var landmarkEdited = false
    private var busy = false

    private data class RetainedWork(
        val save: java.util.concurrent.FutureTask<String?>?,
        val photo: java.util.concurrent.FutureTask<ParkingPhotoTask.Result>?
    )

    private var photoTask: java.util.concurrent.FutureTask<ParkingPhotoTask.Result>? = null
    private val checkPhoto = object : Runnable {
        override fun run() {
            val task = photoTask ?: return
            if (!task.isDone) {
                saveHandler.postDelayed(this, 200)
                return
            }
            val result = try {
                task.get()
            } catch (_: Exception) {
                null
            }
            photoTask = null
            busy = false
            if (result == null || !result.imported) {
                status = "새 사진을 읽지 못했습니다. 이전 사진과 입력은 유지했습니다. 다시 촬영하거나 선택하세요."
                render()
                return
            }
            photos = result.photos
            recognized = result.recognized
            val candidates = ParkingSigns.floors(recognized)
            val markers = ParkingSigns.landmarks(recognized)
            val keptInput = result.append && (floorEdited || landmarkEdited)
            floor = ParkingSigns.nextInput(floor, floorEdited, result.append, candidates)
            landmark = ParkingSigns.nextInput(landmark, landmarkEdited, result.append, markers)
            if (!result.append) {
                floorEdited = false
                landmarkEdited = false
            }
            status = when {
                result.failed -> "일부 사진을 읽지 못했습니다. 보이는 표지를 직접 확인해 입력하세요."
                candidates.size > 1 -> "서로 다른 층 표지가 읽혔습니다: ${candidates.joinToString()}. 현재 위치의 표지를 확인하세요."
                candidates.isEmpty() -> "층 표지를 읽지 못했습니다. 사진 속 표지를 확인해 직접 입력하세요."
                else -> "읽은 층: ${candidates.first()}. ${if (markers.size == 1) "기둥 후보: ${markers.first()}. " else ""}사진과 일치하는지 확인하세요."
            }
            if (keptInput) status += "\n직접 입력한 값은 유지했습니다. 추가한 사진의 표지와도 일치하는지 확인하세요."
            render()
        }
    }
    private var saveTask: java.util.concurrent.FutureTask<String?>? = null
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val checkSave = object : Runnable {
        override fun run() {
            val task = saveTask ?: return
            if (!task.isDone) {
                saveHandler.postDelayed(this, 200)
                return
            }
            val error = try {
                task.get()
            } catch (_: Exception) {
                "저장 결과를 확인하지 못했습니다. 이전 주차 기록을 확인해 주세요."
            }
            saveTask = null
            busy = false
            if (error == null) {
                Toast.makeText(this@PhotoParkingActivity, "주차 위치와 사진을 저장했습니다", Toast.LENGTH_LONG)
                    .show()
                finish()
            } else {
                status = error
                render()
            }
        }
    }

    private fun startSave() {
        if (busy || saveTask != null) return
        val task = ParkingSaveTask.create(this, floor, landmark, photos, recognized)
        saveTask = task
        busy = true
        status = "주차 위치와 사진을 저장하고 있습니다."
        render()
        Thread(task, "parking-photo-save").start()
        saveHandler.post(checkSave)
    }

    private var comparisonShown = false
    private var comparedReference: String? = null
    private var comparisonNotice: TextView? = null
    private fun invalidateComparison() {
        if (!comparisonShown) return
        comparisonShown = false
        status = "표지 입력이 바뀌었습니다. ‘표지 확인하고 비교’를 다시 눌러 주세요."
        // 그 자리에서 고쳐 입력 중에 포커스와 키보드가 초기화되지 않게 한다
        comparisonNotice?.text = status
    }

    private var compare = false
    private var comparisonId: String? = null
    private var resolveLatestReference = false
    private var reference: ParkingPhotoStore.ReadResult? = null
    private var referenceLoading = false
    private var referenceGeneration = 0
    private var referenceTask: java.util.concurrent.Future<*>? = null
    private var cameraUri: Uri? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        store = ParkingPhotoStore(this)
        compare = intent.getBooleanExtra("compare", false)
        if (compare) comparisonId = if (state != null) state.getString("comparison_id")
        else if (intent.hasExtra("parking_record_id")) intent.getStringExtra("parking_record_id")
        else null
        resolveLatestReference = compare && (state?.getBoolean("resolve_latest_reference", false)
            ?: !intent.hasExtra("parking_record_id"))
        state?.let {
            photos = it.getStringArrayList("photos")?.map { path -> File(path) } ?: emptyList()
            comparisonShown = it.getBoolean("comparison_shown", false)
            comparedReference = it.getString("compared_reference")
            floorEdited = it.getBoolean("floor_edited", false)
            landmarkEdited = it.getBoolean("landmark_edited", false)
            recognized = it.getString("recognized", "")
            floor = it.getString("floor", "")
            landmark = it.getString("landmark", "")
            status = it.getString("status", status)
            cameraUri = it.getString("camera_uri")?.let { value -> Uri.parse(value) }
        }
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val retained = lastNonConfigurationInstance as? RetainedWork
        saveTask = retained?.save
        photoTask = retained?.photo
        if (saveTask != null) {
            busy = true
            status = "주차 위치와 사진을 저장하고 있습니다."
        } else if (photoTask != null) {
            busy = true
            status = "사진에서 층 표지를 읽고 있습니다."
        } else if (state?.getBoolean("save_in_progress", false) == true)
            status = "저장 결과를 확인하지 못했습니다. 이전 주차 기록을 확인한 뒤 다시 저장해 주세요."
        render()
    }

    @Deprecated("Framework retained instance")
    override fun onRetainNonConfigurationInstance(): Any? = RetainedWork(saveTask, photoTask)
    override fun onSaveInstanceState(out: Bundle) {
        out.putStringArrayList("photos", ArrayList(photos.map { it.absolutePath }))
        out.putString("recognized", recognized)
        out.putString("floor", floor)
        out.putString("landmark", landmark)
        out.putString("camera_uri", cameraUri?.toString())
        out.putString("comparison_id", comparisonId)
        out.putBoolean("resolve_latest_reference", resolveLatestReference)
        out.putBoolean("comparison_shown", comparisonShown)
        out.putString("compared_reference", comparedReference)
        out.putBoolean("floor_edited", floorEdited)
        out.putBoolean("landmark_edited", landmarkEdited)
        out.putBoolean("save_in_progress", saveTask != null)
        out.putString(
            "status",
            if (busy && saveTask == null) "사진 처리가 중단됐습니다. 사진을 다시 선택하세요." else status
        )
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && compare) refreshReference()
        if (saveTask != null) saveHandler.post(checkSave)
        if (photoTask != null) saveHandler.post(checkPhoto)
    }

    override fun onPause() {
        saveHandler.removeCallbacks(checkSave)
        saveHandler.removeCallbacks(checkPhoto)
        referenceGeneration++
        referenceTask?.cancel(true)
        referenceTask = null
        super.onPause()
    }

    private fun refreshReference(after: (() -> Unit)? = null) {
        val current = ++referenceGeneration
        referenceTask?.cancel(true)
        referenceLoading = true
        render()
        val resolve = resolveLatestReference
        val requestedId = comparisonId
        referenceTask = worker.submit {
            var resolvedId = requestedId
            val result = try {
                if (resolve) resolvedId = store.latestResult().record?.optString("id")
                store.comparisonRecord(resolvedId)
            } catch (_: Exception) {
                ParkingPhotoStore.ReadResult(null, "주차 기록을 확인하지 못했습니다.")
            }
            runOnUiThread {
                if (current != referenceGeneration || isFinishing || isDestroyed) return@runOnUiThread
                comparisonId = resolvedId
                resolveLatestReference = false
                reference = result
                referenceLoading = false
                if (after != null) after() else render()
            }
        }
    }

    private fun label(value: String, size: Float = 18f) = ui.text(value, size)
    private fun render() {
        comparisonNotice = null
        val root = ui.install()
        fun button(
            title: String,
            enabled: Boolean = true,
            primary: Boolean = false,
            block: () -> Unit
        ) {
            root.addView(ui.button(title, primary, block).apply { isEnabled = !busy && enabled })
        }

        val compact = resources.configuration.screenWidthDp >= 600 &&
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                resources.configuration.fontScale < 1.5f
        val title = if (compare) "현재 위치 확인" else "주차 사진 기록"
        if (compact) {
            val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            header.addView(
                ui.button("‹ 주차 위치로") { finish() },
                LinearLayout.LayoutParams(dp(210), -2).apply { marginEnd = dp(20) })
            header.addView(ui.text(title, 26f, true), LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(header)
        } else {
            button("‹ 주차 위치로") { finish() }
            ui.heading(
                root, title,
                if (compare) "주차 당시 사진과 지금 주변의 표지를 비교하세요."
                else "층과 기둥 표지를 남겨 두면 차를 다시 찾기 쉽습니다."
            )
        }
        if (compare && (referenceLoading || reference == null)) {
            root.addView(ui.notice("주차 당시 사진을 확인하고 있습니다."))
            return
        }
        val saved = reference?.record
        if (compare && saved == null) {
            comparisonShown = false
            comparedReference = null
            root.addView(label(reference?.error ?: "주차 기록을 확인하지 못했습니다."))
            return
        }
        if (comparisonShown && comparedReference != saved?.toString()) {
            comparisonShown = false
            comparedReference = null
            status = "주차 기준 기록이 바뀌었습니다. 사진과 표지를 확인한 뒤 다시 비교하세요."
        }
        if (comparisonShown) root.addView(ui.notice(status).also { comparisonNotice = it })
        if (compare && saved != null && photos.isNotEmpty()) button(
            "두 사진 함께 크게 보기",
            primary = true
        ) {
            val referencePhotos = saved.getJSONArray("photos")
            ParkingPhotoGallery.compare(
                this,
                (0 until referencePhotos.length()).map { File(referencePhotos.getString(it)) },
                photos
            )
        }
        root.addView(
            label(
                "사진은 이 휴대전화에만 보관합니다. 최대 4장을 저장할 수 있습니다.",
                15f
            ).apply { setTextColor(ui.muted) })
        button(
            if (photos.isEmpty()) "지금 주변 촬영" else "사진 더 촬영 (${photos.size}/4)",
            photos.size < 4,
            primary = photos.isEmpty()
        ) {
            val capture = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            if (capture.resolveActivity(packageManager) == null) {
                status = "카메라 앱을 찾지 못했습니다. 저장된 사진을 선택하세요."
                render()
            } else {
                val directory = File(cacheDir, "parking-capture").apply { mkdirs() }
                val file = File(directory, "${java.util.UUID.randomUUID()}.jpg")
                cameraUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.parkingphotos",
                    file
                )
                capture.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri)
                capture.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                capture.clipData = android.content.ClipData.newRawUri("parking photo", cameraUri)
                try {
                    startActivityForResult(capture, 43)
                } catch (_: Exception) {
                    status = "카메라를 열지 못했습니다. 저장된 사진을 선택하세요."
                    render()
                }
            }
        }
        button(if (saveTask != null) "저장 중…" else if (busy) "사진에서 표지를 읽는 중…" else if (photos.isEmpty()) "주변 사진 선택" else "사진 다시 선택") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, 42)
        }
        if (compare && saved != null) {
            root.addView(
                label(
                    "주차 당시  ${saved.optString("floor")} · ${saved.optString("landmark")}",
                    22f
                )
            )
            val old = saved.optJSONArray("photos")
            if (old != null && old.length() > 0) {
                root.addView(
                    photoStrip((0 until old.length()).map { File(old.getString(it)) }),
                    LinearLayout.LayoutParams(-1, dp(210))
                )
            }
        }
        if (photos.isNotEmpty()) {
            root.addView(label(if (compare) "지금 선택한 주변 사진" else "저장할 주차 사진", 20f))
            root.addView(photoStrip(photos), LinearLayout.LayoutParams(-1, dp(210)))
            root.addView(
                label(
                    "${photos.size}/4장 · 사진을 누르면 크게 볼 수 있습니다. 옆으로 밀어 다른 사진을 확인하세요. 다시 선택하면 현재 사진을 바꿉니다.",
                    14f
                )
            )
        }
        if (!comparisonShown) root.addView(ui.notice(status))
        if (recognized.isNotBlank()) root.addView(label("사진에서 읽은 글자\n$recognized", 15f))
        if (photos.isNotEmpty() && !busy) {
            val floorField = EditText(this).apply {
                setText(floor)
                hint = "층 (예: B2, 1F)"
                setTextColor(ui.ink)
                setHintTextColor(ui.muted)
                setSingleLine()
                minHeight = dp(56)
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            val landmarkField = EditText(this).apply {
                setText(landmark)
                hint = "기둥·구역 표지 (예: 24번 기둥)"
                setTextColor(ui.ink)
                setHintTextColor(ui.muted)
                setSingleLine()
                minHeight = dp(56)
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            floorField.addTextChangedListener(watch {
                if (floor != it) invalidateComparison()
                floor = it
                floorEdited = true
            })
            landmarkField.addTextChangedListener(watch {
                if (landmark != it) invalidateComparison()
                landmark = it
                landmarkEdited = true
            })
            val floorLabel = label("층", 16f).apply {
                labelFor = android.view.View.generateViewId()
                floorField.id = labelFor
            }
            val landmarkLabel = label("기둥·구역", 16f).apply {
                labelFor = android.view.View.generateViewId()
                landmarkField.id = labelFor
            }
            root.addView(floorLabel)
            root.addView(floorField)
            root.addView(landmarkLabel)
            root.addView(landmarkField)
            button(if (compare) "표지 확인하고 비교" else "주차 위치 저장", primary = true) {
                floor = ParkingSigns.normalizedFloor(floorField.text.toString())
                landmark = landmarkField.text.toString().trim()
                if (floor.isEmpty()) {
                    floorField.error = "층 표지를 확인해 B2 또는 1F처럼 입력하세요"
                    return@button
                }
                if (compare) {
                    refreshReference {
                        val fresh = reference!!
                        if (fresh.record == null) {
                            comparisonShown = false
                            status = fresh.error ?: "주차 기록을 확인하지 못했습니다."
                            render()
                            return@refreshReference
                        }
                        val referenceRecord = fresh.record
                        comparedReference = referenceRecord.toString()
                        val oldFloor = referenceRecord.optString("floor")
                        val oldLandmark = referenceRecord.optString("landmark")
                        comparisonShown = true
                        status =
                            when (ParkingSigns.compare(oldFloor, oldLandmark, floor, landmark)) {
                                ParkingSigns.Comparison.DIFFERENT_FLOOR -> "층이 다릅니다. 지금 확인한 층은 $floor, 주차 기록은 ${oldFloor}입니다."
                                ParkingSigns.Comparison.DIFFERENT_LANDMARK -> "같은 $floor 층이지만 입력한 표지는 다릅니다.\n지금: $landmark\n주차 당시: $oldLandmark\n사진에서 같은 표지를 다르게 적은 것은 아닌지 확인하세요."
                                ParkingSigns.Comparison.NEED_LANDMARK -> "같은 $floor 층입니다. 기둥·구역 표지도 입력하면 비교할 수 있습니다.\n주차 당시: $oldLandmark"
                                ParkingSigns.Comparison.SAME_SIGNS -> "층과 기둥·구역 표지가 같습니다. 같은 표지가 반복될 수 있으니 두 시점의 주변 사진도 확인하세요."
                                else -> "층을 비교하지 못했습니다. 두 사진의 층 표지를 다시 확인하세요."
                            }
                        render()
                    }
                } else {
                    if (landmark.isEmpty()) {
                        landmarkField.error = "다시 찾을 수 있도록 기둥이나 구역 표지를 적어 주세요"
                        return@button
                    }
                    startSave()
                }
            }
        }
    }

    private fun watch(changed: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            changed(s?.toString() ?: "")
        }

        override fun afterTextChanged(s: android.text.Editable?) {}
    }

    private fun photoStrip(files: List<File>) =
        ParkingPhotoGallery.create(this, files, "주차 사진", ui.muted)

    @Deprecated("Legacy Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uris = if (requestCode == 43) listOfNotNull(cameraUri)
        else if (requestCode == 42 && data != null) data.clipData?.let { clip ->
            (0 until minOf(
                clip.itemCount,
                4
            )).map { clip.getItemAt(it).uri }
        } ?: listOfNotNull(data.data)
        else return
        if (uris.isEmpty()) return
        val append = requestCode == 43
        if (append && photos.size >= 4) {
            status = "사진은 최대 4장까지 함께 저장할 수 있습니다."
            render()
            return
        }
        comparisonShown = false
        val task = ParkingPhotoTask.create(this, uris, photos, append)
        photoTask = task
        busy = true
        status = "사진에서 층 표지를 읽고 있습니다."
        render()
        Thread(task, "parking-photo-process").start()
        saveHandler.post(checkPhoto)
    }
}
