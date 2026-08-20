package example.mediaexport;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

public final class CreatorExportService {
    private static final String CONTENT_TYPE = "text/csv";
    private final StorageGateway storage;
    private final ExportConfig config;
    private final Clock clock;

    public CreatorExportService(StorageGateway storage, ExportConfig config, Clock clock) {
        this.storage = storage;
        this.config = config;
        this.clock = clock;
    }

    public ExportDelivery export(ExportRequest request) {
        if (!"COMPLETED".equals(request.processingStatus())) {
            throw new ExportRejected("ASSET_NOT_READY", "CSV delivery requires a completed processing job", 409);
        }

        String key = "creator_" + safe(request.creatorId()) + "_asset_" + safe(request.assetId()) + ".csv";
        byte[] csv = csv(request).getBytes(StandardCharsets.UTF_8);
        storage.ensureBucket(config.bucket());
        String putUrl = storage.presignPut(config.bucket(), key, CONTENT_TYPE, csv.length,
                "export-" + safe(request.assetId()) + "-" + safe(request.jobId()));
        storage.upload(putUrl, csv, CONTENT_TYPE);
        if (!storage.objectExists(config.bucket(), key)) {
            throw new IllegalStateException("Export verification did not find the object");
        }
        String disposition = "attachment; filename=\"" + key + "\"";
        String downloadUrl = storage.presignDownload(config.bucket(), key, config.linkTtlSeconds(), disposition);
        return new ExportDelivery(key, downloadUrl, Instant.now(clock).plusSeconds(config.linkTtlSeconds()));
    }

    private String csv(ExportRequest request) {
        return "asset_id,creator_id,job_id,processing_status,duration_seconds\r\n"
                + cell(request.assetId()) + ',' + cell(request.creatorId()) + ',' + cell(request.jobId()) + ','
                + cell(request.processingStatus()) + ',' + request.durationSeconds() + "\r\n";
    }

    private static String cell(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }

    private static String safe(String value) {
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (cleaned.isBlank()) throw new ExportRejected("INVALID_IDENTIFIER", "Identifiers must contain letters or digits", 400);
        return cleaned;
    }

    public record ExportRequest(String assetId, String creatorId, String jobId, String processingStatus, long durationSeconds) {
        public ExportRequest {
            if (assetId == null || creatorId == null || jobId == null || processingStatus == null) {
                throw new ExportRejected("INVALID_REQUEST", "All identifiers and processingStatus are required", 400);
            }
            if (durationSeconds < 0) throw new ExportRejected("INVALID_DURATION", "durationSeconds cannot be negative", 400);
        }
    }

    public record ExportDelivery(String objectKey, String downloadUrl, Instant expiresAt) {}

    public static final class ExportRejected extends RuntimeException {
        private final String code;
        private final int status;
        ExportRejected(String code, String message, int status) { super(message); this.code = code; this.status = status; }
        public String code() { return code; }
        public int status() { return status; }
    }
}
