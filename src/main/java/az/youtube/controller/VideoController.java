package az.youtube.controller;

import az.youtube.dto.request.InitiateUploadRequest;
import az.youtube.dto.response.InitiateUploadResponse;
import az.youtube.dto.response.PartUrlsResponse;
import az.youtube.dto.response.UploadProgressResponse;
import az.youtube.dto.response.VideoResponse;
import az.youtube.security.CurrentUser;
import az.youtube.service.UploadService;
import az.youtube.service.VideoService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Upload flow:
 * <ol>
 *   <li>{@code POST /api/videos/uploads} — video row UPLOADING + S3 multipart upload; returns part size and count</li>
 *   <li>{@code GET /api/videos/{id}/upload/part-urls?from=1&count=32} — presigned URLs; the client PUTs each part
 *       straight to S3 and retries a failed part on its own</li>
 *   <li>{@code GET /api/videos/{id}/upload/progress} — parts S3 already has (to resume)</li>
 *   <li>{@code POST /api/videos/{id}/upload/complete} — verify, UPLOADED + outbox event</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final UploadService uploads;
    private final VideoService videos;

    public VideoController(UploadService uploads, VideoService videos) {
        this.uploads = uploads;
        this.videos = videos;
    }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public InitiateUploadResponse initiate(@CurrentUser long userId, @Valid @RequestBody InitiateUploadRequest request) {
        return uploads.initiate(userId, request);
    }

    @GetMapping("/{videoId}/upload/part-urls")
    public PartUrlsResponse partUrls(@CurrentUser long userId, @PathVariable long videoId,
                                     @RequestParam(defaultValue = "1") int from,
                                     @RequestParam(defaultValue = "100") int count) {
        return uploads.partUrls(userId, videoId, from, count);
    }

    @GetMapping("/{videoId}/upload/progress")
    public UploadProgressResponse progress(@CurrentUser long userId, @PathVariable long videoId) {
        return uploads.progress(userId, videoId);
    }

    @PostMapping("/{videoId}/upload/complete")
    public VideoResponse complete(@CurrentUser long userId, @PathVariable long videoId) {
        return uploads.complete(userId, videoId);
    }

    @DeleteMapping("/{videoId}/upload")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abort(@CurrentUser long userId, @PathVariable long videoId) {
        uploads.abort(userId, videoId);
    }

    @GetMapping("/{videoId}")
    public VideoResponse get(@CurrentUser long userId, @PathVariable long videoId) {
        return videos.get(userId, videoId);
    }
}
