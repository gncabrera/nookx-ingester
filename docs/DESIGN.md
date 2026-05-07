# nookx-crawler v2 — Diseño (para validación)

> **Estado:** propuesta. Nada implementado aún. Validar antes de codear.

Documento que consolida las decisiones tomadas y deriva la arquitectura, modelo de datos, abstracciones Java, dashboard y plan de trabajo.

---

## 1. Objetivo

Reescribir la POC actual (`nookx-crawler`) en un **repo nuevo** con dos objetivos:

1. **Extensibilidad por dos ejes ortogonales:**
   - `Source` = de dónde se obtienen los datos (ej. `klickypedia`).
   - `IngestTarget` = qué tipo de cosa se ingesta y a dónde se manda (ej. `klickypedia-sets`, `something-figures`, `something-minifigs`).
2. **Operabilidad:** dashboard web para ver qué corre, qué está pendiente, qué falló, logs, y disparar acciones on-demand.

Mantener todo lo que funcionó bien en la POC: pipeline en 4 etapas independientes y re-ejecutables, idempotencia en cada salto, throttling por source, cache en cada etapa.

## 2. Stack

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 3 |
| DB | MySQL |
| Migraciones | Flyway |
| ORM | Spring Data JPA |
| HTML parsing | jsoup |
| HTTP client | `java.net.http.HttpClient` (igual que la POC) |
| Build | Maven |
| **Front** | **Thymeleaf SSR + HTMX + Tailwind** (sin build pipeline JS) |
| Auth | **Sin auth** (red privada) |
| Concurrencia | **Single-thread por etapa** (igual que POC) |
| Logging | SLF4J + Logback (file + persistencia estructurada por job, ver §9) |

## 3. Modelo conceptual

```
                                          ┌──────────────────────┐
                                          │ scrape_page (URLs)   │
                                          └──────────┬───────────┘
                                                     │
                                  ┌──────────────────┼──────────────────┐
                                  ▼                  ▼                  ▼
                              Discovery            Crawl              Parse
                                  ▲                                     │
                                  │                                     ▼
                              (recursivo)                    ┌──────────────────────┐
                                                             │ parsed_payload (json)│
                                                             │ parsed_asset         │
                                                             └──────────┬───────────┘
                                                                        │
                                                                        ▼
                                                                      Push
                                                                        │
                                                                        ▼
                                                                  IngestTarget
                                                                  (REST destino)
```

### 3.1 Source

```java
interface Source {
    String code();                              // "klickypedia"
    SourceDiscoverer discoverer();
    Optional<PageParser> parserFor(PageType pt);
    String ingestTargetCode();                  // 1 source → 1 IngestTarget
}
```

- Un `Source` define cómo descubrir URLs y cómo parsear cada `pageType`.
- **Cada `Source` apunta a exactamente un `IngestTarget`.** Esto fija el shape de salida del parser para ese source.

### 3.2 IngestTarget

```java
interface IngestTarget<P extends NormalizedPayload> {
    String code();                              // "klickypedia-sets"
    Class<P> payloadType();                     // type-safe DTO
    void validate(P payload);                   // sync, lanza si inválido
    PushOutcome push(P payload, List<DownloadedAsset> assets);
    String idempotencyKey(P payload);
    String cron();                              // schedule propio del target
}
```

- Cada `IngestTarget` es un `@Component` Java (plugin = deploy).
- Tipado: cada target define su propio DTO (`NormalizedSetDto`, `NormalizedFigureDto`, etc).
- Maneja:
  - **Validación** del payload normalizado.
  - **Cliente HTTP** propio (URL, auth, content-type, paginación, batch o no, etc).
  - **Idempotency-key** propio (cómo derivarla del payload).
  - **Mapeo** payload → request body (si hace falta más allá del JSON directo).
- **NO** maneja descarga de assets ni multipart (eso es genérico, ver §3.3).
- **NO** maneja persistencia local (eso es genérico).

### 3.3 Manejo de assets (genérico, igual para todos los targets)

Política única para v2: **download local → multipart upload junto con el push del payload**.

- El runtime del Push:
  1. Resuelve el `IngestTarget` del payload pendiente.
  2. Descarga (con cache local) cada asset asociado.
  3. Llama a `IngestTarget.push(payload, downloadedAssets)`.
- El target decide la forma exacta del multipart (campos, nombres, sortOrder), pero el ciclo de vida del binario (download, cache, hash, retry) es genérico.

### 3.4 Parser tipado por target

```java
interface PageParser<P extends NormalizedPayload> {
    PageType pageType();
    ParseResult<P> parse(ParseContext ctx);
}

record ParseResult<P>(List<P> payloads, List<DiscoveredUrl> newUrls) {}
```

- El parser conoce el tipo `P` que espera su `IngestTarget`.
- En tiempo de wiring se valida: `source.ingestTarget().payloadType() == parser.payloadType()`.

## 4. Pipeline (4 etapas)

Todas las etapas siguen funcionando como en la POC: independientes, re-ejecutables, idempotentes, estado en DB.

### 4.1 Discovery
- Igual que POC. Inserta URLs en `scrape_page` con `enqueueIfAbsent`.
- Schedule: cron por source (no por target, porque source es quien sabe descubrir).
- Trigger manual: por source individual.

### 4.2 Crawl
- **Idéntico a la POC.** Genérico, no entiende contenido. Solo HTTP GET + storage de bytes + etag/last-modified + validator opcional por source.
- Schedule global (fixed delay) + manual.

### 4.3 Parse
- Cambio: el parser ahora produce `List<P>` tipado al `IngestTarget` del source.
- El runtime del Parse:
  1. Resuelve `Source` por `source_code`.
  2. Resuelve `PageParser<P>` por `page_type`.
  3. Ejecuta parse → obtiene `payloads` + `newUrls`.
  4. Para cada payload: serializa a JSON, **persiste en `parsed_payload`** con `push_type = source.ingestTargetCode()` y `push_status = PENDING`. Persiste assets en `parsed_asset` (FK al payload).
  5. Enqueue `newUrls` (igual que POC).
- Schedule global + manual.

### 4.4 Push
- **Cambio mayor:** una corrida de Push procesa **un IngestTarget a la vez**.
- Cada `IngestTarget` tiene su propio cron (definido en su `@Component`).
- El runtime del Push para un target T:
  1. Lee `parsed_payload` con `push_type = T.code()` y `push_status = PENDING`.
  2. Para cada uno: deserializa JSON → `P`, valida con `T.validate(P)`, descarga assets (cache), llama `T.push(P, assets)`.
  3. Marca `push_status` = `PUSHED` / `ALREADY_EXISTS` / `FAILED` con `push_last_error`.
- Trigger manual: por target individual + reset de errores en bulk (FAILED → PENDING).

## 5. Modelo de datos

### 5.1 Tablas pipeline

```
scrape_page                    ← idéntica a POC
parsed_payload                 ← genérica nueva (reemplaza source_set)
parsed_asset                   ← genérica nueva (reemplaza source_set_asset)
job_run                        ← nueva, tracking de ejecuciones
```

### 5.2 `parsed_payload`

| Campo | Tipo | Notas |
|---|---|---|
| `id` | bigint PK | |
| `source_code` | varchar(64) | de qué source vino |
| `ingest_target_code` | varchar(64) | a dónde va (denormalizado del source en momento del parse) |
| `payload_type` | varchar(128) | FQN o alias del DTO Java (validación de schema) |
| `external_id` | varchar(255) | id estable dentro del source |
| `payload_json` | json | DTO serializado |
| `payload_hash` | varchar(64) | sha256 del json, para detectar cambios |
| `push_status` | enum | `PENDING / PUSHED / ALREADY_EXISTS / FAILED` |
| `push_retry_count` | int | |
| `push_last_error` | varchar(2000) | |
| `external_ref` | varchar(255) | id devuelto por el destino (ej. nookxSetId), nullable |
| `scrape_page_id` | bigint FK | trazabilidad al raw |
| `first_seen_at`, `last_parsed_at`, `pushed_at`, `created_at`, `updated_at` | datetime | |

**Unique:** `(source_code, ingest_target_code, external_id)`.

**Índices:** `(ingest_target_code, push_status)` para el query principal del Push.

### 5.3 `parsed_asset`

| Campo | Tipo | Notas |
|---|---|---|
| `id` | bigint PK | |
| `parsed_payload_id` | bigint FK | |
| `kind` | varchar(32) | `IMAGE`, etc |
| `external_url` | varchar(2048) | |
| `external_url_hash` | varchar(64) | |
| `label` | varchar(255) | |
| `sort_order` | int | |
| `downloaded` | bit | |
| `local_path`, `content_hash`, `content_type`, `content_size_bytes` | | |
| `download_retry_count`, `download_last_error` | | |
| `push_status`, `push_retry_count`, `push_last_error` | | |
| `created_at`, `updated_at` | | |

**Unique:** `(parsed_payload_id, external_url_hash)`.

### 5.4 `job_run` (nueva, para dashboard y observabilidad)

| Campo | Tipo | Notas |
|---|---|---|
| `id` | bigint PK | |
| `stage` | enum | `DISCOVERY / CRAWL / PARSE / PUSH` |
| `scope_type` | enum | `GLOBAL / SOURCE / INGEST_TARGET` |
| `scope_code` | varchar(128) | nullable, ej. `klickypedia` o `klickypedia-sets` |
| `trigger` | enum | `MANUAL / SCHEDULED` |
| `triggered_by` | varchar(128) | nullable, para futura auth |
| `status` | enum | `RUNNING / SUCCESS / FAILED / SKIPPED` |
| `started_at`, `ended_at` | datetime | |
| `metrics_json` | json | `{ processed, failed, skipped, queued, ... }` por etapa |
| `error_message` | varchar(2000) | nullable |

Cada disparo (manual o schedule) crea una fila. El dashboard la usa para mostrar lo que está corriendo / pasó.

### 5.5 `job_log` (logs estructurados persistidos)

| Campo | Tipo | Notas |
|---|---|---|
| `id` | bigint PK | |
| `job_run_id` | bigint FK | |
| `ts` | datetime(6) | |
| `level` | varchar(8) | `DEBUG/INFO/WARN/ERROR` |
| `message` | varchar(2000) | |
| `context_json` | json | nullable, campos estructurados (pageId, url, etc) |

**Política:** se persisten **solo INFO+** del job (no DEBUG, para no inflar la tabla). Tail en vivo del log file sigue disponible aparte (ver §9).

**Retención:** purga rolling (ej. >90 días o >N filas por job). Configurable.

## 6. Estructura de paquetes Java propuesta

```
com.nookx.crawler
├── core
│   ├── http        (ScraperHttpClient, FetchResult, jitter)
│   ├── store       (RawContentStore, AssetFileStore)
│   └── hash        (HashUtils)
├── pipeline
│   ├── discovery   (DiscoveryRunner)
│   ├── crawl       (CrawlRunner)
│   ├── parse       (ParseRunner)
│   └── push        (PushRunner, AssetDownloader, MultipartUploader)
├── source
│   ├── api         (Source, SourceDiscoverer, PageParser, ParseContext, ParseResult, SourceFetchValidator, dto.*)
│   └── klickypedia (KlickypediaSource + parsers + validator)
├── ingest
│   ├── api         (IngestTarget, NormalizedPayload, DownloadedAsset, PushOutcome)
│   └── nookx       (KlickypediaSetsTarget, NormalizedSetDto, ...)
├── job             (JobRun, JobRunRepository, JobRunner, JobLogAppender)
├── domain          (ScrapePage, ParsedPayload, ParsedAsset, JobRun, JobLog + enumeration)
├── repository      (Spring Data repos)
├── config          (CrawlerProperties, JacksonConfig, SchedulingConfig)
├── web             (controllers Thymeleaf + HTMX endpoints)
└── NookxCrawlerApplication
```

Convenciones (acordes a `AGENTS.md`):
- `@RequiredArgsConstructor` + `@Slf4j` Lombok donde aplique.
- `@Service` para lógica, `@Repository` JPA, `@Component` Spring genéricos.
- Inmutabilidad: records para DTOs, `@Builder(setterPrefix = "with")` para entidades complejas.
- 4-space indent, 120 col, parámetros y locales `final`.
- Sin `var`. Early returns. Mappers MapStruct.

## 7. Configuración (`application.yml`)

```yaml
crawler:
  http: { user-agent: ..., connect-timeout-ms: 10000, request-timeout-ms: 30000 }
  storage: { raw-dir: ..., asset-dir: ... }
  sources:
    klickypedia:
      enabled: true
      min-delay-ms: 1500
      jitter-ms: 500
      discovery-cron: "0 0 0 ? * MON"
  ingest-targets:
    klickypedia-sets:
      enabled: true
      base-url: ${NOOKX_BASE_URL}
      api-key: ${NOOKX_API_KEY}
      max-batch-size: 100
      push-cron: "0 */5 * * * *"
  schedule:
    crawl-delay-ms: 15000
    parse-delay-ms: 5000
  job:
    log-retention-days: 90
    log-min-level: INFO
```

Notas:
- Cron de **discovery** vive bajo `sources.<code>` (lo dispara el source).
- Cron de **push** vive bajo `ingest-targets.<code>` (lo dispara el target). Decisión E14.
- Crawl y Parse siguen con fixed-delay global (son genéricos, no dependen del source/target).

## 8. Dashboard

Single-page server-rendered (Thymeleaf), con HTMX para refresh por polling cada N seg sin reload completo.

### 8.1 Vistas

1. **Home / Overview**
   - Cards: jobs corriendo ahora, jobs en últimas 24h (success/failed), pending por etapa, errores por etapa.
   - Lista live de `RUNNING` job_runs (auto-refresh cada 3s vía HTMX).

2. **Sources**
   - Lista de sources con: enabled, último discovery, próximo discovery (cron), pending crawl, pending parse, errores.
   - Botones por fila: **Run Discovery**, **Run Crawl** (filtrado a este source), **Reset crawl errors**, **Reset parse errors**.

3. **Ingest Targets**
   - Lista de targets con: enabled, último push, próximo push (cron), payloads pending / pushed / failed, último error.
   - Botones por fila: **Run Push**, **Reset push errors**.

4. **Pages (`scrape_page`)**
   - Tabla filtrable por source, page_type, fetch_status, parse_status. Search por URL.
   - Acción por fila: re-fetch, re-parse.

5. **Payloads (`parsed_payload`)**
   - Tabla filtrable por ingest_target, push_status. Search por external_id.
   - Drill-in: ver JSON, ver assets, ver job_runs que lo tocaron.
   - Acción por fila: re-push individual.

6. **Jobs (`job_run`)**
   - Histórico, filtrable por etapa/scope/status.
   - Drill-in a un job: métricas, logs estructurados (`job_log`), botón "tail" si está RUNNING.

7. **Logs**
   - Tail de logs file en vivo (HTMX SSE o polling endpoint que devuelve últimas N líneas).
   - Por defecto últimos 500 lines.

### 8.2 Endpoints HTTP

```
GET  /                                   → overview
GET  /sources                            → list
POST /sources/{code}/discovery/run       → trigger
POST /sources/{code}/crawl/run           → trigger
POST /sources/{code}/errors/reset?stage=crawl|parse → bulk reset
GET  /ingest-targets                     → list
POST /ingest-targets/{code}/push/run     → trigger
POST /ingest-targets/{code}/errors/reset → bulk reset
GET  /pages                              → list (filterable)
POST /pages/{id}/re-fetch
POST /pages/{id}/re-parse
GET  /payloads                           → list
GET  /payloads/{id}                      → detail
POST /payloads/{id}/re-push
GET  /jobs                               → list
GET  /jobs/{id}                          → detail
GET  /jobs/{id}/logs                     → fragment (HTMX)
GET  /logs/tail                          → fragment (HTMX, file tail)
```

Todos los `POST` de trigger crean un `job_run` y devuelven HTMX fragment con el id + status RUNNING.

### 8.3 "Disparar a pedido"

- Manual = crea `job_run(trigger=MANUAL)`, despacha al runner correspondiente.
- Re-entrada protegida por `AtomicBoolean` por `(stage, scope)` → si ya hay uno corriendo en el mismo scope, se devuelve fragment con warning y NO se duplica.
- "Reset errors en bulk" = update SQL: `update parsed_payload set push_status='PENDING', push_last_error=null where ingest_target_code=? and push_status='FAILED'` (análogo para `scrape_page` por etapa). Muestra cuántas filas afectó.

## 9. Logging

Dos canales:

1. **Logback file** (igual que POC): rotativo, todos los niveles, para grep/SSH.
   - Vista del dashboard: tail por endpoint `/logs/tail`.
2. **`job_log` table:** appender custom que cuando un job está activo, escribe INFO+ con `job_run_id` en contexto.
   - Implementación: `MDC.put("jobRunId", id)` al iniciar el job + `JobLogAppender` que filtra por presencia de `jobRunId` y persiste.
   - Drill-in del dashboard usa esta tabla.

## 10. Idempotencia y reentrada

Mantengo todo lo de la POC + los nuevos puntos:

- `scrape_page`: unique `(source_code, url_hash)`.
- `parsed_payload`: unique `(source_code, ingest_target_code, external_id)`.
- `parsed_asset`: unique `(parsed_payload_id, external_url_hash)`.
- HTTP cache (etag / 304) en Crawl.
- Filesystem cache para raw HTML y para assets binarios.
- Idempotency-Key derivado por target en Push.
- `AtomicBoolean` por `(stage, scope)` para evitar runs solapados.
- `payload_hash` permite detectar si un re-parse cambió el contenido (futuro: re-push automático si cambió).

## 11. Throttling

- Por **source** (igual POC): `min-delay-ms` + `jitter-ms` aplicados solo en Crawl.
- Por **ingest target**: opcional `max-batch-size` y eventualmente `min-delay-ms` entre requests del Push (no hace falta arrancar con esto último).

## 12. Migración desde la POC

**No.** Decisión E11: arrancamos limpio. Repo nuevo, schema nuevo, sin data migration. La POC sigue corriendo en paralelo si hace falta.

## 13. Plan de trabajo (fases sugeridas)

Cada fase termina con algo demostrable.

| Fase | Entregable |
|---|---|
| **0** | Repo nuevo (Maven + Spring Boot 3 + Java 21 + Flyway + MySQL + Lombok + jsoup + Tailwind + HTMX). `application.yml` esqueleto. Pipeline vacío que arranca. |
| **1** | Migración Flyway con las 5 tablas (`scrape_page`, `parsed_payload`, `parsed_asset`, `job_run`, `job_log`). Entidades + repos. |
| **2** | Core genérico: `ScraperHttpClient`, `RawContentStore`, `AssetFileStore`, `HashUtils`, `ScraperJitter`. Idéntico a POC. |
| **3** | Abstracciones API: `Source`, `IngestTarget<P>`, `PageParser<P>`, `NormalizedPayload`, `DiscoveredUrl`, `ParseContext`, `ParseResult`, `PushOutcome`, `DownloadedAsset`. |
| **4** | Runners de las 4 etapas + `JobRunner` + `JobLogAppender`. Sin schedules todavía, todo manual. |
| **5** | Implementar primer source completo: `KlickypediaSource` + parsers + `KlickypediaSetsTarget` (port directo de la POC, ahora bajo el nuevo modelo). End-to-end CLI. |
| **6** | `SchedulingConfig` con crons por source (discovery) y por target (push) + fixed-delay para crawl/parse. |
| **7** | Dashboard básico: overview, sources list, ingest targets list, jobs list. Disparo manual + bulk reset de errores. Sin drill-ins. |
| **8** | Dashboard avanzado: pages, payloads, drill-ins, tail de logs, logs estructurados por job. |
| **9** | Hardening: retención de `job_log`, métricas, README operativo, dockerfile, compose. |

## 14. Asunciones (asumir si no se aclara lo contrario)

- Sin auth, deploy en red privada (decisión C9).
- Single-thread por etapa (decisión E12).
- Polling cada 3s en HTMX para vistas live (decisión C6).
- Tablas `job_log` con retención por días, no por count.
- `payload_type` en `parsed_payload` se valida en boot: cada `IngestTarget` registrado declara su tipo y se persiste/se valida match al deserializar.
- IngestTarget code es **único globalmente** (no namespaced por source).
- Naming Java de PushType = **`IngestTarget`** (decisión E13). El campo en DB se llama `ingest_target_code`.

## 15. Cosas explícitamente fuera del scope v2

- Multi-tenant / multi-destino dentro de un IngestTarget (decisión E15: 1 target = 1 destino; si hay staging/prod, son dos targets distintos en config).
- Workers paralelos / colas distribuidas.
- Auth en dashboard.
- API REST pública (el dashboard consume internamente, pero no se expone JSON oficial).
- Migración de datos desde POC.

---

## Para validar antes de empezar

1. ¿Te cierra la división `Source` (descubre + parsea) vs `IngestTarget` (envía)?
2. ¿OK con el shape de `parsed_payload` (json + hash + external_ref) y con que el "tipo" se identifique por `ingest_target_code` + `payload_type`?
3. ¿OK con `job_run` + `job_log` para tracking, y con que los logs estructurados sean **INFO+** únicamente?
4. ¿OK con el set de vistas del dashboard (§8.1) y el set de acciones (run + reset bulk + re-X individual)?
5. ¿OK con el plan de fases (§13)? ¿Querés que las fases sean PRs separados o un único PR grande al final?
6. ¿Confirmás que no hay nada que ya quieras agregar de movida (ej. métricas Prometheus, healthcheck, alerting)?
