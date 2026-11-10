package ai.teraq.roadguardian;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 휴대폰의 현재 시간대로 날짜 차이를 센다. 24시간 단위로 자르지 않는다
 */
public final class ParkingAge {
    private ParkingAge() {
    }

    public static String label(double parkedSeconds, long nowMillis, ZoneId zone) {
        if (!Double.isFinite(parkedSeconds) || parkedSeconds < 0 || parkedSeconds > nowMillis / 1000.0)
            return "기기 시간 확인 필요";
        long days = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli((long) (parkedSeconds * 1000)).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate());
        if (days == 0) return "오늘";
        if (days == 1) return "어제";
        return days + "일 전";
    }
}
