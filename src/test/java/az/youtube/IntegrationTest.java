package az.youtube;

import az.youtube.entity.Resolution;
import az.youtube.transcoding.SimulatedTranscoder;
import az.youtube.transcoding.Transcoder;
import az.youtube.transcoding.TranscodingException;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests: the whole application against real PostgreSQL (schema from Liquibase), Kafka and
 * MinIO in Docker, started once and shared by all *IT classes. Tests do not clean up; each creates its own videos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 11 MiB test files become 3 parts (5 + 5 + 1 MiB); 5 MiB is the S3 minimum part size.
        "youtube.upload.part-size=5MB",
        "youtube.outbox.poll-interval-ms=100",
        "youtube.transcoding.max-retries=1",
        "youtube.transcoding.retry-interval=100ms",
        "youtube.transcoding.simulated-duration.P360=50ms",
        "youtube.transcoding.simulated-duration.P720=50ms",
        "youtube.transcoding.simulated-duration.P1080=50ms",
        "youtube.transcoding.simulated-duration.P2160=300ms",
})
@Import(IntegrationTest.FaultInjection.class)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:RELEASE.2025-04-22T22-12-26Z-cpuv1")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    static {
        Startables.deepStart(POSTGRES, KAFKA, MINIO).join();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("youtube.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("youtube.storage.access-key", () -> "minioadmin");
        registry.add("youtube.storage.secret-key", () -> "minioadmin");
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected FaultInjectingTranscoder transcoder;

    protected static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + what, e);
            }
        }
    }

    @TestConfiguration
    static class FaultInjection {

        @Bean
        @Primary
        FaultInjectingTranscoder faultInjectingTranscoder(SimulatedTranscoder simulated) {
            return new FaultInjectingTranscoder(simulated);
        }
    }

    /** The real (simulated) transcoder, except for the jobs a test told it to fail every time. */
    public static class FaultInjectingTranscoder implements Transcoder {

        private final Transcoder delegate;
        private final Set<String> alwaysFailing = ConcurrentHashMap.newKeySet();

        FaultInjectingTranscoder(Transcoder delegate) {
            this.delegate = delegate;
        }

        public void failAlways(long videoId, Resolution resolution) {
            alwaysFailing.add(videoId + ":" + resolution);
        }

        @Override
        public String transcode(long videoId, String sourceKey, Resolution resolution) {
            if (alwaysFailing.contains(videoId + ":" + resolution)) {
                throw new TranscodingException("Injected failure for " + resolution.label());
            }
            return delegate.transcode(videoId, sourceKey, resolution);
        }
    }
}
