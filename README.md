# YouTube — System Design, Built for Real

Learning project for topic 4/30 (YouTube system design). The design reference is
[docs/reference/04_YouTube_System_Design_Detailed_Explanation.pdf](docs/reference/04_YouTube_System_Design_Detailed_Explanation.pdf).

Stack: Java 26, Gradle, Spring Boot 4.1, JPA, PostgreSQL, Liquibase, Kafka, S3 (MinIO locally), same as `../whatsapp`.

## What is implemented (PDF sections 1–5)

```
Client ──POST /uploads──────────────▶ videos row UPLOADING + S3 CreateMultipartUpload
Client ──GET part-urls──────────────▶ presigned UploadPart URLs (signed locally, no S3 call)
Client ══PUT part 1..N═════════════▶ S3 / MinIO          (the 2 GB never passes through the backend)
Client ──POST complete──────────────▶ ListParts check → CompleteMultipartUpload → HeadObject size check
                                      └─ one transaction: UPLOADED + outbox(VideoUploaded)
OutboxPublisher ─▶ video.uploaded ─▶ JobPlanner: ffprobe (size, rotation, audio)
                                      └─ PROCESSING + a variant row per resolution (SKIPPED if larger than the source)
                                         + one outbox job per rendition worth encoding
OutboxPublisher ─▶ video.transcode.360p | .720p | .1080p | .4k   (one topic + worker pool each)
TranscodeWorker ─▶ claim → ffmpeg: fMP4 HLS segments → upload segments, then playlist → COMPLETED
                   → rewrite master.m3u8 (measured BANDWIDTH, CODECS, RESOLUTION) → derive video status
Player ─GET /api/videos/{id} → playbackUrl ─▶ CDN / MinIO: master.m3u8 → renditions → segments (ABR in the player)
```

| PDF section | Where |
|---|---|
| 1. Direct-to-S3 multipart upload | `UploadService`, `ObjectStorage`, `VideoController` |
| 2. Upload lifecycle, transactional outbox, orphan uploads | `UploadService.complete`, `OutboxService`, `OutboxPublisher`, `UploadExpiryJob` |
| 3. Kafka key fix: a topic and worker pool per resolution | `Topics`, `ProcessingService.planJobs`, `TranscodeWorker` |
| 4. Progressive availability, per-variant status | `video_variants`, `ProcessingService.completeVariant/failVariant`, `VideoStatusCalculator`, `ManifestWriter` |
| 5. HLS segments, manifests, adaptive bitrate | `FfmpegTranscoder`, `FfprobeMediaProbe`, `HlsPlaylist`, `ManifestWriter`, `StorageKeys`, `static/player.html` |

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
- **Manifest.** `streams/{id}/master.m3u8` lists only the completed renditions and is rewritten whenever one more
  finishes (short `Cache-Control`, so a later viewer sees 4K once it exists). It is written under a lock on the
  video row, so two variants finishing together cannot leave an incomplete manifest behind.

Section 5 — streaming:

- **Segments, not one MP4.** Each rendition is HLS with fMP4 (CMAF) segments: `index.m3u8` + `init.mp4` +
  `seg_NNNNN.m4s`, 4 s each. A player starts after the first segment instead of downloading a whole file, and can
  change quality at every segment. fMP4 rather than MPEG-TS so a DASH `.mpd` can later point at the same segments.
- **Keyframe alignment across separate workers.** Each rendition is encoded by a different worker pool at a different
  time, so alignment cannot come from encoding them together. It comes from the encoder settings: a keyframe is forced
  every 2 s of presentation time (`-force_key_frames expr:gte(t,n_forced*2)`, scene-cut keyframes off) and segments
  are a multiple of that. Segment N starts at the same instant in 360p and in 4K, so switching is seamless.
  `FfmpegPipelineIT` checks that the segment cut points of 360p and 720p are identical.
- **The player decides (ABR).** The backend returns one `playbackUrl` (the master playlist). hls.js / Safari /
  ExoPlayer measure segment download speed and buffer level and pick the rendition for the next segment; the backend
  is not called per switch. For that to work the master playlist must be truthful: `BANDWIDTH` is the measured peak
  of the real segments (RFC 8216 rule: busiest run of segments ≥ half the target duration), `AVERAGE-BANDWIDTH` the
  measured average, `CODECS` the exact H.264 profile/level + AAC string (a player skips renditions it cannot decode),
  `RESOLUTION` the real output size. The encoder's rate is capped (`-maxrate`, `-bufsize`) so peaks stay near target.
- **No upscaling.** The planner runs ffprobe on a presigned URL (only the headers are read, not the 2 GB) and marks
  renditions above the source's shorter side `SKIPPED`; the lowest is always made. A 720p upload gets 360p + 720p and
  is `FULLY_PROCESSED` when both are done. Rotation flags are honoured: a portrait phone video stays portrait
  (720p = 720×1280). A file that is not a video is rejected (`PROCESSING_FAILED`) without retries.
- **Upload order.** Segments and `init.mp4` first, the rendition playlist last, the master playlist only after the
  variant is recorded: no playlist ever points at a file that is not there yet. All rendition files are written once
  under fixed keys and cached as `immutable`; a retried job overwrites the same keys.
- **Long jobs and Kafka.** A 4K encode can take far longer than Kafka's default `max.poll.interval.ms` (5 min); a
  consumer that does not poll in time is thrown out of its group and its job goes to a second worker while the first
  still runs. Each pool's `max.poll.interval.ms` is therefore `job-timeout + transfer-allowance` of its resolution,
  and ffmpeg is killed at `job-timeout`. A job is redelivered only when its worker died. (A lease + heartbeat on the
  variant row would also cover a worker that stalls without dying; not needed while timeout < poll interval.)
- **Public streams, private originals.** `originals/{id}` and `streams/{id}/...` are separate prefixes. Locally the
  app lets anyone GET `streams/*` in MinIO (`S3_PUBLIC_STREAMS`); in production the bucket stays private and only the
  CDN reads it, and `PLAYBACK_BASE_URL` is the CDN domain.
- **Startup latency (p95 < 2 s)** is a player + CDN concern: the demo player starts at the lowest rendition and steps
  up; short segments keep the first download small. Measuring it needs client-side metrics (not built).

Not yet: DASH manifest, CDN (section 6), client playback metrics, moderation and publication rules, signed URLs or
cookies for private videos.

## API

All requests need the `X-User-Id` header (see Authentication).

| Method | Path | |
|---|---|---|
| POST | `/api/videos/uploads` | `{"title","contentType":"video/mp4","sizeBytes"}` → `videoId`, `partSizeBytes`, `partCount` |
| GET | `/api/videos/{id}/upload/part-urls?from=1&count=32` | presigned PUT URLs (at most 100 per call) |
| GET | `/api/videos/{id}/upload/progress` | uploaded and missing part numbers |
| POST | `/api/videos/{id}/upload/complete` | verify and finish; safe to repeat |
| DELETE | `/api/videos/{id}/upload` | abort |
| GET | `/api/videos/{id}` | status, per-resolution variant status and size, `playbackUrl` once playable |

The client PUTs part `n` (bytes `(n-1)·partSize … n·partSize`) to its URL. A browser client also needs CORS on the
bucket; MinIO allows all origins by default.

## Run locally

- JDK 26: `C:\Users\AZINTELECOM\.jdks\temurin-26.0.2.1` (Project SDK and Gradle JVM in IntelliJ).
- Create the database: `CREATE DATABASE youtube;` on `localhost:5433`.
- Start Kafka (port 9095) and MinIO (S3 API 9010, console http://localhost:9011, `minioadmin`/`minioadmin`):
  `docker compose -f docker/docker-compose.yml up -d`. The app creates the `youtube-videos` bucket on startup.
- MinIO is no longer published on Docker Hub; compose and the integration tests use the locally built
  `ecis-local/minio:RELEASE.2025-09-07T16-13-09Z` (override with `MINIO_IMAGE`).
- ffmpeg and ffprobe on the `PATH` (`winget install Gyan.FFmpeg`), or `FFMPEG_PATH` / `FFPROBE_PATH`. Without them,
  run with `TRANSCODING_ENGINE=simulated` (no real segments, nothing to play).
- The app listens on port `8082` (`SERVER_PORT`), so it can run next to twitter (`8080`) and whatsapp (`8081`).
- Demo player: http://localhost:8082/player.html — enter a video id and play it; throttle the network in DevTools to
  watch hls.js switch renditions.

```
./gradlew bootRun
./gradlew test               # unit tests
./gradlew integrationTest    # PostgreSQL, Kafka and MinIO in Docker (Testcontainers); FfmpegPipelineIT needs ffmpeg
```

## Authentication (development)

Same as whatsapp: the caller's id comes from the `X-User-Id` header (the API-gateway pattern, where the gateway
validates the token and forwards the user id). A missing, non-numeric or non-positive value returns `401`.
Controllers get the id with `@CurrentUser long userId`; business code depends only on `CurrentUserProvider`, so
the header implementation can later be swapped for Spring Security / JWT.
