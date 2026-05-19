from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app
from app.models.search import SearchRequest
from app.utils.cache_manager import CacheManager


class BackendSmokeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(app)

    def test_health_endpoint(self) -> None:
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "ok")
        self.assertIn("maxCacheImages", payload)
        self.assertIn("cacheConfigured", payload)

    def test_search_endpoint_returns_list(self) -> None:
        response = self.client.post(
            "/search",
            json={
                "terms": ["upper body anatomy reference", "arms up pose"],
                "poseData": {"source": "javafx"},
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertIsInstance(response.json(), list)

    def test_search_legacy_endpoint_still_available(self) -> None:
        response = self.client.post(
            "/api/v1/search/references",
            json={"terms": ["pose reference"]},
        )
        self.assertEqual(response.status_code, 200)
        self.assertIsInstance(response.json(), list)

    def test_capabilities_are_local_only(self) -> None:
        response = self.client.get("/api/v1/capabilities")
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertTrue(payload["localSearchEnabled"])
        self.assertFalse(payload["playwrightEnabled"])

    def test_cache_manager_prunes_images(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manager = CacheManager(root, max_images=2)
            session = manager.prepare_current_search("session-a")
            for index in range(4):
                image = Path(session) / f"{index}.jpg"
                image.write_bytes(b"fake-image")
            manager.prune_current_search()
            remaining = sorted(path.name for path in Path(session).glob("*.jpg"))
            self.assertEqual(len(remaining), 2)

    def test_search_request_normalizes_terms(self) -> None:
        request = SearchRequest(terms=["  pose  ", "", "arms up"], poseData=None)
        self.assertEqual(request.terms, ["pose", "arms up"])


if __name__ == "__main__":
    unittest.main()

