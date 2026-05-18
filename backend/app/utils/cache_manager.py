from __future__ import annotations

import re
import shutil
from pathlib import Path
from uuid import uuid4

_IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


class CacheManager:
    def __init__(self, root: Path, max_images: int = 100) -> None:
        self.root = root
        self.max_images = max_images
        self.root.mkdir(parents=True, exist_ok=True)

    def prepare_current_search(self, session_id: str | None = None) -> Path:
        self.clear_current_search()
        session_name = self._sanitize_session_name(session_id)
        session_dir = self.root / session_name
        session_dir.mkdir(parents=True, exist_ok=True)
        return session_dir

    @staticmethod
    def _sanitize_session_name(session_id: str | None) -> str:
        candidate = (session_id or uuid4().hex).strip()
        candidate = re.sub(r"[^A-Za-z0-9._-]", "_", candidate)
        candidate = candidate.strip("._-")
        return candidate or uuid4().hex

    def clear_current_search(self) -> None:
        if not self.root.exists():
            return
        for item in self.root.iterdir():
            if item.is_dir():
                shutil.rmtree(item, ignore_errors=True)
            else:
                try:
                    item.unlink()
                except FileNotFoundError:
                    pass

    def prune_current_search(self) -> None:
        image_files = sorted(
            (path for path in self.root.rglob("*") if path.is_file() and path.suffix.lower() in _IMAGE_SUFFIXES),
            key=lambda path: path.stat().st_mtime,
        )
        while len(image_files) > self.max_images:
            victim = image_files.pop(0)
            try:
                victim.unlink()
            except FileNotFoundError:
                continue

    def ensure_limit(self) -> None:
        self.prune_current_search()

