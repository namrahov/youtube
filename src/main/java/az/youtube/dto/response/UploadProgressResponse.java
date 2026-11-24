package az.youtube.dto.response;

import java.util.List;

/** For resuming: which parts S3 already has, and which ones the client still has to send. */
public record UploadProgressResponse(int partCount, List<Integer> uploadedParts, List<Integer> missingParts) {
}
