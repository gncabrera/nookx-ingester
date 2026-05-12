# Cómo agregar un nuevo `IngestTarget`

> Un `IngestTarget<P>` es el destino tipado de un payload normalizado. Lo emite el `Source` (vía Parse), y un `IngestTarget` lo recibe en Push, lo valida, lo manda al sistema downstream (HTTP REST), y reporta resultado por payload y por asset.

Reglas clave (DESIGN §3.2):

- **1 target = 1 destino**. Si querés mandar a staging y prod, son dos targets distintos.
- El target **no descarga assets** ni maneja persistencia local — eso es genérico (`AssetDownloader` + `RawContentStore`).
- El target **sí decide** la forma del request body, el header de auth, el `Idempotency-Key`, la lectura del response, y el mapeo de errores.
- El `code()` del target debe ser **único globalmente** (no namespaced por source).

---

## Checklist

- [ ] 1. Crear paquete `ingest/<targetCode>/`
- [ ] 2. `NormalizedXxxPayload implements NormalizedPayload` (record + validación en compact constructor)
- [ ] 3. (Opcional) `XxxIngestClient` — wrapper HTTP del destino (ver `NookxIngestClient` como referencia)
- [ ] 4. `XxxTarget implements IngestTarget<NormalizedXxxPayload>` (validate / push / idempotencyKey / code / payloadType)
- [ ] 5. Registrar config en `application.yml` bajo `ingester.ingest-targets.<targetCode>`
- [ ] 6. Apuntar al menos un `Source.ingestTargetCode()` a este `code()`
- [ ] 7. Verificar boot logs `[Ingester/IngestTargetRegistry] - REGISTER`

---

## 1. Payload tipado

Cada target define **su propio DTO**. Es un `record` que implementa `NormalizedPayload`.

```java
public record NormalizedFooPayload(
    String externalId,
    String code,
    String name,
    LocalDate releaseDate,
    JsonNode rawAttributes,
    List<NormalizedAssetDto> assets
) implements NormalizedPayload {

    public NormalizedFooPayload {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
        if (assets == null) {
            assets = List.of();
        }
    }
}
```

Reglas:

- **`externalId` es obligatorio**: identificador estable dentro del source. Forma parte de la unique key `(source_code, ingest_target_code, external_id)` en `parsed_payload`.
- **`assets`** se completa con `NormalizedAssetDto(externalUrl, kind, label, sortOrder)` en el parser. El runtime de Push descarga cada uno antes de pasarlos al `target.push()`.
- **Validar invariantes en el compact constructor** (no en `validate()` del target). El compact constructor protege ante deserialización corrupta desde DB.
- **Sin lógica de negocio en el record**, solo data + checks de invariantes mínimos.
- Para campos abiertos / metadata variable usar `JsonNode` (Jackson) — se serializa a `payload_json` en DB y vuelve sin pérdida.

## 2. Cliente HTTP (opcional pero recomendado)

Si el destino es un solo endpoint trivial, mete el `HttpClient` directo en el target. Si tiene varios endpoints, headers compartidos, multipart, etc, separá en una clase cliente.

Patrón usado por `NookxIngestClient`:

```java
public class FooApiClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;

    public FooApiClient(final ObjectMapper objectMapper, final String baseUrl, final String apiKey) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public IngestHttpResult createItem(final JsonNode payload, final String idempotencyKey) {
        // POST con header X-Api-Key + Idempotency-Key, return (status, JsonNode body)
    }

    public record IngestHttpResult(int status, JsonNode body) {}
}
```

Notas:

- **No es un `@Component`**: lo instancia el target en `@PostConstruct` con la config concreta del target. Esto permite que el mismo cliente se reuse para varios targets con baseUrls distintas (ej. staging vs prod).
- **`Idempotency-Key`** en cada request: el destino lo usa para tolerar reintentos. Derivarla del payload (sha256 truncado), no de timestamps.
- **Connect timeout corto** (10s), **request timeout largo** para batches/multipart (60–120s).
- Devolver `(status, body)` raw — la decisión de qué hacer con cada status es del target.

## 3. El Target

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class FooTarget implements IngestTarget<NormalizedFooPayload> {

    private static final String OPTION_TENANT_ID = "tenant-id";
    private static final long DEFAULT_TENANT_ID = 1L;

    private final IngesterProperties properties;
    private final ObjectMapper objectMapper;

    private FooApiClient client;
    private long tenantId;

    @PostConstruct
    public void init() {
        final IngestTargetConfig config = properties.getIngestTargets().get(code());
        if (config == null) {
            throw new IllegalStateException("Missing ingest-target config for " + code());
        }
        this.client = new FooApiClient(objectMapper, config.getBaseUrl(), config.getApiKey());
        this.tenantId = parseLongOption(config, OPTION_TENANT_ID, DEFAULT_TENANT_ID);
        log.info("[Ingester/FooTarget] - INIT: baseUrl={} tenantId={}", config.getBaseUrl(), this.tenantId);
    }

    @Override
    public String code() {
        return "foo-items";
    }

    @Override
    public Class<NormalizedFooPayload> payloadType() {
        return NormalizedFooPayload.class;
    }

    @Override
    public void validate(final NormalizedFooPayload payload) {
        if (payload.code() == null || payload.code().isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (payload.name() == null || payload.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }

    @Override
    public String idempotencyKey(final NormalizedFooPayload payload) {
        return "foo-" + HashUtils.sha256(payload.externalId()).substring(0, 20);
    }

    @Override
    public PushOutcome push(final NormalizedFooPayload payload, final List<DownloadedAsset> assets) {
        final ObjectNode body = buildRequestBody(payload);
        final IngestHttpResult response = client.createItem(body, idempotencyKey(payload));

        if (response.status() == 201) {
            final String externalRef = response.body().path("id").asText(null);
            final List<AssetOutcome> assetOutcomes = pushAssets(payload, externalRef, assets);
            return PushOutcome.pushed(externalRef, assetOutcomes);
        }
        if (response.status() == 409) {
            return PushOutcome.alreadyExists(null);
        }
        return PushOutcome.failed("Unexpected status " + response.status() + ": " + response.body());
    }

    // ... buildRequestBody / pushAssets helpers ...
}
```

### Detalles importantes

#### `code()`
String inmutable, único globalmente. Suele coincidir con el package o ir en un `Constants` si lo comparte el `Source`. Aparece en `parsed_payload.ingest_target_code`, en `application.yml`, en URLs del dashboard.

#### `payloadType()`
Devuelve `Class<P>`. El runtime lo usa en `objectMapper.treeToValue(row.getPayloadJson(), target.payloadType())`. Validado al boot por `IngestTargetRegistry`.

#### `validate(P)`
Sync, lanza `IllegalArgumentException` con mensaje claro. Se ejecuta antes de cada `push()`. **No incluir validaciones que ya están en el compact constructor** del record (eso es defensa en profundidad innecesaria).

#### `idempotencyKey(P)`
Determinístico, **basado en el contenido del payload**, no en timestamps ni randoms. Suele ser un prefijo + sha256 truncado. El destino lo usa para deduplicar reintentos.

#### `push(P, List<DownloadedAsset>)`
El corazón. Devolver siempre un `PushOutcome`:

| Resultado | Constructor |
|---|---|
| Pusheado OK con assets | `PushOutcome.pushed(externalRef, assetOutcomes)` |
| Ya existía (unique violation, 409, etc) | `PushOutcome.alreadyExists(externalRef)` |
| Falló (status raro, error de red, validación server) | `PushOutcome.failed(errorMessage)` |

**Nunca tirar excepciones desde `push()`** — atraparlas y devolver `PushOutcome.failed()`. Si tirás, el `PushRunner` igual las absorbe pero el job_run queda con status SUCCESS y la métrica `failed` infla, no muestra el motivo en `push_last_error`.

#### Push de assets (multipart)
Patrón general:

1. Filtrar/ordenar `List<DownloadedAsset>` (ya descargados, con `Path` local).
2. Armar multipart con todos los archivos en una sola request si el destino lo soporta — minimiza overhead.
3. Derivar idempotency key del set de archivos (`nookxSetId + ":" + count + ":" + firstFilename` por ejemplo).
4. Mapear el array de results del response 1:1 con la lista de assets enviados → un `AssetOutcome(parsedAssetId, status, error)` por asset.

Si el destino no soporta batch, hacer un POST por asset y agregar los outcomes manualmente.

## 4. Configuración

```yaml
ingester:
    ingest-targets:
        foo-items:
            enabled: true
            base-url: ${FOO_BASE_URL:http://localhost:9000}
            api-key: ${FOO_API_KEY:dev-key}
            max-batch-size: 50
            push-cron: "0 */5 * * * *"
            options:
                tenant-id: "1"
```

| Propiedad | Default | Notas |
|---|---|---|
| `enabled` | `true` | si `false`, Push no agarra payloads para este target |
| `base-url` | — (NotBlank) | URL del destino sin path |
| `api-key` | — (NotBlank) | header de auth (el target decide qué header) |
| `max-batch-size` | 100 | límite de payloads por corrida de Push |
| `push-cron` | `0 */5 * * * *` | cron de Spring; vacío = solo manual |
| `options` | `{}` | `Map<String, String>` libre; el target lo lee con keys propias |

> **`options`** es la vía estándar para parámetros específicos del target (tenant id, interest id, modo de mapeo, flags). Mantenerlos como `String` y parsearlos en el target con defaults y `try/catch` (ver `KlickypediaSetsTarget.parseInterestId`).

> Secrets (`api-key`) **siempre** vía env var con default placeholder en YAML. Nunca hardcodear el valor real en `application.yml`.

## 5. Wiring con un Source

Para que un Source emita payloads del nuevo tipo:

1. Su(s) `PageParser<P>` deben tener `payloadType() == NormalizedFooPayload.class`.
2. Su `Source.ingestTargetCode()` debe devolver el mismo string que `FooTarget.code()`.

Validado al boot por `IngestTargetRegistry.validateWiring()`. Si no matchea, falla el arranque.

## 6. Verificación

Logs esperados al boot:

```
[Ingester/FooTarget] - INIT: baseUrl=http://localhost:9000 tenantId=1
[Ingester/IngestTargetRegistry] - REGISTER: target=foo-items payloadType=NormalizedFooPayload enabled=true
[Ingester/Scheduler] - PUSH: target=foo-items cron=0 */5 * * * *
```

Luego en el dashboard:

1. `http://localhost:8081/ingest-targets` → debe listarse `foo-items`.
2. Cuando un Source asociado complete Parse, los `parsed_payload` quedan en `PENDING` con `ingest_target_code=foo-items`.
3. **Run Push** (o esperar al cron) → debería pasar a `PUSHED` (con `external_ref` poblado) o `FAILED` (con `push_last_error`).
4. Si `FAILED`: revisar `push_last_error` y los logs del job (`/jobs/{id}` en el dashboard).

## Errores comunes

| Síntoma | Causa probable |
|---|---|
| Boot falla con `Missing ingest-target config for foo-items` | falta la sección bajo `ingester.ingest-targets.<code>` en YAML |
| Boot falla con `Source X references unknown ingest target 'Y'` | el `code()` del target no matchea el `ingestTargetCode()` del source |
| Push deja todo en `FAILED` con `Unexpected status 401` | `api-key` mal seteado, o el destino requiere otro header |
| `push_status=PUSHED` pero `external_ref` vacío | el target no extrajo bien el id del response — chequear path en el JSON devuelto |
| Assets se quedan en `PENDING` cuando el payload está `PUSHED` | `pushAssets()` recibió lista vacía, o el `multipart` falló — chequear `parsed_asset.push_last_error` |
| `409 Conflict` y todo va a `FAILED` en vez de `ALREADY_EXISTS` | el target no está reconociendo el caso de duplicado, agregar branch en el switch del status |
