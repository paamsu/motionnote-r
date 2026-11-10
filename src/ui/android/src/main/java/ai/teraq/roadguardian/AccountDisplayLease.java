package ai.teraq.roadguardian;

/**
 * 화면 표시 허용 시간. 기기 시계를 되돌려도 경과 시간으로 계산해 늘어나지 않는다
 */
public final class AccountDisplayLease {
    private final long wallDeadline, elapsedDeadline, startedElapsed;

    public AccountDisplayLease(long tokenExpiry, long wallNow, long elapsedNow) {
        long wall = 0, elapsed = 0;
        try {
            wall = Math.subtractExact(tokenExpiry, 30_000L);
            long remaining = Math.min(Math.subtractExact(wall, wallNow), 900_000L);
            if (wallNow < 0 || elapsedNow < 0 || remaining <= 0) throw new ArithmeticException();
            elapsed = Math.addExact(elapsedNow, remaining);
        } catch (ArithmeticException invalid) {
            wall = 0;
            elapsed = 0;
        }
        wallDeadline = wall;
        elapsedDeadline = elapsed;
        startedElapsed = elapsedNow;
    }

    public boolean valid(long wallNow, long elapsedNow) {
        return wallNow >= 0 && elapsedNow >= startedElapsed && elapsedNow >= 0
                && wallNow < wallDeadline && elapsedNow < elapsedDeadline;
    }
}
