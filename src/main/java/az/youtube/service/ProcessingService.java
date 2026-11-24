package az.youtube.service;

import az.youtube.config.TranscodingProperties;
import az.youtube.dto.event.TranscodeRequestedEvent;
import az.youtube.dto.event.VideoUploadedEvent;
import az.youtube.entity.Resolution;
import az.youtube.entity.VariantStatus;
import az.youtube.entity.VideoStatus;
import az.youtube.repository.VariantRepository;
import az.youtube.repository.VariantRepository.Variant;
import az.youtube.repository.VideoRepository;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Job planning and per-variant state. Every method is idempotent: Kafka may deliver any event twice. */
@Service
public class ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final VideoRepository videos;
    private final VariantRepository variants;
    private final OutboxService outbox;
    private final ManifestWriter manifests;
    private final TranscodingProperties properties;

    public ProcessingService(VideoRepository videos, VariantRepository variants, OutboxService outbox,
                             ManifestWriter manifests, TranscodingProperties properties) {
        this.videos = videos;
        this.variants = variants;
        this.outbox = outbox;
        this.manifests = manifests;
        this.properties = properties;
    }

    /**
     * Job planner: UPLOADED → PROCESSING, one PENDING variant row and one job per resolution, each job to its own
     * resolution topic. The jobs go through the outbox too, so they exist if and only if the status change committed.
     */
    @Transactional
    public void planJobs(VideoUploadedEvent event) {
        long videoId = event.videoId();
        if (videos.transition(videoId, VideoStatus.UPLOADED, VideoStatus.PROCESSING, Instant.now()) == 0) {
            log.debug("Video {} already planned, duplicate event ignored", videoId);
            return;
        }
        for (Resolution resolution : properties.resolutions()) {
            variants.insertPending(videoId, resolution);
            outbox.append(videoId, TranscodeRequestedEvent.TYPE, resolution.topic(), String.valueOf(videoId),
                    new TranscodeRequestedEvent(videoId, resolution, event.originalS3Key()));
        }
    }

    /** @return false if the job is a duplicate (variant already COMPLETED or FAILED): the worker skips it */
    @Transactional
    public boolean claimVariant(long videoId, Resolution resolution) {
        return variants.claim(videoId, resolution);
    }

    /**
     * Marks one variant COMPLETED and recalculates the video. The video row is locked first: when 720p and 1080p
     * finish at the same moment, the second waits and then sees both, so the manifest lists both and
     * FULLY_PROCESSED is not missed. The status is PLAYABLE only after the manifest that lists the variant exists.
     */
    @Transactional
    public void completeVariant(long videoId, Resolution resolution, String playlistKey) {
        videos.lockById(videoId);
        if (variants.markCompleted(videoId, resolution, playlistKey) == 0) {
            log.debug("Variant {} of video {} already completed, duplicate ignored", resolution.label(), videoId);
            return;
        }
        List<Variant> all = variants.findByVideo(videoId);
        List<Resolution> completed = all.stream()
                .filter(v -> v.status() == VariantStatus.COMPLETED)
                .map(Variant::resolution)
                .toList();
        // Written while the row lock is held, so two writers cannot leave an older list as the last version.
        // Trade-off: a slow S3 PUT holds the lock and a connection. If the commit then fails, the manifest lists a
        // rendition that exists but is not yet COMPLETED in the DB; the job is redelivered and repairs it.
        String manifestKey = manifests.write(videoId, completed);
        updateStatus(videoId, all, manifestKey);
    }

    /**
     * Called once a job ran out of retries. The other variants are not affected: a video with 360p–1080p ready
     * and 4K FAILED stays PLAYABLE, it just never becomes FULLY_PROCESSED.
     */
    @Transactional
    public void failVariant(long videoId, Resolution resolution, String error) {
        videos.lockById(videoId);
        String message = error == null ? "unknown error" : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        if (variants.markFailed(videoId, resolution, message) == 0) {
            return;
        }
        log.warn("Variant {} of video {} FAILED: {}", resolution.label(), videoId, message);
        updateStatus(videoId, variants.findByVideo(videoId), null);
    }

    private void updateStatus(long videoId, List<Variant> all, String manifestKey) {
        VideoStatus status = VideoStatusCalculator.derive(all.stream().map(Variant::status).toList());
        videos.updateProcessingState(videoId, status, manifestKey, Instant.now());
    }
}
