# YouTube — System Design, Built for Real

Learning project for topic 4/30 (YouTube system design). The design reference is
[docs/reference/04_YouTube_System_Design_Detailed_Explanation.pdf](docs/reference/04_YouTube_System_Design_Detailed_Explanation.pdf).

Stack: Java 26, Gradle, Spring Boot 4.1, JPA, PostgreSQL, Liquibase, Kafka, S3 (MinIO locally), same as `../whatsapp`.

## What is implemented (PDF sections 1–4)

```
Client ──POST /uploads──────────────▶ videos row UPLOADING + S3 CreateMultipartUpload
Client ──GET part-urls──────────────▶ presigned UploadPart URLs (signed locally, no S3 call)
Client ══PUT part 1..N═════════════▶ S3 / MinIO          (the 2 GB never passes through the backend)
Client ──POST complete──────────────▶ ListParts check → CompleteMultipartUpload → HeadObject size check
                                      └─ one transaction: UPLOADED + outbox(VideoUploaded)
OutboxPublisher ─▶ video.uploaded ─▶ JobPlanner: PROCESSING + 4 variant rows + 4 outbox jobs
OutboxPublisher ─▶ video.transcode.360p | .720p | .1080p | .4k   (one topic + worker pool each)
TranscodeWorker ─▶ claim variant → transcode → COMPLETED → rewrite master.m3u8 → derive video status
```

| PDF section | Where |
|---|---|
| 1. Direct-to-S3 multipart upload | `UploadService`, `ObjectStorage`, `VideoController` |
| 2. Upload lifecycle, transactional outbox, orphan uploads | `UploadService.complete`, `OutboxService`, `OutboxPublisher`, `UploadExpiryJob` |
| 3. Kafka key fix: a topic and worker pool per resolution | `Topics`, `ProcessingService.planJobs`, `TranscodeWorker` |
| 4. Progressive availability, per-variant status | `video_variants`, `ProcessingService.completeVariant/failVariant`, `VideoStatusCalculator`, `ManifestWriter` |

Key decisions:

- **Upload row first.** `UPLOADING` is written before the first byte is sent. An upload still open after
  `youtube.upload.upload-ttl` (24 h) is aborted in S3 and marked `UPLOAD_FAILED`; unfinished multipart uploads
  are invisible in the bucket but still billed.
- **Resume.** `GET /upload/progress` asks S3 which parts it has. The client sends only the missing ones and asks
  for fresh part URLs if the old ones expired.
- **Verify, don't trust.** On complete, the part list comes from S3 (ListParts), every part size is checked against
  the plan, and the final object size against the declared size.
- **Outbox instead of dual write.** `UPLOADED` and the event commit together. While Kafka is down, events wait
  in the table. The publisher reads only unpublished rows (partial index), never the whole `videos` table.
- **Why not one topic keyed by video id or `original_s3_key`:** all four jobs of a video would land on the
  same partition and run one after another. `videoId:resolution` spreads them only by chance. Separate topics give
  each resolution its own parallelism and its own thread count (`youtube.transcoding.concurrency.*`), so slow
  4K jobs never delay 360p.
- **Progressive availability.** Each job owns its `video_variants` row. The video status is *derived* from those
  rows (`VideoStatusCalculator`), never counted, so a duplicate "completed" event changes nothing. The first
  finished variant makes the video `PLAYABLE`; all of them make it `FULLY_PROCESSED`. A variant that fails after
  its retries becomes `FAILED`: the video stays `PLAYABLE` with the others and never becomes `FULLY_PROCESSED`.
- **Manifest.** `videos/{id}/master.m3u8` lists only the completed renditions and is rewritten whenever one more
  finishes (short `Cache-Control`, so a later viewer sees 4K once it exists). It is written under a lock on the
  video row, so two variants finishing together cannot leave an incomplete manifest behind.

Not yet (later steps): real ffmpeg encoding and segmenting (PDF section 5). `SimulatedTranscoder` waits as long as a
real job would and writes a playlist without segments. Also not built: CDN (section 6), moderation and
publication rules, and checking the source resolution so a 1080p upload is not "upscaled" to 4K.

## API

All requests need the `X-User-Id` header (see Authentication).

| Method | Path | |
|---|---|---|
| POST | `/api/videos/uploads` | `{"title","contentType":"video/mp4","sizeBytes"}` → `videoId`, `partSizeBytes`, `partCount` |
| GET | `/api/videos/{id}/upload/part-urls?from=1&count=32` | presigned PUT URLs (at most 100 per call) |
| GET | `/api/videos/{id}/upload/progress` | uploaded and missing part numbers |
| POST | `/api/videos/{id}/upload/complete` | verify and finish; safe to repeat |
| DELETE | `/api/videos/{id}/upload` | abort |
| GET | `/api/videos/{id}` | status, per-resolution variant status, manifest key |

The client PUTs part `n` (bytes `(n-1)·partSize … n·partSize`) to its URL. A browser client also needs CORS on the
bucket; MinIO allows all origins by default.

## Run locally

- JDK 26: `C:\Users\AZINTELECOM\.jdks\temurin-26.0.2.1` (Project SDK and Gradle JVM in IntelliJ).
- Create the database: `CREATE DATABASE youtube;` on `localhost:5433`.
- Start Kafka (port 9095) and MinIO (S3 API 9010, console http://localhost:9011, `minioadmin`/`minioadmin`):
  `docker compose -f docker/docker-compose.yml up -d`. The app creates the `youtube-videos` bucket on startup.
- The app listens on port `8082` (`SERVER_PORT`), so it can run next to twitter (`8080`) and whatsapp (`8081`).

```
./gradlew bootRun
./gradlew test               # unit tests
./gradlew integrationTest    # PostgreSQL, Kafka and MinIO in Docker (Testcontainers)
```

## Authentication (development)

Same as whatsapp: the caller's id comes from the `X-User-Id` header (the API-gateway pattern, where the gateway
validates the token and forwards the user id). A missing, non-numeric or non-positive value returns `401`.
Controllers get the id with `@CurrentUser long userId`; business code depends only on `CurrentUserProvider`, so
the header implementation can later be swapped for Spring Security / JWT.
