package ai.teraq.roadguardian;

/**
 * 비동기 관측을 순서대로 보관한다. 경과 시간은 요청을 보낸 시점부터 센다
 */
public final class TimedSignal {
    private long sequence = -1, at = -1;
    private String value, error;

    public boolean apply(long request, long started, long received, String nextValue, String nextError) {
        if (request <= sequence || started < 0 || received < started || received - started >= 5000 || started < at)
            return false;
        sequence = request;
        at = started;
        value = nextError == null ? nextValue : null;
        error = nextError;
        return true;
    }

    public boolean fresh(long now) {
        return value != null && at >= 0 && now >= at && now - at < 5000;
    }

    public String getValue() {
        return value;
    }

    public String getError() {
        return error;
    }

    public long getAt() {
        return at;
    }
}
