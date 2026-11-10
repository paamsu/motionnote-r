package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaPlayer
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Local synthetic codec probe; no UI controls or authentication. */
class VideoDecodeProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val thread = HandlerThread("synthetic-video-probe").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var player: MediaPlayer? = null
        var images: ImageReader? = null
        var frames = 0
        var duration = 0
        var width = 0
        var height = 0
        val file = File.createTempFile("decode-probe-", ".mp4", targetContext.cacheDir)
        try {
            targetContext.assets.open("synthetic-playback-v73.mp4")
                .use { input -> file.outputStream().use { input.copyTo(it) } }
            handler.post {
                try {
                    val media = MediaPlayer()
                    player = media
                    val reader = ImageReader.newInstance(640, 360, ImageFormat.YUV_420_888, 3)
                    images = reader
                    reader.setOnImageAvailableListener({ source ->
                        source.acquireLatestImage()?.use { frames++ }
                    }, handler)
                    media.setSurface(reader.surface)
                    media.setOnErrorListener { _, what, extra ->
                        failure.set(IllegalStateException("decoder error $what/$extra"))
                        done.countDown()
                        true
                    }
                    media.setOnCompletionListener { done.countDown() }
                    media.setDataSource(file.absolutePath)
                    media.prepare()
                    duration = media.duration
                    width = media.videoWidth
                    height = media.videoHeight
                    check(duration in 9500..10500 && width == 640 && height == 360)
                    media.start()
                } catch (error: Throwable) {
                    failure.set(error)
                    done.countDown()
                }
            }
            check(done.await(25, TimeUnit.SECONDS)) { "playback completion timeout" }
            failure.get()?.let { throw it }
            check(frames >= 80) { "insufficient decoded frames: $frames" }
            result.putString(
                "stream",
                "PASS: Android MediaPlayer completed local synthetic H.264 playback; duration=${duration}ms dimensions=${width}x${height} decodedFrames=$frames. Offscreen ImageReader only; no authenticated network, VideoView UI or real camera tested.\n"
            )
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            failure.set(error)
        } finally {
            val released = CountDownLatch(1)
            handler.post {
                try {
                    player?.release()
                    images?.close()
                } finally {
                    released.countDown()
                }
            }
            released.await(5, TimeUnit.SECONDS)
            thread.quitSafely()
            file.delete()
        }
        finish(if (failure.get() == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
