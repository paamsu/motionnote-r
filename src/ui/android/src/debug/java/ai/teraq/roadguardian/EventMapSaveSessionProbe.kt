package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import org.json.JSONObject
import java.io.File

/** Prepares actual Main in a parked simulation; user controls are operated separately. */
class EventMapSaveSessionProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val event = JSONObject(File(targetContext.filesDir, "map-save-fixture.json").readText())
            val activity = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity

            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            Thread.sleep(2500)
            runOnMainSync {
                val timer = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) timer.removeCallbacks(field(name).get(activity) as Runnable)
                (field("drivingState").get(activity) as DrivingState).apply {
                    useSimulation(true)
                    setSimulatedMoving(false)
                }
                field("selected").set(activity, "기록")
                field("opened").set(activity, event)
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
                    .invoke(activity)
            }
            File(targetContext.filesDir, "map-save-session-ready").writeText("ready")
            repeat(1800) {
                if (File(targetContext.filesDir, "map-save-session-stop").exists()) return@repeat
                Thread.sleep(100)
            }
            result.putString(
                "stream",
                "Session ended; UI actions and exported files require separate audit\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
