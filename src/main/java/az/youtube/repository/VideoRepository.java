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

    /** Sets the status derived from the variants; a null manifest key keeps the current one. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VideoEntity v
            set v.status = :status, v.manifestS3Key = coalesce(:manifestKey, v.manifestS3Key), v.updatedAt = :now
            where v.id = :id
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
