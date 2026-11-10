package ai.teraq.roadguardian;

import java.io.File;
import java.io.IOException;

/**
 * 앱 시작 때 재생·내보내기용 임시 복사본을 지운다
 */
public final class PrivateVideoCache {
    private PrivateVideoCache() {
    }

    public static void clearPreviousProcess(File cache) throws IOException {
        File[] files = cache.listFiles();
        if (files == null) throw new IOException("Video cache unavailable");
        for (File file : files) {
            String name = file.getName();
            if (((name.startsWith("account-video-") && name.endsWith(".mp4")) ||
                    (name.startsWith("account-export-") && name.endsWith(".zip"))) && file.isFile()) {
                if (!file.delete()) throw new IOException("Video cache cleanup failed");
            }
        }
    }
}
