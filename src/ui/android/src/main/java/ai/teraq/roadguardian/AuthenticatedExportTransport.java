package ai.teraq.roadguardian;

import java.io.*;
import java.net.*;

/**
 * 크기 제한이 있는 ZIP 전송. 압축 내용 확인은 받는 쪽에서 한다
 */
public final class AuthenticatedExportTransport {
    public static final int MAX_BYTES = 40 * 1024 * 1024;
    private final AuthenticatedTransport.Connections connections;

    public AuthenticatedExportTransport() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    AuthenticatedExportTransport(AuthenticatedTransport.Connections connections) {
        this.connections = connections;
    }

    public long download(String base, String id, String token, OutputStream output) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
        return downloadPath(base, "/api/events/" + id + "/export", token, output, MAX_BYTES);
    }

    public long downloadMeasurement(String base, String id, String before, String after, String token, OutputStream output) throws IOException {
        for (String value : new String[]{id, before, after})
            if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
        return downloadPath(base, "/api/events/" + id + "/measurement-comparison/" + before + "/" + after + "/export", token, output, 3 * 1024 * 1024);
    }

    private long downloadPath(String base, String path, String token, OutputStream output, int limit) throws IOException {
        String target = BackendEndpoint.resolve(base, path, false);
        if (token == null || token.isEmpty() || token.length() > 16384 || !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new AccountAccessException(AccountAccessException.Reason.SIGN_IN);
        HttpURLConnection connection = connections.open(new URL(target));
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(6000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/zip");
            int code = connection.getResponseCode();
            if (code == 401)
                throw new AccountAccessException(AccountAccessException.Reason.SIGN_IN);
            if (code == 404)
                throw new AccountAccessException(AccountAccessException.Reason.NOT_FOUND);
            if (code != 200)
                throw new AccountAccessException(AccountAccessException.Reason.UNAVAILABLE);
            String type = connection.getContentType();
            long length = connection.getContentLengthLong();
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/zip") || length > limit)
                throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
            long total = 0;
            byte[] signature = new byte[4];
            long started = System.nanoTime();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > limit - total || System.nanoTime() - started > 60000000000L || Thread.currentThread().isInterrupted())
                        throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
                    for (int i = 0; i < count && total + i < 4; i++)
                        signature[(int) total + i] = buffer[i];
                    output.write(buffer, 0, count);
                    total += count;
                }
            }
            if ((length >= 0 && length != total) || total < 4 || signature[0] != 80 || signature[1] != 75 || signature[2] != 3 || signature[3] != 4)
                throw new AccountAccessException(AccountAccessException.Reason.RESPONSE);
            return total;
        } finally {
            connection.disconnect();
        }
    }
}
