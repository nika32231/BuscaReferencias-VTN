from __future__ import annotations

import os
import sys
from pathlib import Path


class BackendSettings:
    def __init__(self) -> None:
        repo_root = Path(__file__).resolve().parents[3]
        self.host = os.getenv("BACKEND_HOST", "0.0.0.0")
        self.port = int(os.getenv("BACKEND_PORT", "8000"))
        self.reload = self._as_bool(os.getenv("BACKEND_RELOAD", "false"))
        self.log_level = os.getenv("BACKEND_LOG_LEVEL", "info")
        self.max_cache_images = int(os.getenv("BACKEND_MAX_CACHE_IMAGES", "100"))
        self.cache_dir = Path(os.getenv("BACKEND_CACHE_DIR", str(self._default_cache_dir())))
        self.cors_origins = self._parse_origins(os.getenv("BACKEND_CORS_ORIGINS", "*"))
        self.engine_enabled = self._as_bool(os.getenv("BACKEND_ENGINE_ENABLED", "true"))
        self.engine_timeout_seconds = int(os.getenv("BACKEND_ENGINE_TIMEOUT_SECONDS", "120"))
        self.python_bin = os.getenv("BACKEND_PYTHON_BIN", sys.executable)
        self.image_engine_path = Path(
            os.getenv("BACKEND_IMAGE_ENGINE_PATH", str(repo_root / "Python" / "image_search_engine.py"))
        )
        self.default_providers = self._parse_csv(
            os.getenv("BACKEND_DEFAULT_PROVIDERS"),
            ["pixabay", "pexels", "unsplash", "bing", "flickr", "playwright", "pinterest"],
        )
        # Feature flags para rollout progresivo
        self.rollout_percentage = int(os.getenv("BACKEND_ROLLOUT_PERCENTAGE", "0"))
        self.force_local = self._as_bool(os.getenv("BACKEND_FORCE_LOCAL", "false"))
        self.force_backend = self._as_bool(os.getenv("BACKEND_FORCE_BACKEND", "false"))
        self.hash_seed = os.getenv("BACKEND_HASH_SEED", "default")

    @staticmethod
    def _default_cache_dir() -> Path:
        return Path(__file__).resolve().parents[3] / "cache" / "current_search"

    @staticmethod
    def _as_bool(value: str | None) -> bool:
        if value is None:
            return False
        return value.strip().lower() in {"1", "true", "yes", "y", "on"}

    @staticmethod
    def _parse_origins(value: str) -> list[str]:
        origins = [item.strip() for item in value.split(",") if item.strip()]
        return origins or ["*"]

    @staticmethod
    def _parse_csv(value: str | None, fallback: list[str]) -> list[str]:
        if value is None:
            return fallback
        items = [item.strip() for item in value.split(",") if item.strip()]
        return items or fallback


settings = BackendSettings()

