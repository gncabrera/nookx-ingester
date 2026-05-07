# nookx-ingester

Ingester pipeline service (v2). Replaces `nookx-crawler` POC. See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design and [`docs/PIPELINE.md`](docs/PIPELINE.md) for the conceptual model.

Two orthogonal abstractions:

- **`Source`** — where data comes from (discoverer + parsers per page type).
- **`IngestTarget<P>`** — typed destination of ingest (one source ⇒ one target). HTTP client, validation, idempotency, mapping.

Pipeline is the same 4 stages as the POC, all independent and re-runnable:

```
Discovery -> Crawl -> Parse -> Push
```

Plus a Thymeleaf + HTMX dashboard at `/` for monitoring and on-demand triggers.

## Tech stack

- Java 21
- Spring Boot 3
- MySQL + Flyway
- Spring Data JPA
- Thymeleaf + HTMX + Tailwind (CDN, no JS build pipeline)
- jsoup
- Logback (file + custom JDBC appender for per-job structured logs)

## Configuration

`src/main/resources/application.yml` is the source of truth. Environment overrides:

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- `NOOKX_BASE_URL`, `NOOKX_API_KEY`
- `INGESTER_RAW_DIR`, `INGESTER_ASSET_DIR`

## Run with Docker Compose (DB only)

```bash
docker compose up -d mysql
```

## Build and run

```bash
mvn clean package -DskipTests
java -jar target/nookx-ingester-0.0.1-SNAPSHOT.jar
```

Dashboard: http://localhost:8081/

## Notes

- No CLI commands. Everything is run by schedules, or manually triggered from the dashboard (`POST /sources/{code}/discovery/run`, etc).
- Single-thread per pipeline stage (one runner at a time per `(stage, scope)`, protected by `PipelineLockRegistry`).
- No auth — intended for private network deploys.
- Schema and data are independent from the POC; the two services are not compatible.
