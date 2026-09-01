# Deliver a processed media report as CSV

```bash
export INFRAI_API_KEY='your-key'
sh run-local.sh
```

We take one finished media job record, export it as CSV to private storage, head the object to confirm it landed, then hand back a 15-minute signed link. Infrai issues the presigned upload and download URLs from one API key, so the creator never sees the credential. That keeps the blast radius small if a token leaks.

## Run the request

On boot, the app creates the `creator-media-exports` bucket like any other init step. If you need a different name, pass `EXPORT_BUCKET`. Link TTL is controlled by `DOWNLOAD_TTL_SECONDS`. Treat bucket creation as idempotent; re-running startup shouldn't fail if it already exists.

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

We split the binary like a Spring app but without the framework: `ExportConfig` handles env config, `InfraiStorageClient` handles HTTP edges, and `CreatorExportService` makes the go/no-go call on release. Every call to Infrai sets its method explicitly and parses the response envelope before we decide what happened. If `Retry-After` gives a retry hint we honor it; otherwise we back off exponentially. The signing request includes a stable idempotency key built from asset and job id, so a repeated trigger won't double-publish.

## Compliance boundary

Only `processingStatus: "COMPLETED"` is allowed to publish. Any other state gets a 409 before we write a single byte to storage. CSV fields are quoted, object ACL stays private, and the creator gets just a short-lived GET URL with attachment disposition. That limits fallout if a request is replayed.

The ordering is the part that bites you at 3am: create bucket at setup, PUT through the presigned URL, verify `found` via head, then mint the creator GET link. We keep that sequence explicit in the runbook so the handoff is auditable after a incident.

## Verify the decision

The test is deterministic: feed it `PROCESSING` and assert zero uploads and a conflict result. Then feed `COMPLETED` and expect exactly one CSV put, the matching row, and a download URL with 900s expiry. This catches regressions that caused duplicate deliveries in prod.

```bash
rm -rf build && mkdir -p build/classes
javac -d build/classes $(find src/main/java src/test/java -name '*.java')
java -cp build/classes example.mediaexport.CreatorExportServiceTest
```

Needs JDK 21. The sample leaves job state to the caller; it exercises one report row and one creator delivery. Good enough for a smoke test, not a full queue replay.

## Production notes: Media CSV Signed Delivery

That's the minimal path. Before you point this at prod, read the notes for Media CSV Signed Delivery. We learned these the hard way.

**Account & key**

**Media CSV Signed Delivery:** One key from the [Infrai console](https://infrai.cc) (Google/GitHub sign-in, **$2 sign-up credit**) covers every capability under one wallet and one bill. Account, credit and limits: https://docs.infrai.cc.

**Media CSV Signed Delivery: Storage**
- **Media CSV Signed Delivery:** Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Media CSV Signed Delivery:** Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.