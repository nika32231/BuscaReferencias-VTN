# Web Frontend Minimo (GitHub Pages)

Esta carpeta `docs/` contiene una version web minima e independiente del proyecto.

## Objetivo

- Mantener intacta la app JavaFX desktop.
- Tener una base visual estable para evolucion web.
- Permitir publicacion en GitHub Pages sin backend ejecutandose en Pages.

## Contenido

- `index.html`: layout principal.
- `style.css`: tema oscuro y responsive basico.
- `app.js`: canvas mock, miniaturas mock y funcion preparada para backend.

## Integracion futura

`app.js` ya incluye la funcion:

```js
async function searchReferences() {
  // futura llamada backend
}
```

La idea objetivo es:

`frontend (GitHub Pages)` -> `backend API (FastAPI o Node)`

## Activar GitHub Pages

En GitHub:

1. Ir a `Settings` -> `Pages`.
2. En `Build and deployment`, elegir `Deploy from a branch`.
3. Seleccionar rama `main` (o la que uses) y carpeta `/docs`.
4. Guardar.

## Nota

GitHub Pages no ejecuta Python/MediaPipe/scraping. Este frontend solo presenta UI base y mocks visuales.

