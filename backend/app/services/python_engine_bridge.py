from __future__ import annotations

from ..models.search import SearchRequest, SearchResult


class PythonEngineBridge:
    """Stub de compatibilidad; el flujo actual es local y no invoca Python."""

    def is_ready(self) -> bool:
        return False

    def search(self, request: SearchRequest) -> list[SearchResult]:
        return []



DEFAULT_ENGINE_BRIDGE = PythonEngineBridge()

