# Web real para GitHub Pages

La carpeta `docs/` es el **frontend web real** del proyecto.

Está pensado para reutilizar el trabajo ya hecho en desktop:

- la misma paleta anatómica,
- el canvas de dibujo,
- la lógica de detección de colores,
- la generación de términos,
- la galería de resultados con porcentaje y enlace original,
- y la conexión al backend FastAPI existente.

## Qué hace esta versión web

- Funciona en escritorio, móvil y tablet.
- Permite dibujar con ratón o táctil.
- Analiza el canvas localmente para generar términos reales.
- Consulta el backend REST real para obtener imágenes auténticas.
- Muestra miniaturas, porcentaje y enlace de origen.

## Qué no hace GitHub Pages

GitHub Pages solo sirve archivos estáticos:

- `HTML`
- `CSS`
- `JavaScript`

No puede ejecutar directamente:

- JavaFX,
- Java,
- Python,
- MediaPipe,
- Playwright,
- scraping de servidor,
- ni lógica nativa de backend.

Por eso la transición correcta es:

```text
GitHub Pages (frontend estático real)
        ↓
Backend FastAPI / Python actual
        ↓
Motor Python: MediaPipe + Playwright + similarity engine
```

## JPro: análisis realista

JPro sí puede ser útil para renderizar una app JavaFX en navegador, pero tiene una limitación importante:

- puede ayudar a mostrar la UI JavaFX actual,
- pero **no sustituye** el backend Python/MediaPipe/Playwright.

En este proyecto, JPro sería válido solo si el cálculo pesado se mantiene fuera del navegador, por ejemplo:

- JavaFX en el cliente web,
- backend remoto para análisis y búsqueda,
- y el frontend JavaFX hablando con esa API.

Eso significa que **JPro no resuelve por sí solo** toda la app real; la arquitectura híbrida sigue siendo necesaria.

## Recomendación de despliegue

### Opción A: híbrida recomendada

```text
Frontend web responsive en GitHub Pages
        ↓
Backend FastAPI desplegado aparte
        ↓
Python + MediaPipe + Playwright + caché + similarity engine
```

Ventajas:

- cero reescritura total,
- máxima reutilización del motor actual,
- funciona en navegador,
- escalable y mantenible.

### Opción B: JPro + backend remoto

```text
JavaFX renderizado con JPro
        ↓
Backend Python remoto
```

Ventajas:

- reutiliza FXML y controllers JavaFX,
- minimiza cambios en UI desktop.

Desventajas:

- depende de licencia/infraestructura JPro,
- no elimina la necesidad del backend externo,
- no es ideal para GitHub Pages puro.

## Cómo usar esta web

1. Publica `docs/` en GitHub Pages.
2. Despliega el backend FastAPI en un hosting con HTTPS.
3. Introduce la URL pública del backend en la barra superior.
4. Dibuja una pose en el canvas.
5. Pulsa `Analizar dibujo`.
6. Pulsa `Buscar referencias reales`.

## Archivos clave

- `index.html`: estructura web real.
- `style.css`: diseño responsive.
- `app.js`: canvas, análisis de color, conexión API y galería.
- `WEB_TRANSITION.md`: análisis completo de transición y reutilización.

