package az.youtube.dto.response;

import az.youtube.entity.VideoStatus;

import java.time.Instant;

/** Everything the client needs to cut the file: part i covers bytes [(i-1)·partSize, i·partSize). */
public record InitiateUploadResponse(
        long videoId,
        VideoStatus status,
        long partSizeBytes,
        int partCount,
        Instant uploadExpiresAt) {
}
