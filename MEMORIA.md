# MEMORIA del proyecto — BuscaReferencias

Documento de continuidad: qué cambió desde el último push (`3ba5f96 Update 0.1`), qué fallaba, cómo se arregló y por qué el proyecto usa solo fotos locales.

---

## 1. Resumen ejecutivo

| Antes (online) | Ahora (local) |
|----------------|---------------|
| Pexels, Google Images, descargas HTTP | Solo `cache/thumbnails/` |
| Botón «Lanzar Búsqueda Web» | **«Buscar en fotos»** |
| Poses casi exactas | Poses **similares** con tolerancia por parte del cuerpo |
| Galería sin orden claro | **#1 … #10** del más parecido al menos |

---

## 2. Por qué pasamos de búsqueda online a caché local

### Motivos principales

1. **Simplicidad** — No hace falta API key, cuotas, ni red estable. Las referencias viven en una carpeta del disco.
2. **Seguridad de las APIs** — Pexels y buscadores de imágenes exigen claves, límites y políticas de uso. Para un buscador de poses de estudio, eso añade fricción sin aportar control sobre el contenido.
3. **Privacidad** — El dibujo y las búsquedas no salen del equipo.
4. **Resultados predecibles** — El artista elige qué fotos entran en `cache/thumbnails`; la app solo ordena por similitud de pose.
5. **MediaPipe ya resuelve el problema real** — Lo importante es comparar esqueletos, no el texto de una query en internet.

### Qué se eliminó

- `PexelsService.java` (servicio completo).
- Descargas HTTP en `ImageCacheService`.
- Términos «google images» / «pinterest» en `SearchTermGenerator`.
- Dependencia `java.net.http` en `module-info.java`.

---

## 3. Errores detectados y cómo se corrigieron

### 3.1. Las fotos locales nunca puntuaban (crítico)

**Síntoma:** La galería quedaba vacía o sin orden de similitud aunque hubiera imágenes en `cache/thumbnails`.

**Causa:** En `SearchService` se comprobaba `imagePose.getAllJoints()`, pero MediaPipe rellena **`landmarks`**, no `joints`. La condición fallaba siempre → `score = -1` → se descartaban todas.

**Arreglo:** Usar `!imagePose.getAllLandmarks().isEmpty()` antes de `calculateSimilarity()`.

**Por qué ocurrió:** El dibujo del usuario genera `joints` (colores del canvas); las fotos generan `landmarks` (MediaPipe). Se mezclaron dos modelos de datos distintos en la misma comprobación.

---

### 3.2. Rutas locales en Windows (`file://`, barras, relativas)

**Síntoma:** MediaPipe no encontraba archivos o JavaFX no cargaba miniaturas.

**Causa:** Mezcla de URIs `file:`, rutas relativas y rutas sin normalizar.

**Arreglo:**

- Nueva utilidad `LocalImagePaths` (`toAbsolutePath`, `toFileUri`).
- `MediaPipeService.analyzeImage()` solo acepta rutas locales; rechaza `http(s)://`.
- `SearchService` resuelve la ruta absoluta antes de llamar a Python.

---

### 3.3. MediaPipe en Python roto (API 0.10+)

**Síntoma:** `ModuleNotFoundError: cv2` o `AttributeError: module 'mediapipe' has no attribute 'solutions'`.

**Causa:**

- Entorno sin `opencv-python` / `mediapipe` instalados.
- En Windows, pip solo ofrece MediaPipe **≥ 0.10**, que **eliminó** `mp.solutions.pose`.

**Arreglo:**

- `pose_analyzer.py` reescrito con **MediaPipe Tasks** (`PoseLandmarker`).
- Descarga automática de `pose_landmarker_lite.task` en `Python/` si no existe.
- `requirements.txt` actualizado con versiones mínimas.

**Verificación:** Ejecutar  
`.\.venv\Scripts\python.exe Python\pose_analyzer.py cache\thumbnails\tu_foto.jpg`  
debe devolver JSON con `"landmarks"` (puede estar vacío si la imagen no tiene persona).

---

### 3.4. Pocas o ninguna imagen en galería

**Síntoma:** Menos de 5 miniaturas aunque hubiera muchas fotos en caché.

**Causas combinadas:** Bug de joints (3.1), umbral de score demasiado estricto, sin tope mínimo de resultados.

**Arreglo:**

- `search.min.results=5` y `search.max.results=10` en `app.properties`.
- `SearchService.finalizeResults()`: ordena por score, toma hasta 10; si hay menos de 5 por encima del umbral, **relaja** y rellena con las siguientes mejores (score ≥ 0).

---

### 3.5. UI todavía hablaba de «web»

**Arreglo:** Botones en `main-view.fxml` y método `handleLocalPhotoSearch()` en `DrawingController`. La galería muestra **#1, #2, …** y porcentaje de similitud.

---

## 4. Cómo funciona la búsqueda local ahora

```
Dibujo (colores por parte) → DrawingProcessor → joints
                              ↓
                    Botón «Buscar en fotos»
                              ↓
         cache/thumbnails → MediaPipe (Python) → landmarks
                              ↓
              calculateSimilarity (tolerancia por parte)
                              ↓
         Orden #1 (más parecido) … #10 (menos parecido)
```

### Tolerancia (poses similares, no idénticas)

Configuración en `app.properties`:

| Clave | Efecto |
|-------|--------|
| `tolerance.skeleton.*` | Margen de posición por parte (cabeza, manos, piernas…) |
| `tolerance.angle.arm` / `.leg` | Margen en grados para brazos y piernas |
| `search.min.score` | Puntuación mínima (0 = muy permisivo) |

Valores **más altos** = más resultados parecidos. Valores **más bajos** = más exigente.

---

## 5. Archivos tocados en esta iteración

| Archivo | Cambio |
|---------|--------|
| `SearchService.java` | Solo local, 5–10 resultados, ranking |
| `MediaPipeService.java` | Sin HTTP, rutas locales |
| `LocalImagePaths.java` | **Nuevo** — normalización de rutas |
| `PoseToleranceConfig.java` | Tolerancias + min/max resultados |
| `DrawingController.java` | `handleLocalPhotoSearch`, números en galería |
| `main-view.fxml` | «Buscar en fotos» |
| `SearchTermGenerator.java` | Etiquetas locales (sin Google/Pinterest) |
| `PexelsService.java` | **Eliminado** |
| `Python/pose_analyzer.py` | API Tasks MediaPipe 0.10+ |
| `app.properties` | Carpeta local, tolerancias, 5–10 resultados |
| Tests | Actualizados + `SearchServiceFinalizeTest`, `LocalImagePathsTest` |

---

## 6. Uso para el artista

1. Copiar fotos de referencia a **`cache/thumbnails/`** (jpg, png, webp…).
2. Dibujar la pose con los colores de cada parte del cuerpo.
3. Pulsar **Analizar Pose** (opcional; la búsqueda también analiza si hace falta).
4. Pulsar **Buscar en fotos**.
5. Revisar la galería: **#1** es la más parecida; clic abre el archivo local.

### Requisitos Python

```bat
.\.venv\Scripts\pip install -r requirements.txt
```

La primera ejecución puede descargar el modelo `pose_landmarker_lite.task` (~MB) en `Python/`.

---

## 7. Pendiente / límites conocidos

- Si en `cache/thumbnails` hay **menos de 5** imágenes, solo se mostrarán las disponibles.
- Imágenes sin persona detectable tendrán score bajo y pueden quedar fuera del top (según umbral).
- Compilar en este entorno requiere `JAVA_HOME` configurado para `mvnw`.

---

## 8. Referencia rápida de configuración

```properties
search.local.dir=cache/thumbnails
search.min.results=5
search.max.results=10
search.min.score=0.0
tolerance.skeleton.default=2.5
tolerance.skeleton.hands=3.5
tolerance.angle.arm=360
tolerance.angle.leg=360
```

---

*Última actualización: trabajo posterior al push `3ba5f96` — migración completa a referencias locales con MediaPipe operativo y galería numerada por similitud.*
