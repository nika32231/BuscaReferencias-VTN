from __future__ import annotations

import os


class BackendSettings:
    def __init__(self) -> None:
        repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
        self.host = os.getenv("BACKEND_HOST", "0.0.0.0")
        self.port = int(os.getenv("BACKEND_PORT", "8000"))
        self.reload = self._as_bool(os.getenv("BACKEND_RELOAD", "false"))
        self.log_level = os.getenv("BACKEND_LOG_LEVEL", "info")
        self.max_cache_images = int(os.getenv("BACKEND_MAX_CACHE_IMAGES", "100"))
        self.cache_dir = os.getenv("BACKEND_CACHE_DIR", self._default_cache_dir(repo_root))
        self.reference_dir = os.getenv("BACKEND_REFERENCE_DIR", self._default_reference_dir(repo_root))
        self.cors_origins = self._parse_origins(os.getenv("BACKEND_CORS_ORIGINS", "*"))
        self.rollout_percentage = int(os.getenv("BACKEND_ROLLOUT_PERCENTAGE", "0"))
        self.force_local = self._as_bool(os.getenv("BACKEND_FORCE_LOCAL", "false"))
        self.force_backend = self._as_bool(os.getenv("BACKEND_FORCE_BACKEND", "false"))
        self.hash_seed = os.getenv("BACKEND_HASH_SEED", "default")

    @staticmethod
    def _default_cache_dir(repo_root: str) -> str:
        return os.path.join(repo_root, "cache", "current_search")

    @staticmethod
    def _default_reference_dir(repo_root: str) -> str:
        return os.path.join(repo_root, "cache", "thumbnails")

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

