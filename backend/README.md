# Backend híbrido de BuscaReferencias

Este directorio prepara la transición del proyecto hacia una arquitectura híbrida:

- `BuscaReferencias/` mantiene la UI JavaFX actual.
- `backend/` aloja el backend web en `FastAPI`.

## Objetivo de esta primera fase

- Mantener la UI sin cambios.
- Crear una API REST lista para crecer.
- Preparar caché temporal en `cache/current_search/`.
- Dejar listo el terreno para Playwright, MediaPipe, OpenCV, embeddings y ranking por similitud.

## Estructura

```text
backend/
  app/
    main.py
    routes/
    services/
    models/
    utils/
  requirements.txt
```

## Ejecutar localmente

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Endpoints iniciales

- `GET /health`
- `GET /api/v1/capabilities`
- `POST /api/v1/search/references`

## Ejemplo de búsqueda

```json
{
  "terms": [
    "upper body anatomy reference",
    "arms up pose"
  ],
  "poseData": {
    "source": "javafx"
  }
}
```

La respuesta inicial es una lista vacía estable hasta que se conecten proveedores y el motor visual.

