package ai.teraq.roadguardian;

/**
 * 막 바뀐 기어 신호는 알림 신호로만 쓴다. 주차나 위치를 확정하지 않는다
 */
public final class ParkingArrival {
    private long last = -1, parkSince = -1;
    private boolean armed;

    public boolean observe(String gear, long now) {
        if (now < 0 || (last >= 0 && (now <= last || now - last >= 5000))) {
            armed = false;
            parkSince = -1;
        }
        if (now < 0 || (last >= 0 && now <= last)) return false;
        last = now;
        if ("GEAR_DRIVE".equals(gear) || "GEAR_REVERSE".equals(gear)) {
            armed = true;
            parkSince = -1;
        } else if ("GEAR_PARK".equals(gear) && armed) {
            if (parkSince < 0) parkSince = now;
            if (now - parkSince >= 5000) {
                armed = false;
                parkSince = -1;
                return true;
            }
        } else if ("GEAR_NEUTRAL".equals(gear)) {
            parkSince = -1;
        } else {
            armed = false;
            parkSince = -1;
        }
        return false;
    }
}
