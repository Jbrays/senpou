# Senpou

Extensiones **personales** para [Mihon](https://mihon.app) (y forks).  
Repo pequeño: solo las fuentes que usas. Conviven con las de Keiyoushi (package distinto).

## Fuentes

| Nombre en Mihon | Package Android | Dominio |
|-----------------|-----------------|---------|
| Senpou Manhwa-Latino | `app.senpou.extension.es.manhwalatino` | manhwa-latino.com |
| Senpou Manhwa-ES | `app.senpou.extension.es.manhwaes` | manhwa-es.com |
| Senpou TopComicPorno | `app.senpou.extension.es.topcomicporno` | topcomicporno.com |
| Senpou Ikigai Mangas | `app.senpou.extension.es.ikigaimangas` | dominio variable (default `visorikigai.gettocaboca.com`) |

**No hace falta desinstalar Keiyoushi.** Packages y nombres de fuente son distintos → otra fila en Extensiones / Fuentes.

### Parche Manhwa-Latino (HTTP 410 al buscar)

Se fuerza búsqueda clásica Madara:

```kotlin
override val useLoadMoreRequest = LoadMoreStrategy.Never
```

Evita `admin-ajax.php` / `madara_load_more` cuando el sitio responde **410**.

---

## Uso recomendado: repositorio en Mihon (GitHub)

Sí: **es mejor** subir a GitHub y añadir el repo en Mihon. Así actualizas desde la app sin pasar APKs a mano.

### 1. Crear el repo en GitHub

```bash
cd senpou
git remote add origin git@github.com:Jbrays/senpou.git
git push -u origin main
```

### 2. Firma de release (obligatoria para updates estables)

En este PC ya se generó `signingkey.jks` (local, **no se sube a git**).

Copia de seguridad del `.jks` y de las contraseñas. Si pierdes la clave, Mihon no podrá “actualizar” sobre el mismo package.

Para GitHub Actions, crea secrets:

| Secret | Valor |
|--------|--------|
| `SIGNING_KEY_BASE64` | `base64 -w0 signingkey.jks` |
| `SIGNING_KEY_ALIAS` | `senpou` |
| `KEY_STORE_PASSWORD` | (tu password) |
| `KEY_PASSWORD` | (tu password) |

Fingerprint actual (local dev key):

```text
3312b4d3baf078d661a5a3a78f38a9bf64ae22fce461b4124a871c878394de86
```

### 3. Publicar la rama `repo` (índice + APKs)

**Opción A — local**

```bash
export GITHUB_USER=Jbrays
export GITHUB_REPO=senpou
# si usas la key local por defecto:
export ALIAS=senpou
export KEY_STORE_PASSWORD=senpou-dev-change-me
export KEY_PASSWORD=senpou-dev-change-me

chmod +x scripts/*.sh
./scripts/build-and-publish-local.sh
./scripts/push-repo-branch.sh
```

**Opción B — CI**  
Push a `main` con el workflow `.github/workflows/publish-repo.yml` (requiere secrets).

### 4. Añadir en Mihon

1. Mihon → **Browse** → pestaña **Extensions**  
2. Menú → **Repositories** (o engranaje de repos)  
3. Añadir:

```text
https://raw.githubusercontent.com/Jbrays/senpou/repo/index.min.json
```

4. Actualiza la lista e instala las de **Senpou …**

Si el repo es **privado**, `raw.githubusercontent.com` no sirve sin auth; hazlo **público** o usa otra URL pública (JSDelivr, Cloudflare R2, etc.).

---

## Compilar a mano (sin repo)

```bash
export ALIAS=senpou
export KEY_STORE_PASSWORD=senpou-dev-change-me
export KEY_PASSWORD=senpou-dev-change-me

./gradlew :src:es:manhwalatino:assembleRelease
# APK en src/es/manhwalatino/build/outputs/apk/release/
```

Debug (firma debug de Android Studio):

```bash
./gradlew :src:es:manhwalatino:assembleDebug
```

---

## Estructura

```text
senpou/
  src/es/                 ← solo 4 fuentes
  lib-multisrc/madara/    ← theme compartido
  lib/                    ← libs mínimas
  scripts/                ← build repo + push
  .github/workflows/      ← publish automático
```

Añadir sitio nuevo: crea `src/es/nombresitio/`, compila, vuelve a generar la rama `repo`.

---

## Cloudflare

Si Mihon muestra challenge: WebView de la fuente → completar → volver. Normal en estos sitios.

## Créditos

Código base: comunidad Tachiyomi / Mihon / [Keiyoushi](https://github.com/keiyoushi/extensions-source) (Apache 2.0).  
Senpou no está afiliado a Mihon ni Keiyoushi. Los sitios son de terceros.
