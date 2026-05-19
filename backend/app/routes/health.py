from fastapi import APIRouter

from ..utils.settings import settings

router = APIRouter(tags=["health"])


@router.get("/health")
def health() -> dict[str, object]:
    return {
        "status": "ok",
        "service": "BuscaReferencias Backend",
        "version": "0.1.0",
        "maxCacheImages": settings.max_cache_images,
        "cacheConfigured": bool(settings.cache_dir),
    }

