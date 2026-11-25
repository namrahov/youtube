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
import az.youtube.transcoding.Rendition;
import az.youtube.transcoding.SourceInfo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /** Lets the planner skip a duplicate event before it spends an ffprobe run on it. */
    @Transactional(readOnly = true)
    public boolean isAwaitingPlanning(long videoId) {
        return videos.findById(videoId).map(v -> v.getStatus() == VideoStatus.UPLOADED).orElse(false);
    }

    /**
     * Job planner: UPLOADED → PROCESSING, one variant row per configured resolution and one job per variant worth
     * encoding, each job to its own resolution topic. Renditions larger than the source are SKIPPED (no upscaling).
     * The jobs go through the outbox too, so they exist if and only if the status change committed.
     *
     * @param source what ffprobe found; empty if the source was not probed (then every resolution is encoded)
     */
    @Transactional
    public void planJobs(VideoUploadedEvent event, Optional<SourceInfo> source) {
        long videoId = event.videoId();
        Instant now = Instant.now();
        if (videos.transition(videoId, VideoStatus.UPLOADED, VideoStatus.PROCESSING, now) == 0) {
            log.debug("Video {} already planned, duplicate event ignored", videoId);
            return;
        }
        source.ifPresent(s -> videos.recordSource(videoId, s.width(), s.height(), s.durationMs(), now));
        Resolution lowest = properties.lowestPlanned();
        for (Resolution resolution : properties.resolutions()) {
            if (source.isPresent() && !source.get().worthEncoding(resolution, lowest)) {
                variants.insertSkipped(videoId, resolution);
                continue;
            }
            variants.insertPending(videoId, resolution);
            outbox.append(videoId, TranscodeRequestedEvent.TYPE, resolution.topic(), String.valueOf(videoId),
                    new TranscodeRequestedEvent(videoId, resolution, event.originalS3Key(),
                            source.map(SourceInfo::width).orElse(null),
                            source.map(SourceInfo::height).orElse(null),
                            source.map(SourceInfo::hasAudio).orElse(null)));
        }
    }

    /** The upload is not a readable video: nothing to encode, retrying cannot help. */
    @Transactional
    public void rejectSource(long videoId, String reason) {
        if (videos.transition(videoId, VideoStatus.UPLOADED, VideoStatus.PROCESSING_FAILED, Instant.now()) == 1) {
            log.warn("Video {} rejected: {}", videoId, reason);
        }
    }

    /** @return false if the job is a duplicate (variant already final): the worker skips it */
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
    public void completeVariant(long videoId, Resolution resolution, Rendition rendition) {
        videos.lockById(videoId);
        if (variants.markCompleted(videoId, resolution, rendition) == 0) {
            log.debug("Variant {} of video {} already completed, duplicate ignored", resolution.label(), videoId);
            return;
        }
        List<Variant> all = variants.findByVideo(videoId);
        List<Variant> completed = all.stream().filter(v -> v.status() == VariantStatus.COMPLETED).toList();
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
