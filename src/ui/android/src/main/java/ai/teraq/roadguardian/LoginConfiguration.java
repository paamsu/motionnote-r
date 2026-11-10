package ai.teraq.roadguardian;

import java.net.URI;

public final class LoginConfiguration {
    public static final String REDIRECT = "ai.teraq.roadguardian:/oauth2redirect";

    public static void validate(String issuer, String clientId) {
        secureEndpoint(issuer);
        if (clientId == null || clientId.isBlank() || clientId.length() > 256 || clientId.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("로그인 클라이언트 설정을 확인하세요");
    }

    public static void secureEndpoint(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("로그인 서버가 설정되지 않았습니다");
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null)
            throw new IllegalArgumentException("로그인 서버의 HTTPS 주소를 확인하세요");
    }
}
