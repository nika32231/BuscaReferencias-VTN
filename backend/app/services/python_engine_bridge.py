from __future__ import annotations

import json
import subprocess

from ..models.search import SearchRequest, SearchResult
from ..utils.settings import settings


class PythonEngineBridge:
    """Puente al motor Python existente para reutilizar lógica sin reescribir JavaFX."""

    def __init__(self) -> None:
        self.python_bin = settings.python_bin
        self.engine_path = settings.image_engine_path
        self.timeout_seconds = settings.engine_timeout_seconds
        self.enabled = settings.engine_enabled

    def is_ready(self) -> bool:
        return self.enabled and bool(self.python_bin) and self.engine_path.is_file()

    def search(self, request: SearchRequest) -> list[SearchResult]:
        if not self.is_ready():
            return []

        cmd = [
            self.python_bin,
            str(self.engine_path),
            "--terms",
            *request.terms,
            "--limit",
            str(request.limit),
            "--providers",
            *(request.providers or settings.default_providers),
            "--session-id",
            request.sessionId or "backend-session",
        ]

        if request.poseData is not None:
            try:
                cmd.extend(["--pose-json", json.dumps(request.poseData, ensure_ascii=False)])
            except Exception:
                # Si el payload no es serializable, seguimos sin pose_json.
                pass

        try:
            completed = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=self.timeout_seconds,
                check=False,
            )
        except Exception:
            return []

        stdout_text = (completed.stdout or "").strip()
        if not stdout_text:
            return []

        try:
            payload = json.loads(stdout_text)
        except json.JSONDecodeError:
            return []

        raw_results = payload.get("results")
        if not isinstance(raw_results, list):
            return []

        return self._map_results(raw_results, request.limit)

    def _map_results(self, raw_results: list[dict], limit: int) -> list[SearchResult]:
        out: list[SearchResult] = []
        for item in raw_results:
            if not isinstance(item, dict):
                continue

            thumbnail = self._pick(item, "thumbnailUrl", "thumbnail_url", "imageUrl", "original_url")
            source_url = self._pick(
                item,
                "sourceUrl",
                "sourcePageUrl",
                "original_url",
                "imageUrl",
                "thumbnailUrl",
                "thumbnail_url",
            )

            if not thumbnail:
                continue

            out.append(
                SearchResult(
                    thumbnailUrl=thumbnail,
                    sourceUrl=source_url or thumbnail,
                    similarity=self._normalize_similarity(item.get("similarity", 0)),
                    provider=self._pick(item, "provider", "source", default="backend"),
                    title=self._pick(item, "title", default="Referencia"),
                    cachedPath=None,
                )
            )

            if len(out) >= limit:
                break

        return out

    @staticmethod
    def _pick(data: dict, *keys: str, default: str = "") -> str:
        for key in keys:
            value = data.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return default

    @staticmethod
    def _normalize_similarity(value: object) -> int:
        try:
            numeric = float(str(value))
        except Exception:
            return 0

        if numeric <= 1.0:
            numeric *= 100.0
        return int(max(0.0, min(100.0, numeric)))


DEFAULT_ENGINE_BRIDGE = PythonEngineBridge()

