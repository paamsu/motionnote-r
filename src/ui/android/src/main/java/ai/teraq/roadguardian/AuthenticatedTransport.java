package ai.teraq.roadguardian;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTPS만 쓴다. 리디렉션에는 인증 토큰을 보내지 않고, 오류에 응답 본문을 담지 않는다
 */
public final class AuthenticatedTransport {
    interface Connections {
        HttpURLConnection open(URL url) throws IOException;
    }

    private final Connections connections;

    public AuthenticatedTransport() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    AuthenticatedTransport(Connections connections) {
        this.connections = connections;
    }

    public String events(String base, String token) throws IOException {
        return read(base, "/api/events", token);
    }

    public String capabilities(String base, String token) throws IOException {
        return read(base, "/api/capabilities", token);
    }

    public String myVehicles(String base, String token) throws IOException {
        return read(base, "/api/my-vehicles", token);
    }

    public String createOwnerCase(String base, String body, String token) throws IOException {
        if (body == null) throw new IllegalArgumentException("Owner case required");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > 16384)
            throw new IllegalArgumentException("Owner case too large");
        return exchange(base, "/api/cases", token, payload);
    }

    public String event(String base, String id, String token) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("사건 번호를 확인하지 못했습니다");
        return read(base, "/api/events/" + id, token);
    }

    public String eventPage(String base, String cursor, String token) throws IOException {
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,256}"))
            throw new IllegalArgumentException("기록 목록 위치를 확인하지 못했습니다");
        return read(base, "/api/event-pages" + (cursor == null ? "" : "/" + cursor), token);
    }

    public String casePage(String base, String category, String cursor, String token) throws IOException {
        if (category == null || !java.util.Arrays.asList("all", "road", "maintenance", "incident").contains(category))
            throw new IllegalArgumentException("기록 분류를 확인하세요");
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,256}"))
            throw new IllegalArgumentException("기록 목록 위치를 확인하세요");
        return read(base, "/api/case-pages/" + category + (cursor == null ? "" : "/" + cursor), token);
    }

    private static String notePath(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("사건 번호를 확인하세요");
        return "/api/events/" + id + "/review-note";
    }

    public String reviewNote(String base, String id, String token) throws IOException {
        return read(base, notePath(id), token);
    }

    public String saveReviewNote(String base, String id, String body, String token) throws IOException {
        if (body == null) throw new IllegalArgumentException("메모를 확인하세요");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > 8192)
            throw new IllegalArgumentException("메모 길이를 확인하세요");
        return exchange(base, notePath(id), token, payload);
    }

    private static String observationPath(String id) {
        return notePath(id).replace("/review-note", "/sdk-observations");
    }

    public String sdkObservations(String base, String id, String token) throws IOException {
        return read(base, observationPath(id), token);
    }

    private static String measurementPath(String id) {
        return notePath(id).replace("/review-note", "/measurement-runs");
    }

    private static String measurementId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("측정 기록 번호를 확인하세요");
        return id;
    }

    public String measurements(String base, String id, String token) throws IOException {
        return read(base, measurementPath(id), token);
    }

    public String measurement(String base, String id, String run, String token) throws IOException {
        return read(base, measurementPath(id) + "/" + measurementId(run), token);
    }

    public String saveMeasurement(String base, String id, String body, String token) throws IOException {
        if (body == null) throw new IllegalArgumentException("측정 파일을 확인하세요");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > 256 * 1024)
            throw new IllegalArgumentException("측정 파일 크기를 확인하세요");
        return exchange(base, measurementPath(id), token, payload);
    }

    public String measurementComparison(String base, String id, String before, String after, String token) throws IOException {
        return read(base, notePath(id).replace("/review-note", "/measurement-comparison/") + measurementId(before) + "/" + measurementId(after), token);
    }

    public String powertrain(String base, String id, String start, String end, String token) throws IOException {
        if (start == null || end == null || !start.matches("[0-9]{8}") || !end.matches("[0-9]{8}"))
            throw new IllegalArgumentException("조회 날짜를 확인하세요");
        return exchange(base, notePath(id).replace("/review-note", "/powertrain-data/") + start + "/" + end, token, null, 30000);
    }

    public String saveSdkObservation(String base, String id, String body, String token) throws IOException {
        if (body == null) throw new IllegalArgumentException("Observation required");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > 32768)
            throw new IllegalArgumentException("Observation too large");
        return exchange(base, observationPath(id), token, payload);
    }

    private String read(String base, String path, String token) throws IOException {
        return exchange(base, path, token, null);
    }

    private static String photosPath(String id) {
        return notePath(id).replace("/review-note", "/photos");
    }

    public String photos(String base, String id, String token) throws IOException {
        return read(base, photosPath(id), token);
    }

    public String photo(String base, String id, String photoId, String token) throws IOException {
        if (photoId == null || !photoId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Photo UUID required");
        return read(base, photosPath(id) + "/" + photoId, token);
    }

    public String savePhoto(String base, String id, String body, String token) throws IOException {
        if (body == null) throw new IllegalArgumentException("Photo required");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > 1500000)
            throw new IllegalArgumentException("Photo request too large");
        return exchange(base, photosPath(id), token, payload);
    }

    private String exchange(String base, String path, String token, byte[] payload) throws IOException {
        return exchange(base, path, token, payload, 6000);
    }

    private String exchange(String base, String path, String token, byte[] payload, int readTimeout) throws IOException {
        String target = BackendEndpoint.resolve(base, path, false);
        if (token == null || token.isEmpty() || token.length() > 16384 || !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IllegalArgumentException("다시 로그인해 주세요");
        HttpURLConnection connection = connections.open(new URL(target));
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            if (payload != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(payload.length);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }
            int code = connection.getResponseCode();
            if (payload != null && code == 409)
                throw new AccountAccessException(AccountAccessException.Reason.CONFLICT);
            if (payload != null && code == 403)
                throw new AccountAccessException(AccountAccessException.Reason.FORBIDDEN);
            if (code == 404)
                throw new AccountAccessException(AccountAccessException.Reason.NOT_FOUND);
            if (code == 401)
                throw new AccountAccessException(AccountAccessException.Reason.SIGN_IN);
            if (code != 200)
                throw new AccountAccessException(AccountAccessException.Reason.UNAVAILABLE);
            String type = connection.getContentType();
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
                throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 4 * 1024 * 1024)
                        throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
                    output.write(buffer, 0, count);
                }
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }
}
