package ai.teraq.roadguardian;

import java.io.*;
import java.net.*;
import java.security.*;

/**
 * 크기 제한이 있는 인증 MP4 다운로드. 실패하면 호출한 쪽이 결과 파일을 버린다
 */
public final class AuthenticatedVideoTransport {
    public static final long MAX_BYTES = 32L * 1024 * 1024;
    private final AuthenticatedTransport.Connections connections;

    public AuthenticatedVideoTransport() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    AuthenticatedVideoTransport(AuthenticatedTransport.Connections connections) {
        this.connections = connections;
    }

    public void download(String base, String id, String token, String hash, long size, OutputStream output) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || hash == null || !hash.matches("[0-9a-fA-F]{64}") || size <= 0 || size > MAX_BYTES)
            throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
        String target = BackendEndpoint.resolve(base, "/api/events/" + id + "/video", false);
        if (token == null || token.isEmpty() || token.length() > 16384 || !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new AccountAccessException(AccountAccessException.Reason.SIGN_IN);
        HttpURLConnection connection = connections.open(new URL(target));
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(6000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "video/mp4");
            int code = connection.getResponseCode();
            if (code == 401)
                throw new AccountAccessException(AccountAccessException.Reason.SIGN_IN);
            if (code == 404)
                throw new AccountAccessException(AccountAccessException.Reason.NOT_FOUND);
            if (code != 200)
                throw new AccountAccessException(AccountAccessException.Reason.UNAVAILABLE);
            copyVerifiedBody(connection, hash, size, output);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 바이트 수, 길이, 해시를 함께 확인한다. 접근 권한 확인은 호출한 쪽이 먼저 한다
     */
    static void copyVerifiedBody(HttpURLConnection connection, String hash, long size, OutputStream output) throws IOException {
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}") || size <= 0 || size > MAX_BYTES)
            throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String type = connection.getContentType();
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("video/mp4"))
            throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
        long length = connection.getContentLengthLong();
        if (length >= 0 && length != size)
            throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
        long total = 0;
        long started = System.nanoTime();
        try (InputStream input = connection.getInputStream()) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > size || System.nanoTime() - started > 60000000000L || Thread.currentThread().isInterrupted())
                    throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest())
            actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (total != size || !actual.toString().equalsIgnoreCase(hash))
            throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
    }
}
