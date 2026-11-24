# YouTube — System Design, Built for Real

Learning project for topic 4/30 (YouTube system design): direct-to-object-storage multipart upload with
presigned URLs, upload lifecycle (`UPLOADING → UPLOADED → PROCESSING → PLAYABLE → FULLY_PROCESSED`),
transactional outbox to Kafka, independent per-resolution transcoding jobs (360p / 720p / 1080p / 4K),
progressive availability without waiting for 4K, and HLS/DASH adaptive bitrate playback. The design reference is
[docs/reference/04_YouTube_System_Design_Detailed_Explanation.pdf](docs/reference/04_YouTube_System_Design_Detailed_Explanation.pdf).

Stack: Java 26, Gradle, Spring Boot 4.1, JPA, PostgreSQL, Liquibase (same as `../whatsapp`).

## Run locally

- JDK 26: `C:\Users\AZINTELECOM\.jdks\temurin-26.0.2.1` (Project SDK and Gradle JVM in IntelliJ).
- Create the database: `CREATE DATABASE youtube;` on `localhost:5433`.
- Set `DB_PASSWORD` (and optionally `DB_URL`, `DB_USERNAME`) in the terminal or IntelliJ run configuration —
  see `.env.example`. No password is stored in the project.
- The app listens on port `8082` (`SERVER_PORT`), so it can run next to twitter (`8080`) and whatsapp (`8081`).

```
./gradlew bootRun
./gradlew test
```

## Authentication (development)

Same as whatsapp: the caller's id comes from the `X-User-Id` header (the API-gateway pattern, where the gateway
validates the token and forwards the user id). A missing, non-numeric or non-positive value returns `401`.
Controllers get the id with `@CurrentUser long userId`; business code depends only on `CurrentUserProvider`, so
the header implementation can later be swapped for Spring Security / JWT.
