from fastapi import APIRouter

from app.utils.settings import settings

router = APIRouter(tags=["health"])


@router.get("/health")
def health() -> dict[str, object]:
    return {
        "status": "ok",
        "service": "BuscaReferencias Backend",
        "version": "0.1.0",
        "cacheDir": str(settings.cache_dir),
        "maxCacheImages": settings.max_cache_images,
    }

