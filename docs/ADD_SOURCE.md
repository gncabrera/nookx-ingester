# Cómo agregar un nuevo `Source`

> Un `Source` representa un sitio externo del que descubrimos URLs y parseamos contenido. Cada `Source` apunta a **exactamente un** `IngestTarget` (decisión §3.1 del [DESIGN](DESIGN.md)).

Este doc asume que el `IngestTarget` destino **ya existe**. Si no, ver [`ADD_INGEST_TARGET.md`](ADD_INGEST_TARGET.md) primero.

---

## Checklist

- [ ] 1. Crear paquete `source/<sourceCode>/`
- [ ] 2. `XxxConstants.java` con `SOURCE_CODE`, `INGEST_TARGET_CODE`, `PAGE_TYPE_*`, base URLs
- [ ] 3. `XxxDiscoverer implements SourceDiscoverer`
- [ ] 4. Uno o más `XxxYyyParser implements PageParser<P>` por cada `pageType`
- [ ] 5. `XxxSource implements Source` (compone discoverer + parsers, declara `ingestTargetCode`)
- [ ] 6. (Opcional) `XxxFetchValidator implements SourceFetchValidator` para detectar HTTP 200 con HTML basura
- [ ] 7. Registrar config en `application.yml` bajo `ingester.sources.<sourceCode>`
- [ ] 8. Verificar wiring (boot logs `[Ingester/SourceRegistry] - REGISTER`)

---

## 1. Constants

Centralizar los strings constantes y URLs base.

```java
public final class FooConstants {

    public static final String SOURCE_CODE = "foo";
    public static final String INGEST_TARGET_CODE = "foo-items";
    public static final String PAGE_TYPE_INDEX = "INDEX";
    public static final String PAGE_TYPE_DETAIL = "DETAIL";

    static final String BASE_URL = "https://www.foo.example";
    static final String INDEX_URL = BASE_URL + "/items/";

    private FooConstants() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }
}
```

> `SOURCE_CODE` es la **clave canónica**: aparece en `application.yml`, en `scrape_page.source_code`, en `parsed_payload.source_code`, en URLs del dashboard, y en logs MDC. Una vez elegido, **no cambiarlo**.

> `INGEST_TARGET_CODE` debe matchear el `code()` del `IngestTarget` correspondiente. Validado en boot por `IngestTargetRegistry.validateWiring()` — si no matchea, falla el arranque.

## 2. Discoverer

Devuelve una lista de URLs semilla. No descarga (eso lo hace Crawl), no parsea (eso lo hace Parse). Solo bytes → `List<DiscoveredUrl>`.

```java
@Component
@RequiredArgsConstructor
public class FooDiscoverer implements SourceDiscoverer {

    private final ScraperHttpClient httpClient;

    @Override
    public List<DiscoveredUrl> discover() {
        final FetchResult result = httpClient.get(FooConstants.INDEX_URL);
        if (!result.isOk()) {
            return List.of();
        }
        final String html = new String(result.body(), StandardCharsets.UTF_8);
        // jsoup -> List<DiscoveredUrl(url, pageType, naturalKey)>
    }
}
```

- `pageType`: clasifica el rol del HTML; el `Parse` lo usa para resolver el parser concreto.
- `naturalKey`: identificador estable del recurso dentro del source (slug, código). Se usa para nombrar el archivo del raw store.
- Si Discovery falla, devolver `List.of()` y dejar que el log lo refleje. **No tirar excepciones para sitios offline**, los runners lo absorben pero ensucian el `job_run`.

## 3. PageParser por cada `pageType`

Un parser por tipo de página. Recibe el HTML ya descargado y devuelve dos cosas:

1. **Payloads tipados** (`P extends NormalizedPayload`) → datos del dominio.
2. **URLs nuevas** (`DiscoveredUrl`) → alimentan recursivamente Crawl.

```java
@Component
@RequiredArgsConstructor
public class FooDetailParser implements PageParser<NormalizedFooPayload> {

    @Override
    public String pageType() {
        return FooConstants.PAGE_TYPE_DETAIL;
    }

    @Override
    public Class<NormalizedFooPayload> payloadType() {
        return NormalizedFooPayload.class;
    }

    @Override
    public ParseResult<NormalizedFooPayload> parse(final ParseContext context) {
        final Document doc = Jsoup.parse(context.htmlContent(), FooConstants.BASE_URL);
        // ... build NormalizedFooPayload + List<NormalizedAssetDto> ...
        return ParseResult.ofPayload(payload);
    }
}
```

### Reglas del parser

- **Idempotente**: parsear el mismo HTML dos veces produce el mismo resultado. No leer DB ni hacer side effects acá.
- **Sin HTTP**: el parser solo lee `context.htmlContent()`. Si necesita más datos, devolver `DiscoveredUrl` con un `pageType` distinto y dejar que Crawl los traiga.
- **Defensivo con HTML**: usar `selectFirst`, chequear nulls, normalizar whitespace. Sitios viejos (WordPress, etc) tienen markup roto.
- `payloadType()` se usa para deserializar JSON desde `parsed_payload` en Push → debe coincidir con el tipo `P` del `IngestTarget`.

### Variantes comunes

| Caso | Construir |
|---|---|
| Página índice que solo descubre más URLs | `ParseResult.ofUrls(urls)` |
| Página detalle que produce un payload | `ParseResult.ofPayload(payload)` |
| Página índice que descubre URLs y además paginación | mezclar ambos en `new ParseResult<>(payloads, newUrls)` |
| Página irrelevante para el dominio (ej. about) | no registrar parser para ese `pageType`; `ParseRunner` la marca como `PARSED` y sigue |

## 4. Source

Compone discoverer + parsers. Declara el `ingestTargetCode` destino.

```java
@Component
public class FooSource implements Source {

    private final SourceDiscoverer discoverer;
    private final Map<String, PageParser<? extends NormalizedPayload>> parsers;

    public FooSource(
        final FooDiscoverer discoverer,
        final FooIndexParser indexParser,
        final FooDetailParser detailParser
    ) {
        this.discoverer = discoverer;
        final Map<String, PageParser<? extends NormalizedPayload>> map = new LinkedHashMap<>();
        map.put(indexParser.pageType(), indexParser);
        map.put(detailParser.pageType(), detailParser);
        this.parsers = Map.copyOf(map);
    }

    @Override
    public String code() {
        return FooConstants.SOURCE_CODE;
    }

    @Override
    public String ingestTargetCode() {
        return FooConstants.INGEST_TARGET_CODE;
    }

    @Override
    public SourceDiscoverer discoverer() {
        return discoverer;
    }

    @Override
    public Optional<PageParser<? extends NormalizedPayload>> parserFor(final String pageType) {
        return Optional.ofNullable(parsers.get(pageType));
    }
}
```

## 5. (Opcional) `SourceFetchValidator`

Algunos sitios responden `200 OK` con páginas de error/captcha. El validator se ejecuta tras cada GET en Crawl; si devuelve un motivo, la página se marca `FETCH_FAILURE` sin guardar el HTML basura.

```java
@Component
public class FooFetchValidator implements SourceFetchValidator {

    @Override
    public String sourceCode() {
        return FooConstants.SOURCE_CODE;
    }

    @Override
    public Optional<String> invalidFetchReason(final String pageType, final String url, final byte[] body) {
        // detectar marcadores específicos del sitio (ej. WordPress critical error)
        // devolver Optional.of("razón") si es inválido
        return Optional.empty();
    }
}
```

> Solo registrar uno si el sitio realmente miente con el status code. Un validator que siempre devuelve `Optional.empty()` no aporta y suma overhead.

## 6. Configuración

Agregar la sección en `src/main/resources/application.yml`:

```yaml
ingester:
    sources:
        foo:
            enabled: true
            min-delay-ms: 5000
            jitter-ms: 500
            discovery-cron: "0 0 0 ? * MON"
```

| Propiedad | Default | Notas |
|---|---|---|
| `enabled` | `true` | si `false`, Discovery y Parse skipean el source |
| `min-delay-ms` | 1000 | throttle por GET en Crawl (ver `ScraperJitter`) |
| `jitter-ms` | 500 | randomización añadida al min-delay |
| `discovery-cron` | `0 0 0 ? * MON` | cron de Spring; vacío/null = sin schedule (solo manual) |

## 7. Verificación

Tras `mvn spring-boot:run` esperar en logs:

```
[Ingester/SourceRegistry] - REGISTER: source=foo ingestTarget=foo-items enabled=true
[Ingester/Scheduler] - DISCOVERY: source=foo cron=0 0 0 ? * MON
```

Luego desde el dashboard:

1. `http://localhost:8081/sources` → debe aparecer el nuevo `foo` con métricas en cero.
2. Click en **Run Discovery** → ejecuta el discoverer y crea `scrape_page` rows.
3. **Run Crawl** (o esperar al schedule de 15s) → baja HTML.
4. Esperar al schedule de Parse (5s) → genera `parsed_payload` rows.
5. Si tu `IngestTarget` está habilitado, su `push-cron` los empujará al destino.

## Errores comunes

| Síntoma | Causa probable |
|---|---|
| Boot falla con `Source X references unknown ingest target 'Y'` | `INGEST_TARGET_CODE` no matchea ningún `IngestTarget.code()` |
| Discovery corre, no inserta nada | el discoverer devolvió `List.of()` (HTTP fail, selector roto, sitio cambió markup) |
| Parse marca `parse_status=PARSED` sin generar payloads | no hay `PageParser` registrado para ese `pageType`, o el parser devuelve `ParseResult.empty()` |
| `parsed_payload` queda en `PENDING` para siempre | `IngestTarget` deshabilitado, sin `push-cron`, o validator del target rechaza el payload |
