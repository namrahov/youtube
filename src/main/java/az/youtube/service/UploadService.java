package az.youtube.service;

import az.youtube.config.UploadProperties;
import az.youtube.dto.event.VideoUploadedEvent;
import az.youtube.dto.request.InitiateUploadRequest;
import az.youtube.dto.response.InitiateUploadResponse;
import az.youtube.dto.response.PartUrlsResponse;
import az.youtube.dto.response.PartUrlsResponse.PartUrl;
import az.youtube.dto.response.UploadProgressResponse;
import az.youtube.dto.response.VideoResponse;
import az.youtube.entity.VideoEntity;
import az.youtube.entity.VideoStatus;
import az.youtube.messaging.Topics;
import az.youtube.repository.VideoRepository;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.ObjectStorage.UploadedPart;
import az.youtube.storage.StorageKeys;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Upload lifecycle. The file goes from the client straight to S3 in parts (presigned URLs); this service only
 * opens the upload, signs part URLs, verifies the result and records it. Calls to S3 are made outside database
 * transactions, so a slow S3 never holds a connection or a row lock.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final int MAX_URLS_PER_REQUEST = 100;

    private final VideoRepository videos;
    private final ObjectStorage storage;
    private final OutboxService outbox;
    private final VideoService videoService;
    private final UploadProperties properties;
    private final TransactionTemplate tx;

    public UploadService(VideoRepository videos, ObjectStorage storage, OutboxService outbox,
                         VideoService videoService, UploadProperties properties, TransactionTemplate tx) {
        this.videos = videos;
        this.storage = storage;
        this.outbox = outbox;
        this.videoService = videoService;
        this.properties = properties;
        this.tx = tx;
    }

    public InitiateUploadResponse initiate(long userId, InitiateUploadRequest request) {
        if (request.sizeBytes() > properties.maxSize().toBytes()) {
            throw badRequest("File is larger than " + properties.maxSize());
        }
        UploadPlan plan;
        try {
            plan = UploadPlan.of(request.sizeBytes(), properties.partSize().toBytes());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }

        // 1. The row first (UPLOADING): from here on the upload is tracked and expires if abandoned.
        Instant now = Instant.now();
        VideoEntity video = tx.execute(status -> {
            long id = videos.nextId();
            return videos.save(VideoEntity.startUpload(id, userId, request.title(), request.contentType(),
                    request.sizeBytes(), plan.partSizeBytes(), plan.partCount(), StorageKeys.original(id),
                    now, now.plus(properties.uploadTtl())));
        });
        long videoId = video.getId();

        // 2. Then the S3 multipart upload. The key is ours (videos/{id}/original), never chosen by the client.
        String uploadId;
        try {
            uploadId = storage.createMultipartUpload(video.getOriginalS3Key(), request.contentType());
        } catch (SdkException e) {
            markUploadFailed(videoId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Storage unavailable, try again", e);
        }
        if (tx.execute(status -> videos.attachUploadId(videoId, uploadId, Instant.now())) == 0) {
            storage.abortMultipartUpload(video.getOriginalS3Key(), uploadId);
            throw conflict("Upload is no longer open");
        }
        return new InitiateUploadResponse(videoId, VideoStatus.UPLOADING, plan.partSizeBytes(), plan.partCount(),
                video.getUploadExpiresAt());
    }

    /** Signs URLs for parts {@code from .. from+count-1}; a resuming client asks only for the missing ones. */
    public PartUrlsResponse partUrls(long userId, long videoId, int from, int count) {
        VideoEntity video = requireOpenUpload(userId, videoId);
        if (from < 1 || from > video.getPartCount() || count < 1) {
            throw badRequest("Parts are numbered 1.." + video.getPartCount());
        }
        int last = Math.min(video.getPartCount(), from + Math.min(count, MAX_URLS_PER_REQUEST) - 1);
        Duration ttl = properties.partUrlTtl();
        List<PartUrl> urls = IntStream.rangeClosed(from, last)
                .mapToObj(n -> new PartUrl(n,
                        storage.presignUploadPart(video.getOriginalS3Key(), video.getS3UploadId(), n, ttl).toString()))
                .toList();
        return new PartUrlsResponse(urls, Instant.now().plus(ttl));
    }

    /** Asks S3 which parts it has, so a client that lost its state (crash, page reload) can resume. */
    public UploadProgressResponse progress(long userId, long videoId) {
        VideoEntity video = requireOpenUpload(userId, videoId);
        List<UploadedPart> parts;
        try {
            parts = storage.listParts(video.getOriginalS3Key(), video.getS3UploadId());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw conflict("Upload is no longer open in storage");
            }
            throw e;
        }
        List<Integer> missing = missingParts(video, parts);
        List<Integer> uploaded = IntStream.rangeClosed(1, video.getPartCount())
                .filter(n -> !missing.contains(n))
                .boxed()
                .toList();
        return new UploadProgressResponse(video.getPartCount(), uploaded, missing);
    }

    /**
     * Completes the upload. Safe to call again: a video that is already UPLOADED (or further) is returned as it is.
     * The part list comes from S3, not from the client, and the stored object is checked before it is accepted.
     * The UPLOADED status and the VideoUploaded event are written in one transaction (transactional outbox).
     */
    public VideoResponse complete(long userId, long videoId) {
        VideoEntity video = requireOwned(userId, videoId);
        switch (video.getStatus()) {
            case UPLOADING -> {
            }
            case UPLOAD_FAILED -> throw conflict("Upload failed or was aborted; start a new upload");
            default -> {
                return videoService.toResponse(video);
            }
        }
        if (video.getS3UploadId() == null) {
            throw conflict("Upload was not opened");
        }

        String key = video.getOriginalS3Key();
        try {
            List<UploadedPart> parts = storage.listParts(key, video.getS3UploadId());
            List<Integer> missing = missingParts(video, parts);
            if (!missing.isEmpty()) {
                throw conflict("Parts missing or of the wrong size: " + missing);
            }
            storage.completeMultipartUpload(key, video.getS3UploadId(), parts);
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            // NoSuchUpload: an earlier call already completed it in S3 and failed before the DB update.
            // The object check below decides.
        }

        OptionalLong stored = storage.objectSize(key);
        if (stored.isEmpty()) {
            throw conflict("Upload is not in storage; it was aborted or has expired");
        }
        if (stored.getAsLong() != video.getSizeBytes()) {
            storage.delete(key);
            markUploadFailed(videoId);
            throw badRequest("Stored size " + stored.getAsLong() + " does not match declared size "
                    + video.getSizeBytes());
        }

        tx.executeWithoutResult(status -> {
            // Only the call that makes the transition writes the event: a concurrent duplicate writes nothing.
            if (videos.transition(videoId, VideoStatus.UPLOADING, VideoStatus.UPLOADED, Instant.now()) == 1) {
                outbox.append(videoId, VideoUploadedEvent.TYPE, Topics.VIDEO_UPLOADED, String.valueOf(videoId),
                        new VideoUploadedEvent(videoId, key));
            }
        });
        return videoService.get(userId, videoId);
    }

    public void abort(long userId, long videoId) {
        VideoEntity video = requireOwned(userId, videoId);
        if (video.getStatus() != VideoStatus.UPLOADING) {
            throw conflict("Only an upload in progress can be aborted");
        }
        if (video.getS3UploadId() != null) {
            storage.abortMultipartUpload(video.getOriginalS3Key(), video.getS3UploadId());
        }
        markUploadFailed(videoId);
    }

    /**
     * Fails uploads still UPLOADING after their deadline and deletes their parts in S3: unfinished multipart
     * uploads are invisible in the bucket listing but are stored and billed until aborted.
     *
     * An S3 error on one upload skips it (tried again next sweep) instead of stopping the whole batch.
     *
     * @return how many uploads were expired; less than {@code limit} when there are no more or some failed
     */
    public int expireAbandoned(int limit) {
        int done = 0;
        for (VideoEntity video : videos.findExpiredUploads(Instant.now(), PageRequest.of(0, limit))) {
            try {
                if (video.getS3UploadId() != null) {
                    storage.abortMultipartUpload(video.getOriginalS3Key(), video.getS3UploadId());
                }
            } catch (SdkException e) {
                log.warn("Could not abort upload of video {}, will retry", video.getId(), e);
                continue;
            }
            markUploadFailed(video.getId());
            done++;
            log.info("Upload of video {} expired, multipart upload aborted", video.getId());
        }
        return done;
    }

    private static List<Integer> missingParts(VideoEntity video, List<UploadedPart> parts) {
        UploadPlan plan = new UploadPlan(video.getPartSizeBytes(), video.getPartCount());
        Map<Integer, Long> sizes = parts.stream()
                .collect(Collectors.toMap(UploadedPart::partNumber, UploadedPart::size, (a, b) -> b));
        return IntStream.rangeClosed(1, video.getPartCount())
                .filter(n -> !Long.valueOf(plan.expectedPartSize(n, video.getSizeBytes())).equals(sizes.get(n)))
                .boxed()
                .toList();
    }

    private void markUploadFailed(long videoId) {
        tx.executeWithoutResult(status ->
                videos.transition(videoId, VideoStatus.UPLOADING, VideoStatus.UPLOAD_FAILED, Instant.now()));
    }

    private VideoEntity requireOpenUpload(long userId, long videoId) {
        VideoEntity video = requireOwned(userId, videoId);
        if (video.getStatus() != VideoStatus.UPLOADING || video.getS3UploadId() == null) {
            throw conflict("Upload is not open (status " + video.getStatus() + ")");
        }
        return video;
    }

    /** Another user's upload is reported as not found, so ids cannot be probed. */
    private VideoEntity requireOwned(long userId, long videoId) {
        return videos.findById(videoId)
                .filter(v -> v.isOwnedBy(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
