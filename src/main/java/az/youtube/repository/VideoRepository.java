package az.youtube.repository;

import az.youtube.entity.VideoEntity;
import az.youtube.entity.VideoStatus;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoRepository extends JpaRepository<VideoEntity, Long> {

    @Query(value = "SELECT nextval('videos_id_seq')", nativeQuery = true)
    long nextId();

    /**
     * Serialises the status recalculation of one video: two variants finishing at the same moment would otherwise
     * both read "the other one is not done yet" and neither would write FULLY_PROCESSED or the full manifest.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VideoEntity v where v.id = :id")
    Optional<VideoEntity> lockById(@Param("id") long id);

    /**
     * Moves the video from {@code from} to {@code to}, and only from there.
     *
     * @return 1 if this call made the transition, 0 if the video was not in {@code from} (already moved: duplicate)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update VideoEntity v set v.status = :to, v.updatedAt = :now where v.id = :id and v.status = :from")
    int transition(@Param("id") long id, @Param("from") VideoStatus from, @Param("to") VideoStatus to,
                   @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VideoEntity v set v.s3UploadId = :uploadId, v.updatedAt = :now
            where v.id = :id and v.status = az.youtube.entity.VideoStatus.UPLOADING
            """)
    int attachUploadId(@Param("id") long id, @Param("uploadId") String uploadId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VideoEntity v
            set v.sourceWidth = :width, v.sourceHeight = :height, v.sourceDurationMs = :durationMs, v.updatedAt = :now
            where v.id = :id
            """)
    int recordSource(@Param("id") long id, @Param("width") int width, @Param("height") int height,
                     @Param("durationMs") long durationMs, @Param("now") Instant now);

    /** UPLOADING → UPLOADED, and the moment of it (start of the time-to-playable metric). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VideoEntity v set v.status = az.youtube.entity.VideoStatus.UPLOADED, v.uploadedAt = :now,
                v.updatedAt = :now
            where v.id = :id and v.status = az.youtube.entity.VideoStatus.UPLOADING
            """)
    int markUploaded(@Param("id") long id, @Param("now") Instant now);

    /** @return 1 the first time only: the video has just become playable (published) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update VideoEntity v set v.publishedAt = :now where v.id = :id and v.publishedAt is null")
    int markPublished(@Param("id") long id, @Param("now") Instant now);

    /**
     * Soft delete. Not from UPLOADING: an open upload is aborted first, so its multipart parts are removed too.
     *
     * @return 1 if this call deleted the video, 0 if it was already DELETED or is still uploading
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VideoEntity v set v.status = az.youtube.entity.VideoStatus.DELETED, v.deletedAt = :now,
                v.updatedAt = :now
            where v.id = :id and v.status <> az.youtube.entity.VideoStatus.DELETED
              and v.status <> az.youtube.entity.VideoStatus.UPLOADING
            """)
    int markDeleted(@Param("id") long id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update VideoEntity v set v.storagePurgedAt = :now where v.id = :id and v.storagePurgedAt is null")
    int markStoragePurged(@Param("id") long id, @Param("now") Instant now);

    /** Failed videos whose original is still stored and older than {@code before} (partial index). */
    @Query("""
            select v from VideoEntity v
            where v.status = az.youtube.entity.VideoStatus.PROCESSING_FAILED and v.storagePurgedAt is null
              and v.updatedAt < :before
            order by v.updatedAt
            """)
    List<VideoEntity> findFailedToPurge(@Param("before") Instant before, Pageable page);

    /**
     * Sets the status derived from the variants; a null manifest key keeps the current one. A DELETED video is left
     * alone: a job that was still running when the owner deleted it must not bring it back.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VideoEntity v
            set v.status = :status, v.manifestS3Key = coalesce(:manifestKey, v.manifestS3Key), v.updatedAt = :now
            where v.id = :id and v.status <> az.youtube.entity.VideoStatus.DELETED
            """)
    int updateProcessingState(@Param("id") long id, @Param("status") VideoStatus status,
                              @Param("manifestKey") String manifestKey, @Param("now") Instant now);

    @Query("""
            select v from VideoEntity v
            where v.status = az.youtube.entity.VideoStatus.UPLOADING and v.uploadExpiresAt < :now
            order by v.uploadExpiresAt
            """)
    List<VideoEntity> findExpiredUploads(@Param("now") Instant now, Pageable page);
}
