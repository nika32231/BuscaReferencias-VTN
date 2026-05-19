from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from ..models.search import CapabilityInfo, SearchRequest, SearchResult
from .python_engine_bridge import DEFAULT_ENGINE_BRIDGE, PythonEngineBridge
from ..utils.cache_manager import CacheManager
from ..utils.settings import settings

DEFAULT_PROVIDERS = settings.default_providers


class SearchService:
    def __init__(
        self,
        cache_manager: Any | None = None,
        engine_bridge: Any | None = None,
    ) -> None:
        self.cache = cache_manager or CacheManager(settings.cache_dir, settings.max_cache_images)
        self.engine = engine_bridge or DEFAULT_ENGINE_BRIDGE

    def search_references(self, request: SearchRequest) -> list[SearchResult]:
        session_dir = self.cache.prepare_current_search(request.sessionId)
        self._persist_request(session_dir, request)
        self.cache.prune_current_search()

        providers = request.providers or DEFAULT_PROVIDERS
        normalized = request.model_copy(update={"providers": providers})
        results = self.engine.search(normalized)
        return results or []

    def capabilities(self) -> CapabilityInfo:
        return CapabilityInfo(
            providers=DEFAULT_PROVIDERS,
            cacheDir=None,
            maxCacheImages=self.cache.max_images,
            onlineSearchEnabled=self.engine.is_ready(),
            mediaPipeEnabled=self.engine.is_ready(),
            playwrightEnabled=self.engine.is_ready(),
        )

    def _persist_request(self, session_dir: Path, request: SearchRequest) -> None:
        payload = request.model_dump(mode="json")
        (session_dir / "request.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        (session_dir / "terms.txt").write_text("\n".join(request.terms), encoding="utf-8")
        metadata = {
            "sessionId": request.sessionId or session_dir.name,
            "providers": request.providers or DEFAULT_PROVIDERS,
            "limit": request.limit,
            "poseDataPresent": request.poseData is not None,
        }
        (session_dir / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")


def get_search_service() -> SearchService:
    return SearchService()

