package ai.teraq.roadguardian;

/**
 * 안내는 새 결과이고 지금 허용될 때 한 번만 시도한다. 나중으로 미루지 않는다
 */
public final class ComparisonSpeechPolicy {
    public static final boolean DEFAULT_ENABLED = false;

    public static boolean allowed(boolean enabled, boolean noticeVisible, String state) {
        return enabled && noticeVisible && ("parked".equals(state) || "simulated_park".equals(state));
    }

    private long consumed;

    public boolean take(long sequence, boolean permitted, boolean ready) {
        if (!permitted || !ready || sequence <= 0 || sequence <= consumed) return false;
        consumed = sequence;
        return true;
    }
}
