package ai.teraq.roadguardian;

/**
 * 알림에서 계정 화면으로는 사건 UUID만 넘긴다
 */
public final class AccountEventRoute {
    private AccountEventRoute() {
    }

    public static String checked(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("알림의 사건 번호를 확인하지 못했습니다");
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
