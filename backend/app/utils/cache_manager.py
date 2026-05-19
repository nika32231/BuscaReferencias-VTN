from __future__ import annotations

import os
import re
import shutil
from uuid import uuid4

_IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


class CacheManager:
    def __init__(self, root, max_images: int = 100) -> None:
        self.root = os.fspath(root)
        self.max_images = max_images
        os.makedirs(self.root, exist_ok=True)

    def prepare_current_search(self, session_id: str | None = None):
        self.clear_current_search()
        session_name = self._sanitize_session_name(session_id)
        session_dir = os.path.join(self.root, session_name)
        os.makedirs(session_dir, exist_ok=True)
        return session_dir

    @staticmethod
    def _sanitize_session_name(session_id: str | None) -> str:
        candidate = (session_id or uuid4().hex).strip()
        candidate = re.sub(r"[^A-Za-z0-9._-]", "_", candidate)
        candidate = candidate.strip("._-")
        return candidate or uuid4().hex

    def clear_current_search(self) -> None:
        if not os.path.isdir(self.root):
            return
        for name in os.listdir(self.root):
            item = os.path.join(self.root, name)
            if os.path.isdir(item):
                shutil.rmtree(item, ignore_errors=True)
            else:
                try:
                    os.remove(item)
                except FileNotFoundError:
                    pass

    def prune_current_search(self) -> None:
        image_files = []
        for root, _, files in os.walk(self.root):
            for filename in files:
                candidate = os.path.join(root, filename)
                if os.path.splitext(candidate)[1].lower() in _IMAGE_SUFFIXES:
                    image_files.append(candidate)

        image_files.sort(key=lambda path: os.path.getmtime(path))
        while len(image_files) > self.max_images:
            victim = image_files.pop(0)
            try:
                os.remove(victim)
            except FileNotFoundError:
                continue

    def ensure_limit(self) -> None:
        self.prune_current_search()

