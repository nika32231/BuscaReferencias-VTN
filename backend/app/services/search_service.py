from __future__ import annotations

import json
import os
from typing import Any

from ..models.search import CapabilityInfo, SearchRequest, SearchResult
from ..utils.cache_manager import CacheManager
from ..utils.settings import settings


class SearchService:
    def __init__(
        self,
        cache_manager: Any | None = None,
    ) -> None:
        self.cache = cache_manager or CacheManager(settings.cache_dir, settings.max_cache_images)

    def search_references(self, request: SearchRequest) -> list[SearchResult]:
        session_dir = self.cache.prepare_current_search(request.sessionId)
        self._persist_request(session_dir, request)
        self.cache.prune_current_search()

        return self._collect_local_images(limit=request.limit)

    def capabilities(self) -> CapabilityInfo:
        return CapabilityInfo(
            providers=["local"],
            cacheDir=str(settings.reference_dir),
            maxCacheImages=self.cache.max_images,
            mediaPipeEnabled=False,
            playwrightEnabled=False,
            localSearchEnabled=True,
        )

    def _persist_request(self, session_dir, request: SearchRequest) -> None:
        payload = request.model_dump(mode="json")
        request_path = os.path.join(session_dir, "request.json")
        terms_path = os.path.join(session_dir, "terms.txt")
        metadata_path = os.path.join(session_dir, "metadata.json")

        with open(request_path, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, ensure_ascii=False, indent=2))

        with open(terms_path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(request.terms))

        metadata = {
            "sessionId": request.sessionId or os.path.basename(session_dir),
            "providers": request.providers or ["local"],
            "limit": request.limit,
            "poseDataPresent": request.poseData is not None,
        }
        with open(metadata_path, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(metadata, ensure_ascii=False, indent=2))

    def _collect_local_images(self, limit: int) -> list[SearchResult]:
        thumbnails_dir = settings.reference_dir
        if not os.path.isdir(thumbnails_dir):
            return []

        image_paths = []
        for root, _, files in os.walk(thumbnails_dir):
            for filename in files:
                candidate = os.fsdecode(os.path.join(root, filename))
                if self._looks_like_image(candidate):
                    image_paths.append(candidate)

        image_paths.sort(key=lambda path: os.path.getmtime(path), reverse=True)

        results = []
        for path in image_paths[: max(1, limit)]:
            uri = "file:///" + os.path.abspath(path).replace("\\", "/")
            results.append(
                SearchResult(
                    thumbnailUrl=uri,
                    sourceUrl=uri,
                    similarity=0,
                    provider="local",
                    title=self._pretty_title(path),
                    cachedPath=uri,
                )
            )
        return results

    @staticmethod
    def _looks_like_image(path: str) -> bool:
        _, ext = os.path.splitext(path)
        return ext.lower() in {".jpg", ".jpeg", ".png", ".webp", ".gif"}

    @staticmethod
    def _pretty_title(path: str) -> str:
        name = os.path.splitext(os.path.basename(path))[0].replace("_", " ").replace("-", " ").strip()
        return name or "Referencia local"


def get_search_service() -> SearchService:
    return SearchService()

