package az.youtube.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("youtube.upload")
public record UploadProperties(
        DataSize partSize,
        DataSize maxSize,
        Duration partUrlTtl,
        Duration uploadTtl) {
}
