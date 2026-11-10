package ai.teraq.roadguardian;

/**
 * 접수 응답만으로 상대가 요청을 확인했다고 보지 않는다
 */
public final class EmergencyDelivery {
    public static String state(String expected, String recipient, String status, String receipt, boolean valid) {
        if (!valid) return "evidence_invalid";
        if (!("119-mock".equals(expected) || "112-mock".equals(expected)) || !expected.equals(recipient))
            return "waiting";
        if ("retry_pending".equals(status)) return "retry";
        if ("acknowledged".equals(status) && receipt != null && receipt.matches("MOCK-[0-9a-f]{8}"))
            return "acknowledged";
        return "waiting";
    }
}
