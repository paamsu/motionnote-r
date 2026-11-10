package ai.teraq.roadguardian;

import java.net.URI;

/**
 * 연결 전에 주소를 확인한다. 개발용 주소는 릴리스 서버로 갈 수 없다
 */
public final class BackendEndpoint {
    private BackendEndpoint() {
    }

    public static String resolve(String base, String path, boolean development) {
        if (base == null || base.isBlank()) throw new IllegalStateException("서버 주소가 설정되지 않았습니다");
        URI origin;
        try {
            origin = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("서버 주소를 확인하세요", e);
        }
        String host = origin.getHost();
        boolean emulator = "10.0.2.2".equals(host) || "127.0.0.1".equals(host) || "localhost".equals(host);
        boolean secure = "https".equalsIgnoreCase(origin.getScheme());
        if (host == null || origin.getUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))
                || !(secure || development && emulator && "http".equalsIgnoreCase(origin.getScheme())))
            throw new IllegalArgumentException("HTTPS 서버 주소를 확인하세요");
        if (path == null || !path.matches("/api/[A-Za-z0-9_/-]+") || path.contains("//"))
            throw new IllegalArgumentException("API 경로를 확인하세요");
        if (!development && (path.equals("/api/demo") || path.startsWith("/api/demo/")
                || path.equals("/api/dev") || path.startsWith("/api/dev/")
                || path.equals("/api/scenarios") || path.startsWith("/api/scenarios/")))
            throw new IllegalStateException("개발용 기능은 배포 서버에서 실행할 수 없습니다");
        return base.replaceAll("/+$", "") + path;
    }
}
