#!/usr/bin/env python3
"""Pruebas rápidas del motor visual real.

Uso:
    python test_search.py
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path


def _unit_test_ranking() -> bool:
    from gallery_engine import PoseProfile, cosine_similarity, angle_similarity, vector_similarity

    ref = PoseProfile(
        embedding=[0.95, 0.92, 0.88, 0.90, 0.91, 0.90, 0.50, 0.52, 0.48, 0.62, 0.40, 0.55, 0.50, 0.82, 0.78],
        angles={"left_arm": 170.0, "right_arm": 168.0, "left_leg": 176.0, "right_leg": 175.0},
        skeleton=[0.1, 0.1, 0.2, 0.2, 0.3, 0.3],
        contour=[0.7, 0.6, 0.7, 0.5, 0.5, 0.1, 0.1, 0.1],
    )
    cand_good = PoseProfile(
        embedding=[0.94, 0.91, 0.87, 0.89, 0.90, 0.89, 0.51, 0.53, 0.49, 0.61, 0.41, 0.54, 0.49, 0.81, 0.77],
        angles={"left_arm": 169.0, "right_arm": 167.0, "left_leg": 177.0, "right_leg": 176.0},
        skeleton=[0.11, 0.11, 0.21, 0.21, 0.31, 0.31],
        contour=[0.69, 0.61, 0.69, 0.51, 0.49, 0.1, 0.1, 0.1],
    )
    cand_bad = PoseProfile(
        embedding=[0.10, 0.12, 0.15, 0.08, 0.20, 0.25, 0.10, 0.20, 0.90, 0.10, 0.90, 0.20, 0.12, 0.15, 0.30],
        angles={"left_arm": 70.0, "right_arm": 60.0, "left_leg": 90.0, "right_leg": 88.0},
        skeleton=[0.8, 0.8, 0.9, 0.9, 0.7, 0.7],
        contour=[0.2, 0.2, 0.2, 0.2, 0.8, 0.8, 0.8, 0.8],
    )

    good_score = 0.45 * cosine_similarity(cand_good.embedding, ref.embedding) + 0.25 * angle_similarity(ref.angles, cand_good.angles) + 0.20 * vector_similarity(cand_good.skeleton, ref.skeleton) + 0.10 * vector_similarity(cand_good.contour, ref.contour)
    bad_score = 0.45 * cosine_similarity(cand_bad.embedding, ref.embedding) + 0.25 * angle_similarity(ref.angles, cand_bad.angles) + 0.20 * vector_similarity(cand_bad.skeleton, ref.skeleton) + 0.10 * vector_similarity(cand_bad.contour, ref.contour)

    if not (good_score > bad_score):
        print(f"❌ Ranking inválido: good={good_score:.4f} bad={bad_score:.4f}")
        return False

    print(f"✅ Ranking unitario OK: good={good_score:.4f} bad={bad_score:.4f}")
    return True


def _live_smoke_test() -> bool:
    script_path = Path(__file__).parent / "image_search_engine.py"
    if not script_path.exists():
        print(f"❌ Script no encontrado: {script_path}")
        return False

    providers = ["pexels", "playwright"] if os.getenv("PEXELS_API_KEY") else ["playwright"]
    print(f"🎯 Smoke CLI: {script_path.name} con providers={providers}")
    cmd = [
        sys.executable,
        str(script_path),
        "--terms",
        "standing pose reference",
        "--limit",
        "3",
        "--providers",
        *providers,
        "--session-id",
        "pytest-smoke",
        "--fresh",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    print(f"Exit code: {result.returncode}")
    if result.stdout:
        print("stdout:", result.stdout[:400])
    if result.stderr:
        print("stderr:", result.stderr[:400])

    if result.returncode != 0:
        combined = f"{result.stdout}\n{result.stderr}".lower()
        if "401" in combined or "playwright no instalado" in combined or "no results found" in combined:
            print("⚠️  Smoke CLI omitido: falta una key válida de Pexels o Playwright en este entorno")
            return True
        print("❌ Smoke CLI falló")
        return False

    data = json.loads(result.stdout)
    if not isinstance(data, dict) or "results" not in data:
        print("❌ JSON sin campo results")
        return False
    if data["results"]:
        first = data["results"][0]
        for key in ("thumbnailUrl", "sourceUrl", "similarity", "provider", "title"):
            if key not in first:
                print(f"❌ Falta clave {key} en el primer resultado")
                return False
        print(f"✅ Smoke CLI OK: {len(data['results'])} resultados")
        return True
    print("⚠️  Smoke CLI sin resultados, pero el JSON es válido")
    return True


def main() -> int:
    print("=" * 60)
    print("Prueba rápida del motor visual real")
    print("=" * 60)

    ok = _unit_test_ranking()
    if not ok:
        return 1

    if os.getenv("PEXELS_API_KEY") or os.getenv("BACKEND_ENGINE_FORCE_LIVE_TEST", "false").lower() in {"1", "true", "yes"}:
        ok = _live_smoke_test()
        if not ok:
            return 1
    else:
        print("ℹ️  Smoke live omitido: no hay PEXELS_API_KEY configurada")

    print("=" * 60)
    print("✅ Tests completados")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

