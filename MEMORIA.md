# MEMORIA — BuscaReferencias

Documento de continuidad del proyecto. Resume el trabajo realizado **después del último push** (`3ba5f96 Update 0.1`): migración a fotos locales, errores encontrados en pruebas reales, causas y soluciones.

---

## Índice

1. [Resumen ejecutivo](#1-resumen-ejecutivo)
2. [Por qué dejamos la búsqueda online](#2-por-qué-dejamos-la-búsqueda-online)
3. [Cómo funciona hoy](#3-cómo-funciona-hoy)
4. [Errores y correcciones](#4-errores-y-correcciones)
5. [Archivos modificados](#5-archivos-modificados)
6. [Configuración](#6-configuración)
7. [Uso diario](#7-uso-diario)
8. [Límites conocidos](#8-límites-conocidos)

---

## 1. Resumen ejecutivo

| Aspecto | Antes | Ahora |
|---------|-------|-------|
| Fuente de fotos | Pexels, Google Images, HTTP | Carpeta local `cache/thumbnails` (~1294 fotos) |
| Botón de búsqueda | «Lanzar Búsqueda Web» | **«Buscar en fotos»** |
| Tipo de coincidencia | Poses casi exactas | Poses **similares** (tolerancia por parte del cuerpo) |
| Galería | Sin orden claro | **#1 … #10**, del más parecido al menos |
| MediaPipe | API antigua / rutas rotas | Tasks API 0.10+, script en `Python/` |
| Base de datos | A veces sin tablas | Inicialización automática al primer uso |

---

## 2. Por qué dejamos la búsqueda online

### 2.1. Motivos técnicos y de producto

1. **Simplicidad** — No hace falta API key, cuotas ni conexión estable. Las referencias están en disco.
2. **Seguridad y fricción de las APIs** — Pexels y buscadores de imágenes imponen claves, límites de uso y políticas estrictas. Para un estudio de poses de referencia, aportan poco control sobre el contenido real.
3. **Privacidad** — El dibujo y las búsquedas no salen del equipo.
4. **Biblioteca propia** — El artista decide qué entra en `cache/thumbnails`; la app solo ordena por similitud de pose.
5. **El problema real es geométrico** — MediaPipe compara esqueletos; no hace falta buscar por texto en internet.

### 2.2. Qué se eliminó del código

| Eliminado | Motivo |
|-----------|--------|
| `PexelsService.java` | Servicio online ya no usado |
| Descargas HTTP en `ImageCacheService` | Solo archivos locales |
| Términos «google images» / «pinterest» | Eran para búsqueda web |
| `java.net.http` en `module-info` | Ya no hay cliente HTTP |

---

## 3. Cómo funciona hoy

### 3.1. Flujo de búsqueda

```
Usuario dibuja (colores por parte) → DrawingProcessor → joints
                    ↓
          «Buscar en fotos» (analiza dibujo si hace falta)
                    ↓
    ProjectPaths → cache/thumbnails (~1294 imágenes)
                    ↓
    MediaPipe (Python/pose_analyzer.py) → landmarks por foto
                    ↓
    calculateSimilarity (tolerancia + modo parcial si solo cabeza)
                    ↓
    Galería: #1 (más parecido) … hasta #10
```

### 3.2. Dos modelos de datos de pose

| Origen | Estructura en `PoseData` | Contenido |
|--------|--------------------------|-----------|
| Dibujo del usuario | `joints` (por `AnatomyPart`) | Centroides de color en el canvas |
| Foto analizada | `landmarks` (índices MediaPipe) | Puntos del esqueleto detectado |

**Importante:** nunca comparar fotos usando `getAllJoints()`; siempre `getAllLandmarks()`.

### 3.3. Biblioteca grande (~1294 fotos)

- Se listan **todas** las imágenes de la carpeta.
- Las poses ya cacheadas (sesión o SQLite) se puntúan al instante.
- Hasta **400 fotos nuevas** por búsqueda (`search.analysis.limit`), en lote aleatorio para ir cubriendo la biblioteca sin analizar las 1294 de golpe.
- Si ninguna foto obtiene puntuación válida, se muestran igualmente las primeras referencias locales (plan B).

### 3.4. Dibujo parcial (solo cabeza, etc.)

Si el usuario dibuja **4 partes o menos**, la similitud usa **posición absoluta** en el encuadre (coordenadas 0–1), no respecto al torso de la foto. Así un dibujo solo de cabeza puede puntuar > 0.

---

## 4. Errores y correcciones

Cada bloque sigue el mismo esquema: **síntoma → causa → arreglo → por qué pasó**.

---

### 4.1. Sin resultados / galería vacía (joints vs landmarks)

| | |
|---|---|
| **Síntoma** | Mensaje «Sin resultados…» aunque hay cientos de fotos en `cache/thumbnails`. |
| **Causa** | `SearchService` comprobaba `imagePose.getAllJoints()`. Las fotos solo tienen **`landmarks`** (MediaPipe), no `joints`. Siempre `score = -1` y se descartaban. |
| **Arreglo** | Usar `!imagePose.getAllLandmarks().isEmpty()` antes de `calculateSimilarity()`. |
| **Por qué** | El dibujo y la foto usan estructuras distintas en el mismo modelo `PoseData`. |

---

### 4.2. Carpeta de fotos no encontrada (ruta de trabajo)

| | |
|---|---|
| **Síntoma** | «Sin resultados» con la carpeta llena; o carpeta distinta a la del usuario. |
| **Causa** | `cache/thumbnails` relativo al directorio de ejecución (IntelliJ a veces no arranca desde la raíz del proyecto). |
| **Arreglo** | Clase `ProjectPaths`: detecta raíz del proyecto (`pom.xml`, `Python/pose_analyzer.py`). Ruta absoluta en `app.properties` apuntando a la carpeta real del usuario. |
| **Por qué** | Java resuelve rutas relativas respecto al **cwd**, no al `.java`. |

**Ruta actual del usuario:**

`C:/Users/Usuario/IdeaProjects/BuscadorReferenciasColores/BuscaReferencias/cache/thumbnails`

---

### 4.3. Script de Python no encontrado

| | |
|---|---|
| **Síntoma** | MediaPipe no analiza fotos; scores siempre inválidos. |
| **Causa** | `resolveProjectScript` buscaba `pose_analyzer.py` en la raíz; el archivo está en **`Python/pose_analyzer.py`**. |
| **Arreglo** | `ProjectPaths.resolveScript()` busca en `Python/` y en la raíz. |
| **Por qué** | Refactor previo dejó el script en subcarpeta sin actualizar el resolvedor. |

---

### 4.4. MediaPipe en Python (API 0.10+)

| | |
|---|---|
| **Síntoma** | `No module named 'cv2'` o `module 'mediapipe' has no attribute 'solutions'`. |
| **Causa** | En Windows, pip solo ofrece MediaPipe ≥ 0.10, que **eliminó** `mp.solutions.pose`. |
| **Arreglo** | `pose_analyzer.py` reescrito con **MediaPipe Tasks** (`PoseLandmarker`). Descarga automática de `pose_landmarker_lite.task` en `Python/`. |
| **Verificación** | `.\.venv\Scripts\python.exe Python\pose_analyzer.py "ruta\foto.jpg"` → JSON con `"landmarks"` (33 puntos si hay persona). |

---

### 4.5. Similitud siempre 0,0000 (solo cabeza dibujada)

| | |
|---|---|
| **Síntoma** | Logs: `skeleton=0 angles=0 contour=0 final=0` aunque MediaPipe detecta 33 puntos. |
| **Causa** | El algoritmo comparaba la cabeza **respecto al centro del cuerpo** de la foto. Con un solo punto en el dibujo, la distancia normalizada era enorme → score 0. |
| **Arreglo** | `calculatePartialDrawingSimilarity()` para dibujos con ≤ 4 partes: compara posición **absoluta** (0–1) de cada parte dibujada con su landmark. |
| **Por qué** | Dibujo parcial ≠ pose completa; hacía falta otro criterio de similitud. |

---

### 4.6. SQLite: `no such table: Resultados`

| | |
|---|---|
| **Síntoma** | Log: `No se pudo leer la pose cacheada: no such table: Resultados`. |
| **Causa** | `features.dbInitOnStartup=false` y la caché de poses intentaba leer antes de crear tablas. |
| **Arreglo** | `DatabaseManager.ensureInitialized()` crea el esquema en el primer acceso. `features.dbInitOnStartup=true`. |
| **Por qué** | Inicialización perezosa mal coordinada con lecturas de caché en paralelo. |

---

### 4.7. StackOverflowError en búsqueda (recursión infinita en BD)

| | |
|---|---|
| **Síntoma** | `java.lang.StackOverflowError` al pulsar «Buscar en fotos»; traza: `ensureInitialized` ↔ `initDatabase` ↔ `getConnection` en bucle. |
| **Causa** | `getConnection()` llamaba a `ensureInitialized()` → `initDatabase()` → otra vez `getConnection()` → … |
| **Arreglo** | `openConnection()` interno **sin** `ensureInitialized`. Solo `initDatabase()` usa `openConnection()`; `getConnection()` hace `ensureInitialized()` + `openConnection()` una vez. |
| **Por qué** | Se añadió init perezoso en `getConnection()` sin separar apertura cruda de conexión. |

---

### 4.8. UI y mensajes

| Cambio | Detalle |
|--------|---------|
| Botón | «Lanzar Búsqueda Web» → **«Buscar en fotos»** |
| Galería | Badge **#1, #2, …** y etiqueta `#N · 87%` |
| Estado vacío | Mensaje concreto: cuántas fotos hay en carpeta o si falla Python |

---

## 5. Archivos modificados

| Archivo | Rol |
|---------|-----|
| `SearchService.java` | Búsqueda local, ranking 5–10, biblioteca completa, fallback |
| `MediaPipeService.java` | Similitud parcial, sin HTTP, caché de poses |
| `DatabaseManager.java` | `ensureInitialized`, `openConnection`, sin recursión |
| `ProjectPaths.java` | Raíz del proyecto y carpeta de thumbnails |
| `LocalImagePaths.java` | Normalización `file://` y rutas absolutas |
| `PoseToleranceConfig.java` | Tolerancias, min/max resultados, límite de análisis |
| `DrawingController.java` | `handleLocalPhotoSearch`, galería numerada |
| `SearchTermGenerator.java` | Etiquetas locales (español, sin web) |
| `PythonImageSearchClient.java` | Resuelve script vía `ProjectPaths` |
| `Python/pose_analyzer.py` | MediaPipe Tasks 0.10+ |
| `main-view.fxml` | Botón «Buscar en fotos» |
| `app.properties` | Ruta absoluta thumbnails, BD, tolerancias |
| `PexelsService.java` | **Eliminado** |
| Tests | `ProjectPathsTest`, `SearchServiceFinalizeTest`, `LocalImagePathsTest`, etc. |

---

## 6. Configuración

Archivo: `src/main/resources/app.properties`

```properties
# Base de datos
features.dbInitOnStartup=true

# Carpeta de referencias (ruta absoluta recomendada en Windows)
search.local.dir=C:/Users/Usuario/IdeaProjects/BuscadorReferenciasColores/BuscaReferencias/cache/thumbnails

# Resultados mostrados por búsqueda
search.min.results=5
search.max.results=10
search.analysis.limit=400
search.min.score=0.0

# Tolerancia de posición por parte (mayor = más permisivo)
tolerance.skeleton.default=2.5
tolerance.skeleton.head=2.5
tolerance.skeleton.hands=3.5
tolerance.angle.arm=360
tolerance.angle.leg=360
```

| Clave | Significado |
|-------|-------------|
| `search.local.dir` | Carpeta con las fotos de referencia |
| `search.min.results` / `max` | Entre 5 y 10 miniaturas en galería |
| `search.analysis.limit` | Fotos nuevas analizadas con MediaPipe por búsqueda |
| `tolerance.skeleton.*` | Margen de posición por parte del cuerpo |
| `tolerance.angle.*` | Margen de ángulo en grados |

---

## 7. Uso diario

1. Tener fotos en `cache/thumbnails` (jpg, png, webp…).
2. Instalar Python si hace falta: `.\.venv\Scripts\pip install -r requirements.txt`
3. Dibujar con el **color de la paleta** (cabeza = rojo, torso = azul, etc.).
4. Opcional: **Analizar Pose** (genera términos descriptivos).
5. **Buscar en fotos** → galería ordenada #1…#10.
6. Clic en miniatura → abre el archivo local.

### Comprobar MediaPipe manualmente

```bat
.\.venv\Scripts\python.exe Python\pose_analyzer.py "cache\thumbnails\una_foto.jpg"
```

Debe imprimir JSON con `"landmarks"` y `"debug"."points_found"` > 0 si hay una persona.

---

## 8. Límites conocidos

- Menos de 5 imágenes en carpeta → solo se muestran las disponibles.
- Fotos sin persona detectable → score bajo o excluidas del top.
- Primera búsqueda con biblioteca grande puede tardar (hasta 400 análisis Python).
- Compilar requiere `JAVA_HOME` configurado para `mvnw`.
- Dibujar en negro u otro color que no sea el de la paleta → no se detectan partes → búsqueda sin similitud de pose.

---

*Última actualización: mayo 2026 — migración local completa, MediaPipe operativo, galería 5–10 resultados numerados, corrección de BD y similitud para dibujos parciales.*
