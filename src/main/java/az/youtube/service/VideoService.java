package az.youtube.service;

import az.youtube.config.PlaybackProperties;
import az.youtube.dto.response.VideoResponse;
import az.youtube.entity.VideoEntity;
import az.youtube.entity.VideoStatus;
import az.youtube.repository.StatsRepository;
import az.youtube.repository.VariantRepository;
import az.youtube.repository.VideoRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VideoService {

    private final VideoRepository videos;
    private final VariantRepository variants;
    private final PlaybackProperties playback;
    private final StatsRepository stats;

    public VideoService(VideoRepository videos, VariantRepository variants, PlaybackProperties playback,
                        StatsRepository stats) {
        this.videos = videos;
        this.variants = variants;
        this.playback = playback;
        this.stats = stats;
    }

    /** The owner sees the video in every state but DELETED; other users only once it is playable. */
    @Transactional(readOnly = true)
    public VideoResponse get(long userId, long videoId) {
        return videos.findById(videoId)
                .filter(v -> v.getStatus() != VideoStatus.DELETED)
                .filter(v -> v.isOwnedBy(userId) || v.getStatus().isPlayable())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    VideoResponse toResponse(VideoEntity video) {
        var variantList = variants.findByVideo(video.getId()).stream()
                .map(v -> new VideoResponse.Variant(v.resolution().label(), v.status(), v.width(), v.height()))
                .toList();
        String manifestKey = video.getStatus().isPlayable() ? video.getManifestS3Key() : null;
        return new VideoResponse(video.getId(), video.getTitle(), video.getStatus(), video.getSizeBytes(),
                stats.viewCount(video.getId()),
                manifestKey, manifestKey == null ? null : playback.urlOf(manifestKey), variantList,
                video.getCreatedAt());
    }
}
