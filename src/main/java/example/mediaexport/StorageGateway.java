package example.mediaexport;

public interface StorageGateway {
    void ensureBucket(String bucket);

    String presignPut(String bucket, String key, String contentType, long maxBytes, String idempotencyKey);

    void upload(String signedUrl, byte[] content, String contentType);

    boolean objectExists(String bucket, String key);

    String presignDownload(String bucket, String key, int expiresSeconds, String disposition);
}
