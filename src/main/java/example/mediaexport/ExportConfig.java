package example.mediaexport;

public record ExportConfig(String apiKey, String bucket, int port, int linkTtlSeconds) {
    public static ExportConfig fromEnvironment() {
        String apiKey = required("INFRAI_API_KEY");
        String bucket = System.getenv().getOrDefault("EXPORT_BUCKET", "creator-media-exports");
        int port = integer("PORT", 8080);
        int ttl = integer("DOWNLOAD_TTL_SECONDS", 900);
        return new ExportConfig(apiKey, bucket, port, ttl);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }

    private static int integer(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
