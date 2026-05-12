# Notas de implementación para el futuro

> Cosas que no están en el [DESIGN](DESIGN.md) ni en el [PIPELINE](PIPELINE.md) pero que conviene saber antes de extender el sistema. Diferencias entre lo diseñado y lo construido, gotchas conocidos, y deuda técnica priorizada.

---

## 1. Convenciones del codebase

Reforzado por [`AGENTS.md`](../AGENTS.md). Resumen rápido:

- 4 espacios indentación, máximo 120 chars por línea.
- **Sin `var`** — siempre tipo explícito.
- **Todo `final`**: parámetros, locales, fields donde se pueda.
- **Sin comments** salvo: cron expressions, regex patterns, TODOs, given/when/then en tests.
- **Early returns** en vez de `else`. Evitar `Objects.isNull/nonNull` para 1–2 vars (preferir `== null`).
- **Lombok**: `@RequiredArgsConstructor`, `@Slf4j`, `@Builder(setterPrefix = "with")` para entidades complejas. **No** usar `@Data`.
- **`@Transactional` solo a nivel de clase** en `@Service`.
- Logs con placeholder `{}`, nunca concat. Template estándar:
  ```
  log.info("[Ingester/Module] - ACTION: key1={} key2={}", val1, val2);
  ```

## 2. Mappers

El proyecto usa **mappers estáticos puros**, no MapStruct. Patrón:

```java
public final class FooMapper {

    private FooMapper() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }

    public static FooDto toDto(final FooEntity entity) {
        if (entity == null) {
            return null;
        }
        return FooDto.builder()
            .withId(entity.getId())
            // ...
            .build();
    }
}
```

Si en algún momento se introduce MapStruct, hacerlo de manera consistente para todo el módulo — no mezclar.

## 3. Diferencias DESIGN.md vs implementación actual

El diseño (Sept 2024) y el código actual difieren en algunos puntos. Cosas a saber al leer el design:

| Tema | Design dice | Código hace |
|---|---|---|
| Package raíz | `com.nookx.crawler.*` | `com.nookx.ingester.*` (renombrado) |
| Namespace de IngestTarget | `ingest.nookx.*` | `ingest.klickypediasets.*` (por target, no por destino) |
| `IngestTarget.cron()` | parte de la interfaz | sacado, ahora vive en `IngestTargetConfig.pushCron` |
| `PageType` | enum | `String` (más extensible al agregar sources) |
| `payload_type` en DB | FQN del DTO + valida al deserializar | FQN se persiste (ver `ParsedPayloadService.upsertFromParse`), pero **no se valida** contra `target.payloadType()` al pushear |
| `push_type` (campo viejo) | mencionado en design | finalizó como `ingest_target_code` |

## 4. Decisiones de runtime no documentadas

### 4.1 Locks por etapa

`PipelineLockRegistry` usa `ConcurrentHashMap<String, AtomicBoolean>`. La key es `<stage>:<scopeType>:<scopeCode>`. Garantiza que dos triggers (manual + scheduled) **dentro del mismo proceso** no se solapen para el mismo scope. **No es distribuido** — si en el futuro hay réplicas horizontales, hay que mover el lock a DB (FOR UPDATE row lock) o a Redis.

### 4.2 Threading

Single-thread por etapa, `TaskScheduler` con `pool.size: 4` (en YAML). Con 4 hilos se cubre: discovery + crawl + parse + push corriendo en paralelo, **pero** cada uno solo en su lane. Si se agregan más sources/targets, suben las contention en el pool — bumpear `pool.size` proporcionalmente.

### 4.3 `JobLogAppender`

Logback custom que persiste en `job_log` solo cuando `MDC.jobRunId` está presente. `JobContext.bind(jobRunId)` en cada runner setea el MDC; `JobContext.clear()` lo limpia en finally. **Si agregás un nuevo runner**, copiar el patrón:

```java
JobContext.bind(run.getId());
try {
    // ...
} finally {
    JobContext.clear();
    lockRegistry.release(...);
}
```

Si te olvidás del bind, los logs del job no se persisten a `job_log` (siguen yendo al file).

### 4.4 Filtro de nivel para `job_log`

Configurable en `ingester.job.log-min-level` (default `INFO`). DEBUG no se persiste por design (la tabla se inflaría). Si querés DEBUG en un job concreto, hay que cambiar el nivel del logger en runtime — o conformarse con el file log.

### 4.5 Retención

`JobLogRetention` (cron `0 0 3 * * *` por default, 3 AM) borra `job_log` con `ts` más viejo que `log-retention-days`. **No borra `job_run`** — esa tabla crece sin límite. Si llega a ser problema, agregar purge similar.

## 5. Idempotencia en cada etapa

Toda la pipeline es idempotente por diseño. Resumen de las claves:

| Tabla / acción | Unique constraint o key |
|---|---|
| `scrape_page` | `(source_code, sha256(url))` |
| `parsed_payload` | `(source_code, ingest_target_code, external_id)` |
| `parsed_asset` | `(parsed_payload_id, sha256(external_url))` |
| HTTP cache (Crawl) | `If-None-Match` + `If-Modified-Since` → 304 |
| Raw store path | `{sourceCode}/{pageType}/{key[0:2]}/{key}.html.gz` |
| Asset store path | `{sourceCode}/{externalId}/asset-{sortOrder}.{ext}` |
| Push de payload | `Idempotency-Key` (sha256 truncado del `externalId`) |
| Push de assets (batch) | `Idempotency-Key` (sha256 truncado de setId+count+firstFilename) |

**Regla de oro**: cualquier código nuevo en un runner debe ser seguro de re-ejecutar. Si agregás un side effect (escribir a otra tabla, llamar a otra API), pensá cómo se comporta al re-correr.

## 6. Almacenamiento

### 6.1 Layout en disco

```
${INGESTER_RAW_DIR}/         # default /data/raw
└── <sourceCode>/
    └── <pageType_lowercase>/
        └── <key[0:2]>/                # bucket por prefijo de naturalKey
            └── <key>.html.gz          # key = naturalKey o fallback

${INGESTER_ASSET_DIR}/       # default /data/assets
└── <sourceCode>/
    └── <externalId>/
        └── asset-<sortOrder>.<ext>    # ext deducida de la externalUrl
```

Notas:

- Los `.html.gz` se gzippean en `FsRawContentStore` antes de escribir (ahorro ~5x).
- Los assets **no** se gzippean (ya están comprimidos: jpg, png, pdf).
- Re-crawlear sobreescribe el archivo en el mismo path → cache por path.
- El sharding de raw por `key[0:2]` evita que un solo directorio acumule miles de archivos.
- Los nombres se sanitizan (solo `[a-zA-Z0-9._-]`, resto a `_`, max 128 chars).
- Re-deploy a otro host sin compartir el volumen → empieza con cache fría, va a re-bajar todo.

### 6.2 Volumen Docker

`compose.yaml` arranca solo MySQL hoy. Cuando se Dockerice el ingester, montar volúmenes para `/data/raw` y `/data/assets`. Considerar mount read-only en réplicas.

## 7. Schedules y crons

| Etapa | Default | Configurable en | Por scope |
|---|---|---|---|
| Discovery | semanal lunes 00:00 | `sources.<code>.discovery-cron` | source |
| Crawl | fixed-delay 15s | `schedule.crawl-delay-ms` | global |
| Parse | fixed-delay 5s | `schedule.parse-delay-ms` | global |
| Push | cada 5 min | `ingest-targets.<code>.push-cron` | target |
| Job log retention | diario 03:00 | `job.retention-cron` | global |

**Discovery** es por source porque cada sitio sabe cuándo conviene re-explorar (algunos sitios actualizan más seguido). **Push** es por target porque el throttle hacia el destino lo dicta el target (ej. mandar a `nookx-api` cada 1 min, mandar a `archive.org` cada 6 horas).

**Crawl/Parse** son global con fixed-delay porque son genéricos: agarran lo que haya pendiente de cualquier source/target. Si hace falta priorizar un source sobre otro en parse, hoy no hay forma — el parse procesa por `id ASC`.

## 8. Things explicitly NOT implemented

- **Auth en el dashboard**. Está pensado para deploy en red privada.
- **API REST pública**. Solo HTML server-rendered + HTMX fragments.
- **Métricas Prometheus / health endpoint**. No hay actuator wired.
- **Workers paralelos**. Single-thread por (stage, scope).
- **Schema versioning para `payload_json`**. El día que un `IngestTarget` cambie el shape de su `NormalizedXxxPayload`, los registros viejos en `parsed_payload` van a fallar al deserializar. Plan B: campo `schema_version` en payload + migración explícita.
- **Re-push automático cuando `payload_hash` cambia**: ya está implementado en `ParsedPayloadService.upsertFromParse` (resetea `push_status=PENDING` si el hash difiere), **pero los assets no se rebatallan** — si solo cambian assets sin cambiar el payload, no hay re-push. Mejorar: incluir `assets` en el hash o detectar diff de assets independientemente.
- **Backpressure**. Si el destino del Push devuelve 429, no hay backoff exponencial — solo se marca FAILED y se reintenta en el próximo cron.

## 9. Roadmap / Deuda técnica conocida

Ordenado por prioridad real:

### Alta
- **Validación de `payload_type` al deserializar**: el FQN ya se persiste, pero al pushear se confía ciegamente en `target.payloadType()`. Si un IngestTarget cambia su DTO sin migrar la DB, queda silently broken. Agregar `if (!persistedFqn.equals(target.payloadType().getName())) throw ...` o un mapping explícito.
- **Lock distribuido**: si se escala a múltiples instancias, mover `PipelineLockRegistry` a un row lock en DB (`SELECT ... FOR UPDATE` sobre una tabla `pipeline_lock`).
- **Backoff en Push**: detectar 429 / 5xx + Retry-After y agendar el retry en vez de fallar inmediato. Hoy `push_retry_count` se incrementa pero no condiciona el próximo intento.
- **Re-push por cambio de assets**: `payload_hash` no incluye assets — si solo cambian las imágenes, no se detecta. Hashear `payloadJson` + lista canonicalizada de assets.

### Media
- **Métricas Micrometer**: counters por etapa (`ingester_jobs_total{stage,status}`), histogramas de duración. Wire `MeterRegistry` y publicar a Prometheus o JMX.
- **Healthcheck endpoint**: `/actuator/health` + check de DB + check de last successful job_run por etapa.
- **Tail de logs vía SSE** (hoy es polling cada 3s, ver `dashboard.poll-interval-ms`).
- **Retención de `job_run`**: hoy crece sin tope. Borrar runs SUCCESS más viejos que N días.
- **Re-push automático en hash change**: ver punto en §8.

### Baja
- **Multi-target por source**: hoy 1:1. Si un día un source debe alimentar dos destinos (ej. mirror local + producción), revisitar `ingestTargetCode` → `List<String>`.
- **Bulk re-fetch / re-parse / re-push** desde la UI con filtros (no solo por source/target).
- **Histórico de cambios de payload**: hoy `parsed_payload` se sobreescribe en re-parse. Si interesa ver evoluciones, agregar `parsed_payload_version` con FK al actual.

## 10. Testing

Estado actual: **muy poco coverage automatizado**. Antes de iterar agresivo en parsers, conviene:

1. Tests unit de cada `PageParser` con HTML fixture en `src/test/resources/<source>/<pageType>/sample.html` y aserción del `ParseResult`.
2. Tests unit de cada `IngestTarget` con `MockHttpClient` (o WireMock) cubriendo: success 201, conflict 409, error 500, response malformado.
3. Test de wiring: boot el `ApplicationContext` con `@SpringBootTest(properties = ...)` y assertion de que todos los sources tienen su target correspondiente.
4. Integration test del pipeline end-to-end con DB en Testcontainers.

Patrón sugerido para parsers (given/when/then comments OK por convención):

```java
class FooDetailParserTest {

    private final FooDetailParser parser = new FooDetailParser(...);

    @Test
    void parses_full_detail_page() throws Exception {
        // given
        final String html = readFixture("foo/detail/full.html");
        final ParseContext context = new ParseContext("foo", "DETAIL", "https://foo/items/abc", "abc", html);

        // when
        final ParseResult<NormalizedFooPayload> result = parser.parse(context);

        // then
        assertThat(result.payloads()).hasSize(1);
        assertThat(result.payloads().getFirst().externalId()).isEqualTo("abc");
        // ...
    }
}
```

## 11. Operación manual

### Forzar reset de errores

Desde el dashboard hay botones de **Reset errors**, que internamente hacen:

```sql
update scrape_page
set fetch_status = 'PENDING', fetch_last_error = null
where source_code = ? and fetch_status = 'FAILED';

update parsed_payload
set push_status = 'PENDING', push_last_error = null
where ingest_target_code = ? and push_status = 'FAILED';
```

Si necesitás algo más fino (ej. solo errores con cierto mensaje), correr el SQL a mano en MySQL — la app no expone filtros server-side para esto.

### Re-bajar un asset corrupto

1. Borrar el archivo del filesystem (`${INGESTER_ASSET_DIR}/<hashPrefix>/<hash>.<ext>`).
2. Reset del row: `update parsed_asset set downloaded=0, local_path=null, content_hash=null where id=?`.
3. Trigger Push del target → `AssetDownloader.ensureDownloaded()` lo va a re-bajar.

### Cambiar la `api-key` en runtime

La key se lee en `@PostConstruct` del target. **Cambio en runtime requiere restart**. Si esto se vuelve un problema, refactor a lectura on-demand desde `IngesterProperties`.

## 12. Glosario

- **Source code**: identificador del sitio (ej. `klickypedia`).
- **Ingest target code**: identificador del destino (ej. `klickypedia-sets`).
- **Page type**: rol semántico de un HTML (`SET_LIST`, `SET_DETAIL`, etc).
- **Natural key**: id estable del recurso dentro del source (slug, código).
- **External ID**: id estable del payload normalizado dentro del source. Forma parte de la unique key de `parsed_payload`.
- **External ref**: id devuelto por el destino tras el push (ej. `nookxSetId`).
- **Idempotency key**: token determinístico que el destino usa para deduplicar reintentos.
- **Job run**: una ejecución de una etapa con scope concreto (`Push de klickypedia-sets el 2026-05-07 19:00`). Se persiste en `job_run`.
- **Job context**: `MDC` con `jobRunId` que liga los logs SLF4J al `job_run` actual.
