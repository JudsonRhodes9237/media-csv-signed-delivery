package example.mediaexport;

import example.mediaexport.CreatorExportService.ExportRejected;
import example.mediaexport.CreatorExportService.ExportRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class CreatorExportServiceTest {
    public static void main(String[] args) {
        FakeStorage storage = new FakeStorage();
        ExportConfig config = new ExportConfig("test-key", "media-exports", 8080, 900);
        CreatorExportService service = new CreatorExportService(storage, config,
                Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC));

        try {
            service.export(new ExportRequest("asset-7", "creator-2", "job-9", "PROCESSING", 84));
            throw new AssertionError("Processing assets must not be delivered");
        } catch (ExportRejected rejected) {
            check(rejected.status() == 409, "not-ready decision maps to conflict");
            check(storage.uploads == 0, "not-ready decision writes nothing");
        }

        var delivery = service.export(new ExportRequest("asset-7", "creator-2", "job-9", "COMPLETED", 84));
        check(storage.uploads == 1, "completed asset writes one CSV");
        check(storage.lastCsv.contains("\"asset-7\",\"creator-2\",\"job-9\",\"COMPLETED\",84"), "CSV carries the processing record");
        check(delivery.downloadUrl().equals("https://download.example/signed"), "delivery returns the signed download URL");
        check(delivery.expiresAt().equals(Instant.parse("2026-08-20T10:15:00Z")), "expiry is explicit");
        System.out.println("CreatorExportServiceTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeStorage implements StorageGateway {
        int uploads;
        String lastCsv = "";
        public void ensureBucket(String bucket) {}
        public String presignPut(String bucket, String key, String contentType, long maxBytes, String idempotencyKey) { return "https://upload.example/signed"; }
        public void upload(String signedUrl, byte[] content, String contentType) { uploads++; lastCsv = new String(content, java.nio.charset.StandardCharsets.UTF_8); }
        public boolean objectExists(String bucket, String key) { return true; }
        public String presignDownload(String bucket, String key, int expiresSeconds, String disposition) { return "https://download.example/signed"; }
    }
}
