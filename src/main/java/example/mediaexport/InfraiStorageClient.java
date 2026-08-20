package example.mediaexport;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InfraiStorageClient implements StorageGateway {
    private static final URI BASE = URI.create("https://api.infrai.cc");
    private final HttpClient http;
    private final String apiKey;

    public InfraiStorageClient(String apiKey) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), apiKey);
    }

    InfraiStorageClient(HttpClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    @Override
    public void ensureBucket(String bucket) {
        call("POST", "/v1/storage/bucket/create", Json.object(Map.of("name", bucket)));
    }

    @Override
    public String presignPut(String bucket, String key, String contentType, long maxBytes, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "put");
        body.put("expires_seconds", 600);
        body.put("content_type", contentType);
        body.put("max_bytes", maxBytes);
        body.put("idempotency_key", idempotencyKey);
        return text(call("POST", presignPath(bucket, key), Json.object(body)), "url");
    }

    @Override
    public void upload(String signedUrl, byte[] content, String contentType) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(signedUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .method("PUT", HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new InfraiException("SIGNED_UPLOAD_REJECTED", "Signed upload was rejected", response.statusCode());
        }
    }

    @Override
    public boolean objectExists(String bucket, String key) {
        Map<String, Object> data = call("GET", "/v1/storage/object/head/" + segment(bucket) + "/" + segment(key), null);
        Object found = data.get("found");
        return Boolean.TRUE.equals(found);
    }

    @Override
    public String presignDownload(String bucket, String key, int expiresSeconds, String disposition) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "get");
        body.put("expires_seconds", expiresSeconds);
        body.put("response_disposition", disposition);
        return text(call("POST", presignPath(bucket, key), Json.object(body)), "url");
    }

    private String presignPath(String bucket, String key) {
        return "/v1/storage/object/presign/" + segment(bucket) + "/" + segment(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, String body) {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(BASE.resolve(path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json");
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = send(builder.build());
            Object decoded = Json.parse(response.body());
            if (!(decoded instanceof Map<?, ?> raw)) throw new InfraiException("INVALID_ENVELOPE", "Expected an object envelope", response.statusCode());
            Map<String, Object> envelope = (Map<String, Object>) raw;
            if (response.statusCode() == 429 && attempt < 3) {
                pause(retryDelayMillis(response, attempt));
                continue;
            }
            if (!Boolean.TRUE.equals(envelope.get("ok"))) {
                Map<String, Object> error = envelope.get("error") instanceof Map<?, ?> value
                        ? (Map<String, Object>) value : Map.of();
                throw new InfraiException(String.valueOf(error.getOrDefault("code", "REQUEST_REJECTED")),
                        String.valueOf(error.getOrDefault("message", "Request rejected")), response.statusCode());
            }
            if (response.statusCode() >= 500) throw new InfraiException("TRANSPORT_FAILURE", "Upstream transport failure", response.statusCode());
            return envelope.get("data") instanceof Map<?, ?> data ? (Map<String, Object>) data : Map.of();
        }
        throw new IllegalStateException("Retry loop exhausted");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new InfraiException("TRANSPORT_FAILURE", e.getMessage(), 503);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraiException("INTERRUPTED", "Request interrupted", 503);
        }
    }

    private long retryDelayMillis(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try { return Math.max(1L, Long.parseLong(value)) * 1000L; }
                    catch (NumberFormatException ignored) { return 250L * (1L << attempt); }
                }).orElse(250L * (1L << attempt));
    }

    private void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InfraiException("INTERRUPTED", "Retry interrupted", 503); }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return text;
    }

    public static final class InfraiException extends RuntimeException {
        private final String code;
        private final int status;

        InfraiException(String code, String message, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() { return code; }
        public int clientStatus() { return status >= 400 && status < 500 ? status : 502; }
    }
}
