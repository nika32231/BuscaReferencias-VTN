from __future__ import annotations

import argparse
import concurrent.futures as cf
import hashlib
import json
import math
import mimetypes
import os
import re
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote_plus, urlencode
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    import cv2  # type: ignore
except Exception:  # pragma: no cover - dependency gate
    cv2 = None

try:
    import mediapipe as mp  # type: ignore
except Exception:  # pragma: no cover - dependency gate
    mp = None

REPO_ROOT = Path(__file__).resolve().parents[1]
CURRENT_SEARCH_ROOT = REPO_ROOT / "cache" / "current_search"
MAX_CACHED_IMAGES = 100
DEFAULT_TIMEOUT = 30
DEFAULT_LIMIT = 20
DEFAULT_POOL_MULTIPLIER = 4
DEFAULT_PROVIDERS = ["pexels", "playwright"]


@dataclass
class RemoteCandidate:
    thumbnail_url: str
    source_url: str
    original_url: str
    provider: str
    title: str
    author: str = ""
    width: int | None = None
    height: int | None = None
    extra: dict[str, Any] = field(default_factory=dict)


@dataclass
class PoseProfile:
    embedding: list[float] = field(default_factory=list)
    angles: dict[str, float] = field(default_factory=dict)
    joints: dict[str, dict[str, float]] = field(default_factory=dict)
    skeleton: list[float] = field(default_factory=list)
    contour: list[float] = field(default_factory=list)
    parts_found: list[str] = field(default_factory=list)


@dataclass
class ScoredCandidate:
    candidate: RemoteCandidate
    thumbnail_path: Path
    similarity: int
    cosine: float
    angles: float
    skeleton: float
    contour: float
    features: dict[str, Any]

    def to_payload(self) -> dict[str, Any]:
        return {
            "thumbnailUrl": self.candidate.thumbnail_url,
            "thumbnail_url": self.candidate.thumbnail_url,
            "sourceUrl": self.candidate.source_url,
            "sourcePageUrl": self.candidate.source_url,
            "originalUrl": self.candidate.original_url,
            "original_url": self.candidate.original_url,
            "imageUrl": self.candidate.original_url,
            "provider": self.candidate.provider,
            "title": self.candidate.title,
            "similarity": self.similarity,
            "score": self.similarity,
            "cachedPath": str(self.thumbnail_path),
            "cached_path": str(self.thumbnail_path),
            "localThumbnailPath": str(self.thumbnail_path),
            "author": self.candidate.author,
            "width": self.candidate.width,
            "height": self.candidate.height,
            "extra": self.candidate.extra,
            "analysis": {
                "cosine": round(self.cosine, 4),
                "angles": round(self.angles, 4),
                "skeleton": round(self.skeleton, 4),
                "contour": round(self.contour, 4),
            },
        }


# ---------------------------------------------------------------------------
# General utilities
# ---------------------------------------------------------------------------


def log(tag: str, message: str) -> None:
    print(f"[{tag}] {message}", file=sys.stderr, flush=True)


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def as_float_list(values: Any) -> list[float]:
    if isinstance(values, list):
        out: list[float] = []
        for value in values:
            try:
                out.append(float(value))
            except Exception:
                continue
        return out
    return []


def as_int(value: Any) -> int | None:
    try:
        if value is None:
            return None
        return int(float(value))
    except Exception:
        return None


def clean_text(value: Any, default: str = "") -> str:
    if isinstance(value, str):
        return value.strip() or default
    return default


def sanitize_session_id(session_id: str | None) -> str:
    raw = clean_text(session_id, default="default")
    raw = re.sub(r"[^A-Za-z0-9._-]", "_", raw)
    raw = raw.strip("._-")
    return raw or "default"


def ensure_clean_current_search(session_id: str) -> Path:
    CURRENT_SEARCH_ROOT.mkdir(parents=True, exist_ok=True)
    for child in CURRENT_SEARCH_ROOT.iterdir():
        if child.is_dir():
            shutil.rmtree(child, ignore_errors=True)
        else:
            try:
                child.unlink()
            except FileNotFoundError:
                pass
    session_dir = CURRENT_SEARCH_ROOT / session_id
    (session_dir / "downloads").mkdir(parents=True, exist_ok=True)
    (session_dir / "analysis").mkdir(parents=True, exist_ok=True)
    return session_dir


def prune_to_max_images(root: Path, max_images: int = MAX_CACHED_IMAGES) -> None:
    image_suffixes = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
    files = sorted(
        [p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in image_suffixes],
        key=lambda p: p.stat().st_mtime,
    )
    while len(files) > max_images:
        victim = files.pop(0)
        try:
            victim.unlink()
        except FileNotFoundError:
            pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_vector(values: Iterable[float], size: int) -> list[float]:
    out = [float(v) for v in values]
    if len(out) < size:
        out.extend([0.0] * (size - len(out)))
    return out[:size]


def cosine_similarity(a: list[float], b: list[float]) -> float:
    if not a or not b:
        return 0.0
    n = min(len(a), len(b))
    arr_a = [float(v) for v in a[:n]]
    arr_b = [float(v) for v in b[:n]]
    norm_a = math.sqrt(sum(v * v for v in arr_a))
    norm_b = math.sqrt(sum(v * v for v in arr_b))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    value = sum(x * y for x, y in zip(arr_a, arr_b)) / (norm_a * norm_b)
    return clamp((value + 1.0) / 2.0)


def vector_similarity(a: list[float], b: list[float]) -> float:
    if not a or not b:
        return 0.0
    n = min(len(a), len(b))
    arr_a = [float(v) for v in a[:n]]
    arr_b = [float(v) for v in b[:n]]
    dist = math.sqrt(sum((x - y) ** 2 for x, y in zip(arr_a, arr_b))) / max(1.0, math.sqrt(float(n)))
    return clamp(1.0 / (1.0 + dist))


def angle_similarity(ref: dict[str, float], cand: dict[str, float]) -> float:
    if not ref or not cand:
        return 0.0
    common = [key for key in ref.keys() if key in cand]
    if not common:
        return 0.0
    scores = []
    for key in common:
        ra = float(ref[key])
        ca = float(cand[key])
        scores.append(clamp(1.0 - abs(ra - ca) / 180.0))
    return float(sum(scores) / len(scores))


# ---------------------------------------------------------------------------
# Search providers
# ---------------------------------------------------------------------------


def _provider_allowed(name: str, providers: list[str]) -> bool:
    providers_set = {p.lower().strip() for p in providers if p and p.strip()}
    return name.lower() in providers_set


def pexels_search(query: str, limit: int) -> list[RemoteCandidate]:
    key = os.getenv("PEXELS_API_KEY")
    if not key:
        raise RuntimeError("PEXELS_API_KEY no configurada")

    url = "https://api.pexels.com/v1/search"
    params = {
        "query": query,
        "per_page": min(max(limit, 1), 80),
        "orientation": "portrait",
    }
    full_url = f"{url}?{urlencode(params)}"
    request = Request(full_url, headers={"Authorization": key, "User-Agent": "Mozilla/5.0"})
    with urlopen(request, timeout=DEFAULT_TIMEOUT) as response:
        data = json.loads(response.read().decode("utf-8"))

    results: list[RemoteCandidate] = []
    for photo in data.get("photos", [])[:limit]:
        src = photo.get("src", {}) if isinstance(photo, dict) else {}
        thumb = clean_text(src.get("medium") or src.get("small") or src.get("tiny") or src.get("large"))
        original = clean_text(src.get("original") or src.get("large2x") or src.get("large"))
        page_url = clean_text(photo.get("url"))
        if not thumb or not page_url:
            continue
        results.append(
            RemoteCandidate(
                thumbnail_url=thumb,
                source_url=page_url,
                original_url=original or thumb,
                provider="pexels",
                title=clean_text(photo.get("alt"), default=query),
                author=clean_text(photo.get("photographer")),
                width=as_int(photo.get("width")),
                height=as_int(photo.get("height")),
                extra={"id": photo.get("id"), "avg_color": photo.get("avg_color")},
            )
        )
    return results


async def google_images_search(query: str, limit: int) -> list[RemoteCandidate]:
    try:
        from playwright.async_api import async_playwright
    except Exception as exc:  # pragma: no cover - optional dependency gate
        raise RuntimeError("Playwright no instalado o no disponible") from exc

    url = f"https://www.google.com/search?q={quote_plus(query)}&tbm=isch"
    results: list[RemoteCandidate] = []
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        context = await browser.new_context(
            user_agent=(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/124.0.0.0 Safari/537.36"
            ),
            locale="es-ES",
        )
        page = await context.new_page()
        await page.goto(url, wait_until="domcontentloaded", timeout=60000)
        try:
            await page.wait_for_selector("img", timeout=10000)
        except Exception:
            pass
        for _ in range(4):
            try:
                await page.mouse.wheel(0, 1200)
                await page.wait_for_timeout(500)
            except Exception:
                pass
        rows = await page.locator("img").evaluate_all(
            """
            els => els.map(e => {
                const src = e.currentSrc || e.src || '';
                const a = e.closest('a');
                const href = a ? (a.href || '') : '';
                return { src, href };
            }).filter(x => !!x.src && x.src.startsWith('http'))
            """
        )
        for row in rows:
            if len(results) >= limit:
                break
            thumb = clean_text(row.get("src"))
            href = clean_text(row.get("href"), default=url)
            if not thumb:
                continue
            results.append(
                RemoteCandidate(
                    thumbnail_url=thumb,
                    source_url=href,
                    original_url=thumb,
                    provider="playwright",
                    title=query,
                    extra={"search": "google_images"},
                )
            )
        await context.close()
        await browser.close()
    return results


# ---------------------------------------------------------------------------
# Reference pose synthesis
# ---------------------------------------------------------------------------


def _reference_keywords(terms: list[str], pose_data: dict[str, Any] | None = None) -> str:
    parts: list[str] = []
    if terms:
        parts.extend(term.lower() for term in terms)
    if isinstance(pose_data, dict):
        parts.extend(str(v).lower() for v in pose_data.get("partsFound", []) if v)
        parts.extend(str(v).lower() for v in pose_data.get("keywords", []) if v)
    return " ".join(parts)


def _pose_family(text: str) -> str:
    if any(token in text for token in ["sitting", "seated", "chair"]):
        return "sitting"
    if any(token in text for token in ["upper body", "portrait", "torso"]):
        return "upper"
    if any(token in text for token in ["lower body", "legs"]):
        return "lower"
    if any(token in text for token in ["contrapposto", "dynamic", "twist", "twisting"]):
        return "dynamic"
    return "standing"


def _reference_angles_from_text(text: str) -> dict[str, float]:
    family = _pose_family(text)
    if family == "sitting":
        return {
            "left_arm": 120.0,
            "right_arm": 120.0,
            "left_leg": 95.0,
            "right_leg": 95.0,
            "left_torso": 82.0,
            "right_torso": 82.0,
            "neck": 88.0,
        }
    if family == "upper":
        return {
            "left_arm": 128.0,
            "right_arm": 128.0,
            "left_leg": 165.0,
            "right_leg": 165.0,
            "left_torso": 96.0,
            "right_torso": 96.0,
            "neck": 94.0,
        }
    if family == "lower":
        return {
            "left_arm": 165.0,
            "right_arm": 165.0,
            "left_leg": 112.0,
            "right_leg": 112.0,
            "left_torso": 92.0,
            "right_torso": 92.0,
            "neck": 92.0,
        }
    if family == "dynamic":
        return {
            "left_arm": 145.0,
            "right_arm": 150.0,
            "left_leg": 150.0,
            "right_leg": 150.0,
            "left_torso": 78.0,
            "right_torso": 78.0,
            "neck": 90.0,
        }
    return {
        "left_arm": 175.0,
        "right_arm": 175.0,
        "left_leg": 172.0,
        "right_leg": 172.0,
        "left_torso": 90.0,
        "right_torso": 90.0,
        "neck": 90.0,
    }


def _reference_skeleton_from_text(text: str) -> dict[str, dict[str, float]]:
    family = _pose_family(text)
    base = {
        "nose": {"x": 0.50, "y": 0.10},
        "left_shoulder": {"x": 0.40, "y": 0.23},
        "right_shoulder": {"x": 0.60, "y": 0.23},
        "left_elbow": {"x": 0.34, "y": 0.38},
        "right_elbow": {"x": 0.66, "y": 0.38},
        "left_wrist": {"x": 0.30, "y": 0.56},
        "right_wrist": {"x": 0.70, "y": 0.56},
        "left_hip": {"x": 0.44, "y": 0.56},
        "right_hip": {"x": 0.56, "y": 0.56},
        "left_knee": {"x": 0.45, "y": 0.78},
        "right_knee": {"x": 0.55, "y": 0.78},
        "left_ankle": {"x": 0.45, "y": 0.98},
        "right_ankle": {"x": 0.55, "y": 0.98},
    }
    if family == "sitting":
        base["left_knee"] = {"x": 0.41, "y": 0.76}
        base["right_knee"] = {"x": 0.59, "y": 0.76}
        base["left_ankle"] = {"x": 0.35, "y": 0.84}
        base["right_ankle"] = {"x": 0.65, "y": 0.84}
    elif family == "upper":
        base["left_hip"] = {"x": 0.46, "y": 0.60}
        base["right_hip"] = {"x": 0.54, "y": 0.60}
        base["left_knee"] = {"x": 0.46, "y": 0.90}
        base["right_knee"] = {"x": 0.54, "y": 0.90}
    elif family == "dynamic":
        base["left_wrist"] = {"x": 0.20, "y": 0.24}
        base["right_wrist"] = {"x": 0.80, "y": 0.22}
        base["left_knee"] = {"x": 0.40, "y": 0.80}
        base["right_knee"] = {"x": 0.60, "y": 0.76}
    return base


def _reference_contour_from_text(text: str) -> list[float]:
    family = _pose_family(text)
    if family == "sitting":
        return [0.80, 0.54, 0.72, 0.55, 0.52, 0.18, 0.20, 0.14]
    if family == "upper":
        return [0.92, 0.50, 0.76, 0.48, 0.62, 0.14, 0.12, 0.15]
    if family == "lower":
        return [0.62, 0.68, 0.70, 0.50, 0.58, 0.15, 0.16, 0.13]
    if family == "dynamic":
        return [0.70, 0.58, 0.66, 0.57, 0.65, 0.12, 0.22, 0.18]
    return [0.50, 0.62, 0.78, 0.45, 0.55, 0.10, 0.12, 0.10]


def _embedding_from_features(angles: dict[str, float], skeleton: dict[str, dict[str, float]], contour: list[float]) -> list[float]:
    arm_l = float(angles.get("left_arm", 0.0)) / 180.0
    arm_r = float(angles.get("right_arm", 0.0)) / 180.0
    leg_l = float(angles.get("left_leg", 0.0)) / 180.0
    leg_r = float(angles.get("right_leg", 0.0)) / 180.0
    torso_l = float(angles.get("left_torso", 0.0)) / 180.0
    torso_r = float(angles.get("right_torso", 0.0)) / 180.0
    neck = float(angles.get("neck", 0.0)) / 180.0

    left_shoulder = skeleton.get("left_shoulder", {"x": 0.0, "y": 0.0})
    right_shoulder = skeleton.get("right_shoulder", {"x": 1.0, "y": 0.0})
    left_hip = skeleton.get("left_hip", {"x": 0.0, "y": 0.0})
    right_hip = skeleton.get("right_hip", {"x": 1.0, "y": 0.0})
    left_wrist = skeleton.get("left_wrist", {"x": 0.0, "y": 0.0})
    right_wrist = skeleton.get("right_wrist", {"x": 1.0, "y": 0.0})
    left_ankle = skeleton.get("left_ankle", {"x": 0.0, "y": 1.0})
    right_ankle = skeleton.get("right_ankle", {"x": 1.0, "y": 1.0})

    shoulder_span = abs(float(right_shoulder["x"]) - float(left_shoulder["x"]))
    hip_span = abs(float(right_hip["x"]) - float(left_hip["x"]))
    arm_height = max(0.0, 1.0 - min(float(left_wrist["y"]), float(right_wrist["y"])))
    stance = abs(float(right_ankle["x"]) - float(left_ankle["x"]))
    body_aspect = clamp((abs(float(left_hip["y"]) - float(left_shoulder["y"])) + abs(float(right_hip["y"]) - float(right_shoulder["y"]))) / 2.0)
    contour_strength = contour[1] if len(contour) > 1 else 0.5

    return [
        arm_l,
        arm_r,
        leg_l,
        leg_r,
        torso_l,
        torso_r,
        neck,
        clamp(shoulder_span),
        clamp(hip_span),
        clamp(arm_height),
        clamp(stance),
        clamp(body_aspect),
        clamp(contour_strength),
        clamp((arm_l + arm_r + leg_l + leg_r) / 4.0),
    ]


def build_reference_profile(terms: list[str], pose_data: dict[str, Any] | None) -> PoseProfile:
    text = _reference_keywords(terms, pose_data)
    angles = _reference_angles_from_text(text)
    skeleton = _reference_skeleton_from_text(text)
    contour = _reference_contour_from_text(text)

    if isinstance(pose_data, dict):
        pose_angles = pose_data.get("poseAngles") or pose_data.get("pose_angles") or {}
        if isinstance(pose_angles, dict) and pose_angles:
            clean_angles = {str(k): float(v) for k, v in pose_angles.items() if isinstance(v, (int, float, str))}
            if clean_angles:
                angles.update(clean_angles)

        joints = pose_data.get("joints") or {}
        if isinstance(joints, dict) and joints:
            normalized: dict[str, dict[str, float]] = {}
            for key, value in joints.items():
                if isinstance(value, dict) and ("x" in value and "y" in value):
                    normalized[str(key)] = {
                        "x": clamp(float(value.get("x", 0.0))),
                        "y": clamp(float(value.get("y", 0.0))),
                    }
            if normalized:
                skeleton.update(normalized)

        pose_contour = pose_data.get("contour") or pose_data.get("contourVector")
        if isinstance(pose_contour, list) and pose_contour:
            contour = safe_vector([float(v) for v in pose_contour if isinstance(v, (int, float, str))], 8)

    embedding = _embedding_from_features(angles, skeleton, contour)
    return PoseProfile(embedding=embedding, angles=angles, joints=skeleton, skeleton=_skeleton_vector_from_joints(skeleton), contour=contour, parts_found=[term for term in terms if term])


# ---------------------------------------------------------------------------
# Candidate analysis
# ---------------------------------------------------------------------------


def _angle_from_points(a: tuple[float, float], b: tuple[float, float], c: tuple[float, float]) -> float:
    ba = (a[0] - b[0], a[1] - b[1])
    bc = (c[0] - b[0], c[1] - b[1])
    denom = math.sqrt(ba[0] * ba[0] + ba[1] * ba[1]) * math.sqrt(bc[0] * bc[0] + bc[1] * bc[1])
    if denom == 0.0:
        return 0.0
    cosine = float((ba[0] * bc[0] + ba[1] * bc[1]) / denom)
    cosine = max(-1.0, min(1.0, cosine))
    return float(np.degrees(np.arccos(cosine)))


def _landmark_point(landmarks: list[Any], index: int) -> tuple[float, float] | None:
    if index >= len(landmarks):
        return None
    landmark = landmarks[index]
    try:
        return float(landmark.x), float(landmark.y)
    except Exception:
        return None


def _skeleton_vector_from_joints(joints: dict[str, dict[str, float]]) -> list[float]:
    order = [
        "nose",
        "left_shoulder",
        "right_shoulder",
        "left_elbow",
        "right_elbow",
        "left_wrist",
        "right_wrist",
        "left_hip",
        "right_hip",
        "left_knee",
        "right_knee",
        "left_ankle",
        "right_ankle",
    ]
    points = [joints.get(name, {"x": 0.5, "y": 0.5}) for name in order]
    xs = [float(p.get("x", 0.5)) for p in points]
    ys = [float(p.get("y", 0.5)) for p in points]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    width = max(max_x - min_x, 1e-6)
    height = max(max_y - min_y, 1e-6)
    cx = (min_x + max_x) / 2.0
    cy = (min_y + max_y) / 2.0
    vec: list[float] = []
    for point in points:
        x = (float(point.get("x", 0.5)) - cx) / width
        y = (float(point.get("y", 0.5)) - cy) / height
        vec.extend([clamp((x + 1.5) / 3.0), clamp((y + 1.5) / 3.0)])
    return vec


def _contour_vector_from_image(image_bgr: Any, segmentation_mask: Any | None) -> list[float]:
    if cv2 is None:
        return [0.5, 0.5, 0.5, 0.5, 0.5, 0.0, 0.0, 0.0]

    mask = None
    if segmentation_mask is not None:
        mask = ((segmentation_mask > 0.2).astype("uint8")) * 255
    else:
        gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        _, mask = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        if float(sum(int(v) for v in mask.flatten())) / max(1.0, float(mask.size)) > 127.0:
            mask = 255 - mask

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        h, w = image_bgr.shape[:2]
        return [clamp(w / max(h, 1) / 2.0), 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0]

    largest = max(contours, key=cv2.contourArea)
    x, y, w, h = cv2.boundingRect(largest)
    area = float(cv2.contourArea(largest))
    rect_area = float(max(w * h, 1))
    hull = cv2.convexHull(largest)
    hull_area = float(max(cv2.contourArea(hull), 1.0))
    perimeter = float(max(cv2.arcLength(largest, True), 1.0))
    circularity = float(4.0 * math.pi * area / (perimeter * perimeter))
    extent = area / rect_area
    solidity = area / hull_area
    aspect_ratio = float(w) / float(max(h, 1))
    moments = cv2.HuMoments(cv2.moments(largest)).flatten().tolist()
    hu = []
    for value in moments[:4]:
        if value == 0:
            hu.append(0.0)
        else:
            hu.append(float(np.sign(value) * np.log10(abs(value) + 1e-12)))
    return [
        clamp(aspect_ratio / 2.5),
        clamp(extent),
        clamp(solidity),
        clamp(circularity),
        clamp(float(x + w / 2.0) / float(image_bgr.shape[1])),
        clamp(float(y + h / 2.0) / float(image_bgr.shape[0])),
        clamp((hu[0] + 12.0) / 24.0),
        clamp((hu[1] + 12.0) / 24.0),
    ]


def _summary_embedding(angle_map: dict[str, float], joints: dict[str, dict[str, float]], contour: list[float]) -> list[float]:
    arm_l = float(angle_map.get("left_arm", 0.0)) / 180.0
    arm_r = float(angle_map.get("right_arm", 0.0)) / 180.0
    leg_l = float(angle_map.get("left_leg", 0.0)) / 180.0
    leg_r = float(angle_map.get("right_leg", 0.0)) / 180.0
    torso_l = float(angle_map.get("left_torso", 0.0)) / 180.0
    torso_r = float(angle_map.get("right_torso", 0.0)) / 180.0
    neck = float(angle_map.get("neck", 0.0)) / 180.0

    left_shoulder = joints.get("left_shoulder", {"x": 0.5, "y": 0.5})
    right_shoulder = joints.get("right_shoulder", {"x": 0.5, "y": 0.5})
    left_hip = joints.get("left_hip", {"x": 0.5, "y": 0.5})
    right_hip = joints.get("right_hip", {"x": 0.5, "y": 0.5})
    left_wrist = joints.get("left_wrist", {"x": 0.5, "y": 0.5})
    right_wrist = joints.get("right_wrist", {"x": 0.5, "y": 0.5})
    left_ankle = joints.get("left_ankle", {"x": 0.5, "y": 0.5})
    right_ankle = joints.get("right_ankle", {"x": 0.5, "y": 0.5})
    nose = joints.get("nose", {"x": 0.5, "y": 0.1})

    shoulder_span = abs(float(right_shoulder.get("x", 0.5)) - float(left_shoulder.get("x", 0.5)))
    hip_span = abs(float(right_hip.get("x", 0.5)) - float(left_hip.get("x", 0.5)))
    wrist_height = 1.0 - min(float(left_wrist.get("y", 1.0)), float(right_wrist.get("y", 1.0)))
    stance = abs(float(right_ankle.get("x", 0.5)) - float(left_ankle.get("x", 0.5)))
    spine_height = abs(float(left_hip.get("y", 0.5)) - float(left_shoulder.get("y", 0.5)))
    head_height = 1.0 - float(nose.get("y", 0.1))
    contour_extent = contour[1] if len(contour) > 1 else 0.5
    contour_solidity = contour[2] if len(contour) > 2 else 0.5

    return [
        arm_l,
        arm_r,
        leg_l,
        leg_r,
        torso_l,
        torso_r,
        neck,
        clamp(shoulder_span),
        clamp(hip_span),
        clamp(wrist_height),
        clamp(stance),
        clamp(spine_height),
        clamp(head_height),
        clamp(contour_extent),
        clamp(contour_solidity),
    ]


def _build_joints_from_landmarks(landmarks: list[Any]) -> dict[str, dict[str, float]]:
    key_map = {
        "nose": 0,
        "left_shoulder": 11,
        "right_shoulder": 12,
        "left_elbow": 13,
        "right_elbow": 14,
        "left_wrist": 15,
        "right_wrist": 16,
        "left_hip": 23,
        "right_hip": 24,
        "left_knee": 25,
        "right_knee": 26,
        "left_ankle": 27,
        "right_ankle": 28,
    }
    joints: dict[str, dict[str, float]] = {}
    for name, idx in key_map.items():
        point = _landmark_point(landmarks, idx)
        if point is None:
            continue
        joints[name] = {"x": float(point[0]), "y": float(point[1])}
    return joints


def _candidate_features(image_path: Path) -> dict[str, Any]:
    if cv2 is None or mp is None:
        raw = image_path.read_bytes() if image_path.exists() else image_path.name.encode("utf-8")
        digest = hashlib.sha256(raw).digest()
        pseudo = [(b / 255.0) for b in digest[:16]]
        return {
            "joints": {},
            "angles": {},
            "skeleton": pseudo[:8],
            "embedding": pseudo[:14],
            "contour": pseudo[:8],
            "has_pose": False,
        }

    image_bgr = cv2.imread(str(image_path))
    if image_bgr is None:
        raise RuntimeError(f"No se pudo leer la imagen {image_path}")

    image_rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
    pose = mp.solutions.pose.Pose(
        static_image_mode=True,
        model_complexity=1,
        smooth_landmarks=False,
        enable_segmentation=True,
        min_detection_confidence=0.5,
    )
    result = pose.process(image_rgb)
    segmentation_mask = getattr(result, "segmentation_mask", None)
    pose_landmarks = getattr(result, "pose_landmarks", None)

    joints = {}
    angles: dict[str, float] = {}
    if pose_landmarks and getattr(pose_landmarks, "landmark", None):
        landmarks = list(pose_landmarks.landmark)
        joints = _build_joints_from_landmarks(landmarks)
        try:
            left_arm = _angle_from_points(_landmark_point(landmarks, 11) or (0, 0), _landmark_point(landmarks, 13) or (0, 0), _landmark_point(landmarks, 15) or (0, 0))
            right_arm = _angle_from_points(_landmark_point(landmarks, 12) or (0, 0), _landmark_point(landmarks, 14) or (0, 0), _landmark_point(landmarks, 16) or (0, 0))
            left_leg = _angle_from_points(_landmark_point(landmarks, 23) or (0, 0), _landmark_point(landmarks, 25) or (0, 0), _landmark_point(landmarks, 27) or (0, 0))
            right_leg = _angle_from_points(_landmark_point(landmarks, 24) or (0, 0), _landmark_point(landmarks, 26) or (0, 0), _landmark_point(landmarks, 28) or (0, 0))
            left_torso = _angle_from_points(_landmark_point(landmarks, 23) or (0, 0), _landmark_point(landmarks, 11) or (0, 0), _landmark_point(landmarks, 13) or (0, 0))
            right_torso = _angle_from_points(_landmark_point(landmarks, 24) or (0, 0), _landmark_point(landmarks, 12) or (0, 0), _landmark_point(landmarks, 14) or (0, 0))
            neck = _angle_from_points(_landmark_point(landmarks, 11) or (0, 0), _landmark_point(landmarks, 0) or (0, 0), _landmark_point(landmarks, 12) or (0, 0))
            angles = {
                "left_arm": left_arm,
                "right_arm": right_arm,
                "left_leg": left_leg,
                "right_leg": right_leg,
                "left_torso": left_torso,
                "right_torso": right_torso,
                "neck": neck,
            }
        except Exception:
            angles = {}

    contour = _contour_vector_from_image(image_bgr, segmentation_mask)
    skeleton = _skeleton_vector_from_joints(joints)
    embedding = _summary_embedding(angles, joints, contour)

    pose.close()
    return {
        "joints": joints,
        "angles": angles,
        "skeleton": skeleton,
        "embedding": embedding,
        "contour": contour,
        "has_pose": bool(joints),
    }


def _download_bytes(url: str) -> tuple[bytes, str]:
    request = Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urlopen(request, timeout=DEFAULT_TIMEOUT) as response:
            content_type = response.headers.get_content_type().lower() if response.headers else ""
            data = response.read()
            return data, content_type
    except HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code} descargando {url}") from exc
    except URLError as exc:
        raise RuntimeError(f"Error de red descargando {url}: {exc.reason}") from exc


def _extension_from(url: str, content_type: str) -> str:
    parsed_ext = Path(url.split("?")[0]).suffix.lower()
    if parsed_ext in {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}:
        return parsed_ext
    guessed = mimetypes.guess_extension(content_type or "") or ".jpg"
    if guessed == ".jpe":
        guessed = ".jpg"
    return guessed


def _save_candidate_thumbnail(candidate: RemoteCandidate, session_dir: Path) -> tuple[Path, str, bytes]:
    if not candidate.thumbnail_url.startswith("http"):
        raise RuntimeError("Thumbnail remota inválida")
    data, content_type = _download_bytes(candidate.thumbnail_url)
    digest = sha256_bytes(data)
    ext = _extension_from(candidate.thumbnail_url, content_type)
    download_dir = session_dir / "downloads"
    download_dir.mkdir(parents=True, exist_ok=True)
    file_path = download_dir / f"{candidate.provider}_{digest[:16]}{ext}"
    file_path.write_bytes(data)
    return file_path, digest, data


def _load_analysis_cache(cache_path: Path) -> dict[str, Any] | None:
    if not cache_path.exists():
        return None
    try:
        return json.loads(cache_path.read_text(encoding="utf-8"))
    except Exception:
        return None


def _store_analysis_cache(cache_path: Path, payload: dict[str, Any]) -> None:
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _analyze_candidate(candidate: RemoteCandidate, session_dir: Path, reference: PoseProfile, hash_cache: dict[str, dict[str, Any]]) -> ScoredCandidate | None:
    try:
        thumbnail_path, digest, _bytes = _save_candidate_thumbnail(candidate, session_dir)
    except Exception as exc:
        log("PEXELS", f"No se pudo descargar miniatura: {candidate.thumbnail_url[:80]}... ({exc})")
        return None

    analysis_path = session_dir / "analysis" / f"{digest}.json"
    features = hash_cache.get(digest)
    if features is None:
        features = _load_analysis_cache(analysis_path)

    if features is None:
        try:
            log("MEDIAPIPE", f"Analizando pose en {thumbnail_path.name}")
            features = _candidate_features(thumbnail_path)
        except Exception as exc:
            log("MEDIAPIPE", f"Fallback sin pose para {thumbnail_path.name}: {exc}")
            features = {
                "joints": {},
                "angles": {},
                "skeleton": [],
                "embedding": [],
                "contour": [0.5, 0.5, 0.5, 0.5, 0.5, 0.0, 0.0, 0.0],
                "has_pose": False,
            }
        hash_cache[digest] = features
        _store_analysis_cache(analysis_path, {"hash": digest, **features})

    candidate_embedding = safe_vector(features.get("embedding", []), len(reference.embedding) or len(features.get("embedding", [])))
    reference_embedding = safe_vector(reference.embedding, len(candidate_embedding))
    cosine = cosine_similarity(candidate_embedding, reference_embedding)
    angles = angle_similarity(reference.angles, {str(k): float(v) for k, v in (features.get("angles") or {}).items() if isinstance(v, (int, float, str))})
    skeleton = vector_similarity(safe_vector(features.get("skeleton", []), len(reference.skeleton) or len(features.get("skeleton", []))), safe_vector(reference.skeleton, len(features.get("skeleton", []))))
    contour = vector_similarity(safe_vector(features.get("contour", []), len(reference.contour) or len(features.get("contour", []))), safe_vector(reference.contour, len(features.get("contour", []))))

    final_score = clamp(0.45 * cosine + 0.25 * angles + 0.20 * skeleton + 0.10 * contour)
    similarity = int(round(final_score * 100.0))
    return ScoredCandidate(
        candidate=candidate,
        thumbnail_path=thumbnail_path,
        similarity=similarity,
        cosine=cosine,
        angles=angles,
        skeleton=skeleton,
        contour=contour,
        features=features,
    )


# ---------------------------------------------------------------------------
# Search orchestration
# ---------------------------------------------------------------------------


def _dedupe_candidates(candidates: list[RemoteCandidate], limit: int) -> list[RemoteCandidate]:
    seen: set[str] = set()
    out: list[RemoteCandidate] = []
    for candidate in candidates:
        key = candidate.source_url.strip() or candidate.original_url.strip() or candidate.thumbnail_url.strip()
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(candidate)
        if len(out) >= limit:
            break
    return out


async def _google_images_candidates(query: str, limit: int) -> list[RemoteCandidate]:
    try:
        return await google_images_search(query, limit)
    except Exception as exc:
        log("PLAYWRIGHT", f"Búsqueda Google Images falló: {exc}")
        return []


def _search_candidates(query: str, limit: int, providers: list[str]) -> tuple[list[RemoteCandidate], dict[str, str], list[str]]:
    providers_used: list[str] = []
    errors: dict[str, str] = {}
    candidates: list[RemoteCandidate] = []

    if _provider_allowed("pexels", providers):
        providers_used.append("pexels")
        try:
            log("PEXELS", "Searching images")
            candidates.extend(pexels_search(query, min(max(limit * DEFAULT_POOL_MULTIPLIER, 20), 80)))
        except Exception as exc:
            errors["pexels"] = str(exc)
            log("PEXELS", f"Error: {exc}")

    if _provider_allowed("playwright", providers) and len(candidates) < limit:
        providers_used.append("playwright")
        try:
            import asyncio

            log("SEARCH", "Fallback Playwright activado")
            candidates.extend(asyncio.run(_google_images_candidates(query, min(max(limit * 2, 12), 30))))
        except Exception as exc:
            errors["playwright"] = str(exc)
            log("PLAYWRIGHT", f"Error: {exc}")

    return candidates, errors, providers_used


def build_payload(results: list[ScoredCandidate], query: str, providers_requested: list[str], providers_used: list[str], errors: dict[str, str], session_id: str, cached: bool = False) -> dict[str, Any]:
    return {
        "results": [item.to_payload() for item in results],
        "cached": cached,
        "meta": {
            "query": query,
            "providers_requested": providers_requested,
            "providers_used": providers_used,
            "errors": errors,
            "session_id": session_id,
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Motor de búsqueda visual con Pexels + MediaPipe + similarity")
    parser.add_argument("--terms", nargs="+", required=True)
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    parser.add_argument("--providers", nargs="+", default=DEFAULT_PROVIDERS)
    parser.add_argument("--session-id", type=str, default="default")
    parser.add_argument("--pose-json", type=str, default="")
    parser.add_argument("--fresh", action="store_true")
    args = parser.parse_args(argv)

    terms = [term.strip() for term in args.terms if isinstance(term, str) and term.strip()]
    if not terms:
        print(json.dumps({"error": "No terms"}, ensure_ascii=False))
        return 2

    requested_limit = max(1, min(100, int(args.limit)))
    session_id = sanitize_session_id(args.session_id)
    query = " ".join(terms)
    pose_data = None
    if args.pose_json:
        try:
            pose_data = json.loads(args.pose_json)
        except Exception:
            pose_data = None

    log("SEARCH", f"Terms generated: {terms}")
    reference = build_reference_profile(terms, pose_data if isinstance(pose_data, dict) else None)

    session_dir = ensure_clean_current_search(session_id)
    if args.fresh:
        prune_to_max_images(session_dir, MAX_CACHED_IMAGES)

    candidates, errors, providers_used = _search_candidates(query, requested_limit, args.providers)
    if not candidates:
        payload = {"error": "No results found", "meta": {"query": query, "errors": errors, "session_id": session_id}}
        print(json.dumps(payload, ensure_ascii=False))
        return 1

    candidates = _dedupe_candidates(candidates, min(max(requested_limit * DEFAULT_POOL_MULTIPLIER, requested_limit), MAX_CACHED_IMAGES))
    log("PEXELS", f"Downloading thumbnails ({len(candidates)} candidatos)")

    hash_cache: dict[str, dict[str, Any]] = {}
    scored: list[ScoredCandidate] = []
    max_workers = min(8, max(1, len(candidates)))
    with cf.ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [executor.submit(_analyze_candidate, candidate, session_dir, reference, hash_cache) for candidate in candidates]
        for future in cf.as_completed(futures):
            try:
                item = future.result()
            except Exception as exc:
                log("SIMILARITY", f"Fallo analizando candidato: {exc}")
                continue
            if item is not None:
                log("SIMILARITY", f"Calculating score -> {item.similarity}%")
                scored.append(item)

    scored.sort(key=lambda item: item.similarity, reverse=True)
    scored = scored[:requested_limit]
    prune_to_max_images(session_dir, MAX_CACHED_IMAGES)
    log("GALLERY", f"Rendering results: {len(scored)}")

    payload = build_payload(scored, query, args.providers, providers_used, errors, session_id)
    print(json.dumps(payload, ensure_ascii=False))
    return 0


if __name__ == "__main__":  # pragma: no cover - script entrypoint
    raise SystemExit(main())

