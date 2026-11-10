package ai.teraq.roadguardian;

/**
 * 기록한 사건은 이력에 남기고, 지도에는 기간이 남은 유효한 근거만 표시한다
 */
public final class RoadDisplay {
    private RoadDisplay() {
    }

    public static boolean feedCurrent(double generated, double now) {
        return Double.isFinite(generated) && Double.isFinite(now) && generated > 0
                && generated <= now + 2 && now - generated < 15;
    }

    public static boolean current(double created, double expires, double now, boolean valid, String status) {
        return valid && Double.isFinite(created) && Double.isFinite(expires) && Double.isFinite(now)
                && created <= now + 5 && expires > created && now < expires && !"dismissed".equals(status);
    }
}
