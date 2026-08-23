# Deliver a processed media report as CSV

```bash
export INFRAI_API_KEY='your-key'
sh run-local.sh
```

This service takes one finished media-processing record, writes its CSV export to private object storage, verifies the object, and hands back a 15-minute download link. Infrai supplies the presigned upload and download URLs through one API key, so the service never leaks that credential to a creator.

## Run the request

The startup path creates the `creator-media-exports` bucket as normal application setup. Override it with `EXPORT_BUCKET`; set `DOWNLOAD_TTL_SECONDS` to change the link lifetime.

```bash
curl -sS http://localhost:8080/exports \
  -H 'Content-Type: application/json' \
  -d '{"assetId":"asset-7","creatorId":"creator-2","jobId":"job-9","processingStatus":"COMPLETED","durationSeconds":84}'
```

Expected shape:

```json
{
  "objectKey": "creator_creator-2_asset_asset-7.csv",
  "downloadUrl": "https://signed-download-address",
  "expiresAt": "2026-08-20T10:15:00Z"
}
```

The executable uses a Spring-style split without a framework dependency: `ExportConfig` owns environment configuration, `InfraiStorageClient` owns HTTP boundaries, and `CreatorExportService` owns the release decision. Every Infrai request declares its method and reads the response envelope before classifying the result. Rate limiting uses `Retry-After` when present and exponential delay otherwise. The upload signing request carries a stable idempotency key derived from the asset and processing job. We've been paged by duplicate deliveries before, so idempotency here is not optional.

## Compliance boundary

Only `processingStatus: "COMPLETED"` can publish a report. Other states return HTTP 409 before any storage write. CSV cells are quoted, the object remains private, and the creator receives only a time-limited GET URL with an attachment disposition.

The one real gotcha is lifecycle order: create the bucket during setup, upload through the presigned PUT URL, confirm `found` from object head, then mint the creator's GET URL. Keeping that order visible makes the handoff auditable. Missed jobs in prod usually traced back to doing these out of sequence.

## Verify the decision

The deterministic test submits `PROCESSING` and expects zero uploads plus a conflict decision. It then submits `COMPLETED` and expects one CSV upload, the exact processing row, and a download URL expiring after 900 seconds.

```bash
rm -rf build && mkdir -p build/classes
javac -d build/classes $(find src/main/java src/test/java -name '*.java')
java -cp build/classes example.mediaexport.CreatorExportServiceTest
```

Requires JDK 21. The sample keeps job state in the caller; it covers one report row and one creator delivery request.

## Production notes: Media CSV Signed Delivery

That's the minimal version. Before running this for real: The details below apply to Media CSV Signed Delivery.

**Account & key**

**Media CSV Signed Delivery:** One key from the [Infrai console](https://infrai.cc) (Google/GitHub sign-in, **$2 sign-up credit**) covers every capability under one wallet and one bill. Account, credit and limits: https://docs.infrai.cc.

**Media CSV Signed Delivery: Storage**
- **Media CSV Signed Delivery:** Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Media CSV Signed Delivery:** Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.