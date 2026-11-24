package az.youtube.service;

import az.youtube.dto.response.VideoResponse;
import az.youtube.entity.VideoEntity;
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

    public VideoService(VideoRepository videos, VariantRepository variants) {
        this.videos = videos;
        this.variants = variants;
    }

    /** The owner sees the video in every state; other users only once it is playable. */
    @Transactional(readOnly = true)
    public VideoResponse get(long userId, long videoId) {
        return videos.findById(videoId)
                .filter(v -> v.isOwnedBy(userId) || v.getStatus().isPlayable())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    VideoResponse toResponse(VideoEntity video) {
        var variantList = variants.findByVideo(video.getId()).stream()
                .map(v -> new VideoResponse.Variant(v.resolution().label(), v.status()))
                .toList();
        return new VideoResponse(video.getId(), video.getTitle(), video.getStatus(), video.getSizeBytes(),
                video.getStatus().isPlayable() ? video.getManifestS3Key() : null, variantList, video.getCreatedAt());
    }
}
