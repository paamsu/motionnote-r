package ai.teraq.roadguardian;

import java.util.ArrayDeque;

/**
 * 하강 안내를 띄울지 판단하는 개발용 기준. 층이나 엘리베이터를 판별하지는 않는다
 */
public final class DescentCue {
    private record Sample(long time, double pressure, double vertical) {
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long last = -1, lastCue = -1;

    public void reset() {
        samples.clear();
        last = -1;
    }

    public boolean add(long now, double pressure, double vertical) {
        if (now < 0 || !Double.isFinite(pressure) || !Double.isFinite(vertical)
                || pressure < 300 || pressure > 1100) {
            reset();
            return false;
        }
        if (last >= 0 && (now <= last || now - last > 3000)) {
            reset();
        }
        last = now;
        samples.addLast(new Sample(now, pressure, vertical));
        while (!samples.isEmpty() && now - samples.peekFirst().time > 30000) samples.removeFirst();
        if (samples.size() < 5 || now - samples.peekFirst().time < 5000) return false;
        if (lastCue >= 0 && now >= lastCue && now - lastCue < 120000) return false;
        double delta = pressure - samples.peekFirst().pressure;
        boolean pulse = samples.stream().anyMatch(s -> Math.abs(s.vertical) > .15);
        if (delta >= .25 && delta <= 3 && pulse) {
            lastCue = now;
            samples.clear();
            return true;
        }
        return false;
    }

    /**
     * 중력 방향으로 투영해 휴대폰을 돌려도 수직 방향이 바뀌지 않게 한다
     */
    public static double vertical(float[] linear, float[] gravity) {
        if (linear == null || gravity == null || linear.length < 3 || gravity.length < 3)
            return Double.NaN;
        double norm = Math.sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]);
        if (!Double.isFinite(norm) || norm < 7 || norm > 12) return Double.NaN;
        return (linear[0] * gravity[0] + linear[1] * gravity[1] + linear[2] * gravity[2]) / norm;
    }
}
