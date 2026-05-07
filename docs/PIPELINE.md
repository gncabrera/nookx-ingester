# nookx-crawler — Pipeline conceptual

Servicio de ingesta de catálogos. Pipeline de 4 etapas, cada una **independiente, re-ejecutable e idempotente**. Comparten estado a través de tablas en MySQL (`scrape_page`, `source_set`, `source_set_asset`) y de un storage local de bytes crudos (`.html.gz` y assets).

```
Discovery ──▶ Crawl ──▶ Parse ──▶ Push
   │           │          │         │
   ▼           ▼          ▼         ▼
scrape_page  storage   source_set  nookx-api
 (URLs)      (bytes)   + assets    (ingest)
```

Cada etapa lee filas en cierto estado, hace su trabajo, y deja la fila en un nuevo estado. Si algo falla, marca error en la misma fila y se vuelve a intentar en la próxima corrida. Una etapa **nunca** asume que la siguiente o la anterior corrieron en la misma ejecución.

---

## 1. Discovery

**Objetivo:** descubrir URLs nuevas a crawlear y persistirlas en `scrape_page` con estado `PENDING`.

### Conceptos
- Cada `Source` (ej. `klickypedia`) implementa un `SourceDiscoverer`.
- El discoverer sabe **dónde mirar primero** (índices, sitemaps, páginas raíz de categorías) y devuelve una lista de `DiscoveredUrl(url, pageType, naturalKey)`.
- `pageType` clasifica el rol semántico del HTML (`SET_LIST`, `SET_DETAIL`, etc). Define qué parser se usa más adelante.
- `naturalKey` es el identificador estable del recurso dentro del source (slug, código, etc). Sirve para reconciliar y para nombrar el archivo en el storage crudo.

### Persistencia
- Inserta cada URL **idempotentemente** vía `enqueueIfAbsent`: la unique key es `(source_code, sha256(url))`, así que reintentos no duplican.
- Una fila nueva queda con `fetch_status=PENDING`, `parse_status=PENDING`, `next_check_at=now`.

### Trigger
- Cron lento (semanal por defecto) **+** invocación manual.
- Idempotente, así que correrlo de más es seguro.

### Discovery recursivo
- No todo se descubre en esta etapa. El `Parse` también puede emitir `newUrls` (ej. parsear un `SET_LIST` revela cientos de `SET_DETAIL`). Esas URLs entran al mismo `scrape_page` por la misma vía idempotente. **Discovery → Crawl → Parse → Discovery (de nuevas urls)** es un ciclo natural.

---

## 2. Crawl

**Objetivo:** descargar el HTML crudo de cada URL pendiente y guardarlo en disco. **Etapa totalmente genérica**: no entiende el contenido, solo bytes.

### Conceptos
- Lee páginas con `fetch_status` "due" (PENDING o que toca recheckear según `next_check_at`).
- Hace HTTP GET respetando throttle por source (`min-delay-ms` + `jitter-ms` aleatorio).
- Soporta cache HTTP condicional con `ETag` y `Last-Modified` para no re-bajar lo que no cambió → 304 marca `NOT_MODIFIED` sin tocar el storage.
- Antes de persistir, pasa el body por un `SourceFetchValidator` opcional del source (para detectar respuestas que vinieron 200 OK pero son páginas de error/captcha del sitio). Si invalida, marca `FETCH_FAILURE`.
- Si el body es válido: calcula `sha256`, guarda el archivo crudo, persiste path/hash/size/etag en la fila.

### Storage de raw
- `RawContentStore` abstrae el filesystem. Path determinístico: `{sourceCode}/{pageType}/{naturalKey}-{id}.html.gz`. Eso permite:
  - Re-crawlear sin perder el archivo previo (cache hit por path).
  - Skippear el HTTP cuando el archivo ya existe y `--force` no fue pasado.

### Trigger
- Schedule por `fixedDelay` (corto, 15s default).
- Manual con `--limit` y `--force`.

### Idempotencia
- Re-correr no duplica nada: insert no cambia, store sobreescribe el mismo path, `markNotModified` y `markFetchSuccess` son updates.

---

## 3. Parse

**Objetivo:** transformar bytes crudos en un modelo de dominio normalizado y persistirlo en tablas estructuradas (`source_set` + `source_set_asset`).

### Conceptos
- Lee páginas con `parse_status=PENDING` que ya fueron crawleadas.
- Para cada página: usa el `CatalogSource` correcto (`source_code`) y le pide el `PageParser` para ese `page_type`. Si el source no tiene parser para ese tipo (ej. una página sólo sirve para discovery recursivo), se marca `PARSED` y se sigue.
- El parser recibe un `ParseContext` (sourceCode, pageType, url, naturalKey, htmlContent ya leído del storage) y devuelve un `ParseResult` con dos cosas:
  1. **Sets normalizados** (`NormalizedSetDto` + assets) → datos del dominio.
  2. **URLs nuevas** (`DiscoveredUrl`) → para alimentar Crawl recursivamente.
- Los sets se upsertean en `source_set` por `(source_code, source_external_id)`. Los assets se upsertean en `source_set_asset` por `(source_set_id, sha256(external_url))`.
- Las URLs nuevas se enqueuean con la misma `enqueueIfAbsent` que usa Discovery.

### Output
- Toda la lógica específica del sitio vive en el parser concreto (ej. `KlickypediaSetDetailParser` con jsoup, regex de fechas, mapeos de banderas a idiomas, etc).
- El `NormalizedSetDto` es **un único shape** que describe "un set de catálogo": número, nombre, descripción, fecha, atributos JSON libres, lista de assets.
- **Acoplamiento clave (limitación actual):** el parser ya sabe que el destino son "sets" — el output está hardcodeado al modelo `NormalizedSetDto`. No hay forma hoy de que el mismo parser produzca otro tipo de payload.

### Trigger
- Schedule corto + manual con `--limit`.

### Idempotencia
- `upsertFromParse` actualiza si ya existe (mismo external_id) y crea si no. Re-parsear no duplica filas; sí refresca campos y re-aparecen assets nuevos si los hubiera.

---

## 4. Push

**Objetivo:** mandar los datos normalizados a `nookx-api` (sistema downstream) y los binarios de los assets.

### Conceptos
- Dos sub-fases secuenciales por corrida:
  1. **Push de sets**: arma un batch de los `source_set` con `push_status=PENDING`, postea a `/api/admin/ingest/sets` con `Idempotency-Key` derivada del payload, parsea el response item-por-item, marca cada `source_set` como `PUSHED` (guardando el `nookx_set_id` que devolvió el server), `ALREADY_EXISTS` (en caso de unique violation) o `FAILED` con el error.
  2. **Push de assets**: para cada set que ya tiene `nookx_set_id`, busca sus `source_set_asset` pendientes de push. Antes de subir cada asset, lo descarga del origen externo si todavía no está en el `AssetFileStore` local (cache por path). Una vez todos los archivos del set están en disco, hace **un único** multipart POST a `/api/admin/ingest/assets` con todos los archivos del set. Marca cada asset según el resultado individual del response.

### Idempotencia
- `Idempotency-Key` por batch (sha256 truncado del payload o del set+files+filename) deja al server tolerar reintentos.
- Cache de descarga por `assetFileStore.exists(path)` evita re-bajar binarios.
- Re-correr push solo manda los que quedaron en `PENDING`/`FAILED`.

### Trigger
- Schedule corto + manual con `--limit`.
- Se puede deshabilitar globalmente con `crawler.push.enabled=false`.

---

## Propiedades transversales

- **Independencia entre etapas**: cada CLI command y cada `@Scheduled` corre solo su etapa. No hay un "pipeline runner" monolítico. Se pueden disparar manualmente o por schedule, individualmente.
- **Re-entrada segura**: un `AtomicBoolean` por etapa garantiza que dos disparos (manual + schedule) no se pisan dentro del mismo proceso.
- **Estado en DB, no en memoria**: si el proceso muere, el siguiente arranque retoma exactamente donde quedó porque todo el progreso vive en `fetch_status` / `parse_status` / `push_status` por fila.
- **Throttling por source**: `min-delay-ms` + `jitter-ms` configurables por source code → respetar al sitio destino.
- **Cache en cada salto**: HTTP cache en crawl (etag/304), filesystem cache para HTML crudos, filesystem cache para binarios de assets, idempotency-key en push.
- **Modelo `Source`**: una interfaz `CatalogSource` empaqueta `sourceCode + discoverer + (pageType → parser)`. Agregar un nuevo sitio = agregar un `@Component` que implementa esa interfaz + sus parsers concretos. No hay que tocar las commands genéricas.

## Limitaciones de la POC

- El destino del Push es **único y hardcodeado**: el modelo del Parse (`NormalizedSetDto`) y el cliente del Push (`IngestClient`) asumen que lo único que se ingesta son "sets" en `nookx-api`. No hay abstracción para otros tipos de ingest ni para otros destinos.
- No hay UI: todo es CLI + schedules + logs.
- No hay visibilidad en vivo de qué está corriendo, qué está pendiente, qué falló y por qué (hay que mirar logs y queries SQL).
- Disparar una etapa "ya" requiere ssh/cli al proceso, no hay trigger remoto.
