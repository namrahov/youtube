package az.youtube.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Declared before the upload; the size is checked against the stored object when the upload completes. */
public record InitiateUploadRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 100) @Pattern(regexp = "video/[\\w.+-]+") String contentType,
        @Positive long sizeBytes) {
}
