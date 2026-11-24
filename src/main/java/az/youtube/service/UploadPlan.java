package az.youtube.service;

/**
 * How a file is cut into multipart-upload parts. S3 rules: at most 10 000 parts, every part but the last at least
 * 5 MiB, at most 5 GiB per part. The preferred part size grows when the file would not fit into 10 000 parts.
 */
public record UploadPlan(long partSizeBytes, int partCount) {

    static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    static final long MAX_PART_SIZE = 5L * 1024 * 1024 * 1024;
    static final int MAX_PARTS = 10_000;

    public static UploadPlan of(long sizeBytes, long preferredPartSize) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("File size must be positive");
        }
        long partSize = Math.max(Math.max(preferredPartSize, MIN_PART_SIZE), Math.ceilDiv(sizeBytes, MAX_PARTS));
        if (partSize > MAX_PART_SIZE) {
            throw new IllegalArgumentException("File is too large for a multipart upload");
        }
        return new UploadPlan(partSize, (int) Math.ceilDiv(sizeBytes, partSize));
    }

    /** Exact size S3 must receive for a part: all parts are full except possibly the last. */
    public long expectedPartSize(int partNumber, long sizeBytes) {
        return partNumber < partCount ? partSizeBytes : sizeBytes - partSizeBytes * (partCount - 1);
    }
}
