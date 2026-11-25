package az.youtube.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

/**
 * Video metadata. Status changes are conditional UPDATEs in {@link az.youtube.repository.VideoRepository}
 * ("move to X only if still Y"), so a retried request or a duplicate event changes nothing the second time.
 */
@Entity
@Table(name = "videos")
public class VideoEntity implements Persistable<Long> {

    /** Assigned from videos_id_seq before the insert, so the S3 key that contains it is stored with the row. */
    @Id
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "part_size_bytes", nullable = false)
    private long partSizeBytes;

    @Column(name = "part_count", nullable = false)
    private int partCount;

    @Column(name = "original_s3_key", nullable = false, length = 500)
    private String originalS3Key;

    @Column(name = "s3_upload_id", length = 1024)
    private String s3UploadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VideoStatus status;

    @Column(name = "manifest_s3_key", length = 500)
    private String manifestS3Key;

    /** Displayed size and duration found by ffprobe; null if the source was not probed. */
    @Column(name = "source_width")
    private Integer sourceWidth;

    @Column(name = "source_height")
    private Integer sourceHeight;

    @Column(name = "source_duration_ms")
    private Long sourceDurationMs;

    @Column(name = "upload_expires_at", nullable = false)
    private Instant uploadExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected VideoEntity() {
    }

    public static VideoEntity startUpload(long id, long userId, String title, String contentType, long sizeBytes,
                                          long partSizeBytes, int partCount, String originalS3Key,
                                          Instant now, Instant uploadExpiresAt) {
        VideoEntity video = new VideoEntity();
        video.id = id;
        video.userId = userId;
        video.title = title;
        video.contentType = contentType;
        video.sizeBytes = sizeBytes;
        video.partSizeBytes = partSizeBytes;
        video.partCount = partCount;
        video.originalS3Key = originalS3Key;
        video.status = VideoStatus.UPLOADING;
        video.uploadExpiresAt = uploadExpiresAt;
        video.createdAt = now;
        video.updatedAt = now;
        video.isNew = true;
        return video;
    }

    /** The id is assigned by us, so Spring Data cannot tell a new entity by a null id. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    public boolean isOwnedBy(long userId) {
        return this.userId == userId;
    }

    @Override
    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getPartSizeBytes() {
        return partSizeBytes;
    }

    public int getPartCount() {
        return partCount;
    }

    public String getOriginalS3Key() {
        return originalS3Key;
    }

    public String getS3UploadId() {
        return s3UploadId;
    }

    public VideoStatus getStatus() {
        return status;
    }

    public String getManifestS3Key() {
        return manifestS3Key;
    }

    public Integer getSourceWidth() {
        return sourceWidth;
    }

    public Integer getSourceHeight() {
        return sourceHeight;
    }

    public Long getSourceDurationMs() {
        return sourceDurationMs;
    }

    public Instant getUploadExpiresAt() {
        return uploadExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
