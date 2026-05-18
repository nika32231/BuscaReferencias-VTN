from __future__ import annotations

import os
from pathlib import Path


class BackendSettings:
    def __init__(self) -> None:
        self.host = os.getenv("BACKEND_HOST", "0.0.0.0")
        self.port = int(os.getenv("BACKEND_PORT", "8000"))
        self.reload = self._as_bool(os.getenv("BACKEND_RELOAD", "false"))
        self.log_level = os.getenv("BACKEND_LOG_LEVEL", "info")
        self.max_cache_images = int(os.getenv("BACKEND_MAX_CACHE_IMAGES", "100"))
        self.cache_dir = Path(os.getenv("BACKEND_CACHE_DIR", str(self._default_cache_dir())))
        self.cors_origins = self._parse_origins(os.getenv("BACKEND_CORS_ORIGINS", "*"))

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


settings = BackendSettings()

