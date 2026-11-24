package az.youtube.worker;

import az.youtube.service.UploadService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cleans up abandoned uploads (client closed the tab, lost network for good). Uses the partial index on
 * UPLOADING rows, so it never scans the whole videos table. As a second safety net, a bucket lifecycle rule
 * (AbortIncompleteMultipartUpload) would remove parts of uploads that never got a row at all.
 */
@Component
public class UploadExpiryJob {

    private static final int BATCH_SIZE = 100;

    private final UploadService uploads;

    public UploadExpiryJob(UploadService uploads) {
        this.uploads = uploads;
    }

    @Scheduled(fixedDelayString = "${youtube.upload.expiry-sweep-interval-ms}")
    public void expireAbandonedUploads() {
        while (uploads.expireAbandoned(BATCH_SIZE) == BATCH_SIZE) {
            // a full batch: there may be more
        }
    }
}
