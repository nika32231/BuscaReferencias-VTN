from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routes.health import router as health_router
from app.routes.search import router as search_router
from app.utils.settings import settings

app = FastAPI(
    title="BuscaReferencias Backend",
    version="0.1.0",
    description="Backend híbrido para búsqueda online, análisis visual y caché temporal.",
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
        "search": "/api/v1/search/references",
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

