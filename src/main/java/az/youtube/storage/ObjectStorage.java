package az.youtube.storage;

import az.youtube.config.StorageProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** The few S3 operations the app needs. The video bytes never pass through here, only metadata calls. */
@Component
public class ObjectStorage implements InitializingBean {

    public record UploadedPart(int partNumber, String eTag, long size) {
    }

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;
    private final String bucket;

    public ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
        this.bucket = properties.bucket();
    }

    /**
     * Local convenience: creates the bucket before the workers start and opens {@code streams/} to anonymous GET.
     * Production buckets and their policies come from infrastructure.
     */
    @Override
    public void afterPropertiesSet() {
        if (properties.createBucket()) {
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(bucket));
            }
        }
        if (properties.publicStreams()) {
            s3.putBucketPolicy(b -> b.bucket(bucket).policy(publicReadPolicy(StorageKeys.STREAMS_PREFIX)));
        }
    }

    /** GetObject for everyone, only under {@code prefix}: originals stay private. */
    static String publicReadPolicy(String bucket, String prefix) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},\
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/%s*"]}]}""".formatted(bucket, prefix);
    }

    private String publicReadPolicy(String prefix) {
        return publicReadPolicy(bucket, prefix);
    }

    /** @return the S3 uploadId that every part upload and the final complete/abort refer to */
    public String createMultipartUpload(String key, String contentType) {
        return s3.createMultipartUpload(b -> b.bucket(bucket).key(key).contentType(contentType)).uploadId();
    }

    /** A URL the client PUTs one part to. Signed locally; valid for {@code ttl}. */
    public URL presignUploadPart(String key, String uploadId, int partNumber, Duration ttl) {
        return presigner.presignUploadPart(p -> p
                        .signatureDuration(ttl)
                        .uploadPartRequest(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber)))
                .url();
    }

    /** Parts S3 already has: a resuming client uploads only the missing ones. */
    public List<UploadedPart> listParts(String key, String uploadId) {
        return s3.listPartsPaginator(b -> b.bucket(bucket).key(key).uploadId(uploadId))
                .parts()
                .stream()
                .map(p -> new UploadedPart(p.partNumber(), p.eTag(), p.size()))
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .toList();
    }

    public void completeMultipartUpload(String key, String uploadId, List<UploadedPart> parts) {
        List<CompletedPart> completed = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build())
                .toList();
        s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()));
    }

    /** Deletes the parts of an unfinished upload. Safe to repeat: an unknown upload is already gone. */
    public void abortMultipartUpload(String key, String uploadId) {
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
        }
    }

    /** @return the object's size, or empty if there is no such object */
    public OptionalLong objectSize(String key) {
        try {
            return OptionalLong.of(s3.headObject(b -> b.bucket(bucket).key(key)).contentLength());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return OptionalLong.empty();
            }
            throw e;
        }
    }

    /** A URL that reads the object for {@code ttl}: lets ffprobe read just the headers it needs over HTTP. */
    public URL presignGet(String key, Duration ttl) {
        return presigner.presignGetObject(p -> p
                        .signatureDuration(ttl)
                        .getObjectRequest(r -> r.bucket(bucket).key(key)))
                .url();
    }

    /** Streams the object to {@code target}, replacing it if it exists. */
    public void download(String key, Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        s3.getObject(b -> b.bucket(bucket).key(key), target);
    }

    public void putFile(String key, Path file, String contentType, String cacheControl) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).cacheControl(cacheControl),
                RequestBody.fromFile(file));
    }

    public void putText(String key, String content, String contentType, String cacheControl) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).cacheControl(cacheControl),
                RequestBody.fromString(content));
    }

    public String getText(String key) {
        return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asUtf8String();
    }

    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }
}
