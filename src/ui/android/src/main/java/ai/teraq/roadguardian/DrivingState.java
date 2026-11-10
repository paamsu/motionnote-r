package ai.teraq.roadguardian;

/**
 * 5초 안에 받은 P단이면 정차 화면을 연다. OEM UX 승인은 따로 확인한다
 */
public final class DrivingState {
    private String gear;
    private long observedAt = -1;
    private boolean simulation;
    private boolean simulatedMoving;
    private final boolean debug;

    public DrivingState(boolean debug) {
        this.debug = debug;
        this.simulation = false;
    }

    public void useSimulation(boolean enabled) {
        if (enabled && !debug) throw new IllegalStateException("Simulation is debug-only");
        simulation = enabled;
    }

    public void setSimulatedMoving(boolean moving) {
        simulatedMoving = moving;
    }

    public boolean isSimulation() {
        return simulation;
    }

    public void observe(String value, long now) {
        gear = value;
        observedAt = now;
    }

    public boolean restricted(long now) {
        if (simulation) return simulatedMoving;
        return observedAt < 0 || now < observedAt || now - observedAt >= 5000 || !"GEAR_PARK".equals(gear);
    }

    /**
     * 표시용 상태. 접근 허용은 restricted()와 OEM UX 제한이 결정한다
     */
    public String briefState(long now) {
        if (simulation) return simulatedMoving ? "simulated_drive" : "simulated_park";
        if (observedAt < 0 || now < observedAt || now - observedAt >= 5000 || gear == null)
            return "unavailable";
        if ("GEAR_PARK".equals(gear)) return "parked";
        if ("GEAR_DRIVE".equals(gear)) return "drive";
        if ("GEAR_REVERSE".equals(gear)) return "reverse";
        if ("GEAR_NEUTRAL".equals(gear)) return "neutral";
        return "unavailable";
    }

    public String label(long now) {
        if (simulation) return simulatedMoving ? "가상 주행" : "가상 데이터 · 정차";
        if (observedAt < 0 || now < observedAt || now - observedAt >= 5000 || gear == null || "GEAR_UNKNOWN".equals(gear))
            return "차량 상태 미확인 · 상세 제한";
        return "GEAR_PARK".equals(gear) ? "Pleos P단 확인" : "Pleos 주행 기어 · 상세 제한";
    }
}
