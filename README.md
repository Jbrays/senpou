# Senpou

Extensiones personales para **[Mihon](https://mihon.app)** (y forks compatibles).

Repo **propio y pequeño**: solo las fuentes que usas. No es el monorepo de Keiyoushi.

## Fuentes incluidas

| Extensión        | Módulo                 | Dominio / notas                                      |
|------------------|------------------------|------------------------------------------------------|
| Manhwa-Latino    | `src/es/manhwalatino`  | https://manhwa-latino.com — búsqueda sin `admin-ajax` |
| Manhwa-ES        | `src/es/manhwaes`      | https://manhwa-es.com — mirror / stack Madara        |
| TopComicPorno    | `src/es/topcomicporno` | https://topcomicporno.com — contigencia              |
| Ikigai Mangas    | `src/es/ikigaimangas`  | dominio variable; default `visorikigai.gettocaboca.com` |

### Parche Manhwa-Latino (HTTP 410 en búsqueda)

La plantilla Madara a veces usa `POST …/wp-admin/admin-ajax.php` (`madara_load_more`).
En este sitio esa ruta puede responder **410**. Senpou fuerza:

```kotlin
override val useLoadMoreRequest = LoadMoreStrategy.Never
```

Así la búsqueda usa el GET clásico `?s=…&post_type=wp-manga` (mismo enfoque que TopComicPorno).

Si aún fallara, el siguiente paso es copiar la URL real del buscador del sitio (DevTools → Network) y reescribir `searchMangaRequest`.

### Ikigai y dominios que cambian

- Default actual: `https://visorikigai.gettocaboca.com`
- En preferencias de la fuente: **“Buscar dominio automáticamente”** (lee `ikigaimangas.com`)
- También puedes fijar la URL a mano en preferencias

## Requisitos

- JDK 17+
- Android SDK
- [Android Studio](https://developer.android.com/studio) (recomendado)
- Mihon (o fork) instalado en el dispositivo/emulador

## Compilar

```bash
cd senpou

# Una extensión
./gradlew :src:es:manhwalatino:assembleDebug

# Todas las de Senpou
./gradlew :src:es:manhwalatino:assembleDebug \
          :src:es:manhwaes:assembleDebug \
          :src:es:topcomicporno:assembleDebug \
          :src:es:ikigaimangas:assembleDebug
```

Los APK suelen quedar bajo:

```text
src/es/<fuente>/build/outputs/apk/
```

También puedes abrir la carpeta `senpou` en Android Studio y lanzar el módulo deseado.

## Instalar en el teléfono

1. Si ya tienes la extensión **oficial de Keiyoushi** con el mismo package, **desinstálala** (firma distinta).
2. Instala el APK de Senpou (`adb install -r …` o copiando el archivo).
3. En Mihon: Browse → la fuente debería aparecer.

Packages (igual convención que la comunidad, para no pelear con la API):

- `eu.kanade.tachiyomi.extension.es.manhwalatino`
- `eu.kanade.tachiyomi.extension.es.manhwaes`
- `eu.kanade.tachiyomi.extension.es.topcomicporno`
- `eu.kanade.tachiyomi.extension.es.ikigaimangas`

## Añadir otra fuente más adelante

1. Crea `src/es/<nombresitio>/` (puedes copiar una similar o usar `ext-bootstrap.py` si aplica).
2. Si es un CMS Madara, `theme = "madara"` en `build.gradle.kts`.
3. Compila solo ese módulo.
4. Si el sitio cambia de dominio a menudo, usa `baseUrl { custom("…") }` o `mirrors(…)` como en Ikigai / docs de Keiyoushi.

Estructura:

```text
senpou/
  core/ compiler/ gradle/   ← motor de build (no son sitios)
  lib/                      ← libs mínimas (cryptoaes, i18n para Madara)
  lib-multisrc/madara/      ← theme compartido
  src/es/
    manhwalatino/
    manhwaes/
    topcomicporno/
    ikigaimangas/
```

## Cloudflare

Si Mihon muestra challenge de Cloudflare: abre la fuente en WebView, completa el check y vuelve. Eso es del sitio, no del repo. Las cookies suelen durar un tiempo.

## Créditos / licencia

- Código base de extensiones y theme Madara: comunidad Tachiyomi / Mihon / [Keiyoushi](https://github.com/keiyoushi/extensions-source) (Apache 2.0).
- Senpou es un recorte personal con fuentes y parches propios; no está afiliado a Mihon ni a Keiyoushi.
- Los sitios listados son de terceros; este proyecto no aloja su contenido.

## Mantenimiento rápido

| Síntoma                         | Dónde mirar                                      |
|---------------------------------|--------------------------------------------------|
| Búsqueda 410                    | `useLoadMoreRequest` / `searchMangaRequest`      |
| Dominio Ikigai nuevo            | `ikigaimangas/build.gradle.kts` o prefs en app   |
| Selectores rotos Madara         | clase de la fuente o `lib-multisrc/madara`       |
| Imágenes raras / Content-Type   | interceptor en Manhwa-Latino / Manhwa-ES         |
