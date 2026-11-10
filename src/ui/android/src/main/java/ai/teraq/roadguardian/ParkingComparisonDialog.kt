package ai.teraq.roadguardian

import android.app.Dialog
import android.app.DialogFragment
import android.app.AlertDialog
import android.os.Bundle
import java.io.File

/** 화면을 돌려도 열린 비교 창과 고른 사진 두 장을 되살린다 */
@Suppress("DEPRECATION")
class ParkingComparisonDialog : DialogFragment() {
    private var indices = intArrayOf(0, 0)
    override fun onCreateDialog(state: Bundle?): Dialog {
        indices = state?.getIntArray("indices")?.takeIf { it.size == 2 } ?: intArrayOf(0, 0)
        val saved = arguments?.getStringArrayList("saved")?.map { File(it) } ?: emptyList()
        val current = arguments?.getStringArrayList("current")?.map { File(it) } ?: emptyList()
        if (saved.isEmpty() || current.isEmpty() || (saved + current).any { !it.isFile })
            return AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setMessage("비교할 사진을 열지 못했습니다. 사진을 다시 확인해 주세요.")
                .setPositiveButton("닫기", null).create()
        return ParkingPhotoGallery.comparisonDialog(activity, saved, current, indices)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putIntArray("indices", indices)
        super.onSaveInstanceState(out)
    }
}
