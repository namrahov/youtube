package az.youtube;

import az.youtube.dto.event.TranscodeRequestedEvent;
import az.youtube.entity.Resolution;
import az.youtube.transcoding.FfmpegTranscoder;
import az.youtube.transcoding.Rendition;
import az.youtube.transcoding.SimulatedTranscoder;
import az.youtube.transcoding.Transcoder;
import az.youtube.transcoding.TranscodingException;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
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
 * The uploads are random bytes, so the simulated transcoder is used; {@link FfmpegPipelineIT} switches to ffmpeg.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "youtube.transcoding.engine=simulated",
        // 11 MiB test files become 3 parts (5 + 5 + 1 MiB); 5 MiB is the S3 minimum part size.
        "youtube.upload.part-size=5MB",
        "youtube.outbox.poll-interval-ms=100",
        "youtube.transcoding.max-retries=1",
        "youtube.transcoding.retry-interval=100ms",
        "youtube.transcoding.simulated-duration.P360=50ms",
        "youtube.transcoding.simulated-duration.P720=50ms",
        "youtube.transcoding.simulated-duration.P1080=50ms",
        "youtube.transcoding.simulated-duration.P2160=300ms",
        "youtube.views.batch-wait-ms=100",
        "youtube.search.stats-sync-interval-ms=200",
        "youtube.metrics.backlog-refresh-ms=200",
})
@Import(IntegrationTest.FaultInjection.class)
// One application per test class, closed after it: two live contexts would share the Kafka consumer groups, and the
// simulated workers of one would take the ffmpeg jobs of the other.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    /** MinIO no longer publishes images on Docker Hub: the same locally built image as docker-compose.yml. */
    static final String MINIO_IMAGE = System.getenv().getOrDefault("MINIO_IMAGE",
            "ecis-local/minio:RELEASE.2025-09-07T16-13-09Z");

    static final GenericContainer<?> MINIO = new GenericContainer<>(MINIO_IMAGE)
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.2").withExposedPorts(6379);

    static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
            System.getenv().getOrDefault("ELASTICSEARCH_IMAGE", "docker.elastic.co/elasticsearch/elasticsearch:9.0.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow").forPort(9200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        Startables.deepStart(POSTGRES, KAFKA, MINIO, REDIS, ELASTICSEARCH).join();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("youtube.storage.endpoint", IntegrationTest::minioUrl);
        registry.add("youtube.storage.access-key", () -> "minioadmin");
        registry.add("youtube.storage.secret-key", () -> "minioadmin");
        registry.add("youtube.playback.base-url", () -> minioUrl() + "/youtube-videos");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("youtube.search.url",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
    }

    static String minioUrl() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected FaultInjectingTranscoder transcoder;

    protected static void await(String what, BooleanSupplier condition) {
        await(what, Duration.ofSeconds(30), condition);
    }

    protected static void await(String what, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
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

        /** Wraps whichever engine is active. */
        @Bean
        @Primary
        FaultInjectingTranscoder faultInjectingTranscoder(ObjectProvider<SimulatedTranscoder> simulated,
                                                          ObjectProvider<FfmpegTranscoder> ffmpeg) {
            Transcoder engine = simulated.getIfAvailable();
            return new FaultInjectingTranscoder(engine != null ? engine : ffmpeg.getObject());
        }
    }

    /** The real transcoder, except for the jobs a test told it to fail every time. */
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
        public Rendition transcode(TranscodeRequestedEvent job) {
            if (alwaysFailing.contains(job.videoId() + ":" + job.resolution())) {
                throw new TranscodingException("Injected failure for " + job.resolution().label());
            }
            return delegate.transcode(job);
        }
    }
}
