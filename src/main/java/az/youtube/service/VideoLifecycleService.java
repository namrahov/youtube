package az.youtube.service;

import az.youtube.config.LifecycleProperties;
import az.youtube.dto.event.VideoChangedEvent;
import az.youtube.dto.event.VideoDeletedEvent;
import az.youtube.entity.VideoEntity;
import az.youtube.entity.VideoStatus;
import az.youtube.messaging.Topics;
import az.youtube.repository.VideoRepository;
import az.youtube.storage.CdnInvalidator;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.StorageKeys;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Storage lifecycle: what happens to a video's files after it is deleted or has failed.
 * <ul>
 *   <li>Delete: the row becomes DELETED at once (invisible in the API, removed from search); the files are removed
 *       asynchronously by {@link #purgeStorage}, which may take a while for thousands of segments.</li>
 *   <li>Failed videos: their original is kept for {@code failed-original-retention}, then deleted.</li>
 *   <li>Abandoned uploads: aborted by {@link UploadService#expireAbandoned}.</li>
 *   <li>Old originals of live videos: moved to a cheaper storage class by a bucket lifecycle rule
 *       ({@code docs/s3-lifecycle.json}), not by the application.</li>
 * </ul>
 */
@Service
public class VideoLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(VideoLifecycleService.class);

    private final VideoRepository videos;
    private final UploadService uploads;
    private final OutboxService outbox;
    private final ObjectStorage storage;
    private final CdnInvalidator cdn;
    private final LifecycleProperties properties;
    private final TransactionTemplate tx;

    public VideoLifecycleService(VideoRepository videos, UploadService uploads, OutboxService outbox,
                                 ObjectStorage storage, CdnInvalidator cdn, LifecycleProperties properties,
                                 TransactionTemplate tx) {
        this.videos = videos;
        this.uploads = uploads;
        this.outbox = outbox;
        this.storage = storage;
        this.cdn = cdn;
        this.properties = properties;
        this.tx = tx;
    }

    /**
     * Soft delete by the owner. Safe to repeat. An upload in progress is aborted first (its parts are deleted in S3).
     * The DELETED status and both events commit together: the search index drops the video and the storage cleaner
     * removes its files, even if Kafka is down right now.
     */
    public void delete(long userId, long videoId) {
        VideoEntity video = videos.findById(videoId)
                .filter(v -> v.isOwnedBy(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
        if (video.getStatus() == VideoStatus.DELETED) {
            return;
        }
        if (video.getStatus() == VideoStatus.UPLOADING) {
            uploads.abort(userId, videoId);
        }
        tx.executeWithoutResult(status -> {
            if (videos.markDeleted(videoId, Instant.now()) == 1) {
                String key = String.valueOf(videoId);
                outbox.append(videoId, VideoDeletedEvent.TYPE, Topics.VIDEO_DELETED, key,
                        new VideoDeletedEvent(videoId, video.getOriginalS3Key()));
                outbox.append(videoId, VideoChangedEvent.TYPE, Topics.VIDEO_CHANGED, key, new VideoChangedEvent(videoId));
            }
        });
        log.info("Video {} deleted by its owner", videoId);
    }

    /**
     * Removes every file of a video: its renditions, manifests and original. Idempotent, so a redelivered event or
     * a retry after a partial failure simply deletes what is left. A transcoding job that finishes later removes its
     * own rendition (it sees DELETED when it completes), so nothing is left behind by that race either.
     */
    public void purgeStorage(long videoId, String originalKey) {
        int streams = storage.deletePrefix(StorageKeys.STREAMS_PREFIX + videoId + "/");
        storage.delete(originalKey);
        cdn.invalidatePrefix(StorageKeys.STREAMS_PREFIX + videoId + "/");
        tx.executeWithoutResult(status -> videos.markStoragePurged(videoId, Instant.now()));
        log.info("Video {}: storage purged ({} stream files and the original)", videoId, streams);
    }

    /**
     * Deletes the files of videos that failed processing longer ago than the retention period. A storage error on
     * one video skips it (tried again on the next sweep).
     *
     * @return how many videos were purged
     */
    public int purgeFailed(int limit) {
        Instant before = Instant.now().minus(properties.failedOriginalRetention());
        int purged = 0;
        for (VideoEntity video : videos.findFailedToPurge(before, PageRequest.of(0, limit))) {
            try {
                purgeStorage(video.getId(), video.getOriginalS3Key());
                purged++;
            } catch (SdkException e) {
                log.warn("Could not purge failed video {}, will retry", video.getId(), e);
            }
        }
        return purged;
    }
}
