from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routes.health import router as health_router
from app.routes.search import router as search_router
from app.utils.settings import settings

app = FastAPI(
    title="BuscaReferencias Backend",
    version="0.1.0",
    description="Backend local para listar referencias guardadas y gestionar caché temporal.",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health_router)
app.include_router(search_router)


@app.get("/", tags=["root"])
def root() -> dict[str, object]:
    return {
        "service": "BuscaReferencias Backend",
        "status": "ok",
        "docs": "/docs",
        "health": "/health",
        "search": "/search",
        "searchLegacy": "/api/v1/search/references",
        "config": "/config",
        "localSearchOnly": True,
    }


@app.get("/config", tags=["configuration"])
def get_config() -> dict[str, object]:
    """Devuelve la configuración de feature flags para el cliente."""
    return {
        "rollout_percentage": settings.rollout_percentage,
        "force_local": settings.force_local,
        "force_backend": settings.force_backend,
        "hash_seed": settings.hash_seed,
    }


def create_app() -> FastAPI:
    return app


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.reload,
        log_level=settings.log_level,
    )
