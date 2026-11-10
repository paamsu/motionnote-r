package ai.teraq.roadguardian;

/**
 * 최근 기기 위치. 차로, 주차 층, 사건 위치를 확정하지 않는다
 */
public final class VehicleLocationSample {
    public final double latitude, longitude;
    public final float accuracy;
    public final long elapsedMillis;
    public final boolean mock;

    public VehicleLocationSample(double latitude, double longitude, float accuracy, long elapsedMillis, boolean mock) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.elapsedMillis = elapsedMillis;
        this.mock = mock;
    }

    public boolean usable(long now) {
        return Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180
                && Float.isFinite(accuracy) && accuracy > 0 && accuracy <= 100
                && elapsedMillis >= 0 && now >= elapsedMillis && now - elapsedMillis <= 15000;
    }
}
