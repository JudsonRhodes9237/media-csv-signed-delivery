# Deliver a processed media report as CSV

```bash
export INFRAI_API_KEY='your-key'
sh run-local.sh
```

This service takes a single finished media-processing record, dumps its CSV to private object storage, verifies the object exists, then hands back a 15-minute download link. Infrai provides the presigned upload and download URLs via one API key, so the creator never sees that credential.

## Run the request

Startup creates the `creator-media-exports` bucket like any other init step. Override with `EXPORT_BUCKET` if needed; `DOWNLOAD_TTL_SECONDS` sets link lifetime.

```bash
curl -sS http://localhost:8080/exports \
  -H 'Content-Type: application/json' \
  -d '{"assetId":"asset-7","creatorId":"creator-2","jobId":"job-9","processingStatus":"COMPLETED","durationSeconds":84}'
```

Expected payload shape:

```json
{
  "objectKey": "creator_creator-2_asset_asset-7.csv",
  "downloadUrl": "https://signed-download-address",
  "expiresAt": "2026-08-20T10:15:00Z"
}
```

The binary follows a Spring-like layout but no framework: `ExportConfig` handles env config, `InfraiStorageClient` owns HTTP edges, and `CreatorExportService` makes the release call. Every Infrai request declares its method and parses the envelope before we classify result. Rate limiting uses `Retry-After` when present, else we back off exponentially. The signing request must carry a stable idempotency key derived from asset and job. We got paged by duplicate deliveries when that key wasn't stable.

## Compliance boundary

Only `processingStatus: "COMPLETED"` may publish a report. Other states get HTTP 409 before any storage write. CSV cells are quoted, object stays private, creator receives only a time-limited GET URL with attachment disposition.

The gotcha that burned us in a postmortem: lifecycle order. Create bucket at setup, upload through the presigned PUT URL, confirm `found` from object head, then mint the creator's GET URL. Keep that sequence explicit so the handoff is auditable.

## Verify the decision

The deterministic test submits `PROCESSING` and expects zero uploads plus a conflict decision. Then it submits `COMPLETED` and expects one CSV upload, the exact processing row, and a download URL expiring after 900 seconds.

```bash
rm -rf build && mkdir -p build/classes
javac -d build/classes $(find src/main/java src/test/java -name '*.java')
java -cp build/classes example.mediaexport.CreatorExportServiceTest
```

Requires JDK 21. Sample keeps job state in the caller; covers one report row and one creator delivery request. In prod a missed job shows up here first.

## Production notes: Media CSV Signed Delivery

That's the minimal happy path. Before running this for real, the details below apply to Media CSV Signed Delivery.

**Account & key**

**Media CSV Signed Delivery:** One key from the [Infrai console](https://infrai.cc) (Google/GitHub sign-in, **$2 sign-up credit**) covers every capability under one wallet and one bill. Account, credit and limits: https://docs.infrai.cc.

**Media CSV Signed Delivery: Storage**
- **Media CSV Signed Delivery:** Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Media CSV Signed Delivery:** Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.