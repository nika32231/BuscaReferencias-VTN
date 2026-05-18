"""Motor de búsqueda de imágenes (online) centralizado en Python.

Objetivo:
- Proveer resultados reales y robustos sin scraping HTML frágil en Java.
- Preferir APIs oficiales (cuando se configuren keys) y usar Playwright como fallback.
- Mantener caché temporal (TTL) de resultados para no repetir búsquedas.

Contrato CLI (stdout JSON):
python image_search_engine.py --terms "term1" "term2" --limit 20 --providers pixabay pexels unsplash bing flickr playwright

Salida (enriquecida):
{
  "results": [
     {
       "thumbnail_url": "...",
       "original_url": "...",
       "title": "...",
       "provider": "pixabay",
       "width": 1920,
       "height": 1080,
       "page_url": "...",
       "author": "...",
       "license": "...",
       "extra": {...}
     }
  ],
  "cached": false,
  "meta": {"query": "...", "providers_used": ["..."], "errors": {"provider": "msg"}}
}
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import random
import time
from dataclasses import dataclass, asdict, field
from pathlib import Path
from urllib.parse import quote_plus, urlparse, parse_qs, unquote
from typing import List, Dict, Optional, Any

import requests

# Cache temporal por busqueda/sesion. Se limpia desde Java al iniciar busqueda.
CACHE_DIR = Path("cache") / "current_search" / "search_cache"
CACHE_DIR.mkdir(parents=True, exist_ok=True)
DEFAULT_TTL_SECONDS = 5 * 60

DEFAULT_TIMEOUT = 20


@dataclass
class ImageHit:
    thumbnail_url: str
    original_url: str
    title: str = ""
    provider: str = ""
    width: Optional[int] = None
    height: Optional[int] = None
    page_url: str = ""
    author: str = ""
    license: str = ""
    similarity: float = 0.0
    extra: Dict[str, Any] = field(default_factory=dict)

    def to_payload(self, search_query: str) -> Dict[str, Any]:
        payload = asdict(self)
        payload.update(
            {
                "thumbnailUrl": self.thumbnail_url,
                "imageUrl": self.original_url,
                "sourcePageUrl": self.page_url,
                "source": _source_label(self.provider),
                "searchQuery": search_query,
            }
        )
        return payload


def _cache_key(terms: List[str], providers: List[str], limit: int, session_id: str) -> str:
    raw = "|".join([";".join(terms), ",".join(sorted(providers)), str(limit), session_id])
    import hashlib

    return hashlib.md5(raw.encode("utf-8")).hexdigest()


def _cache_path(key: str) -> Path:
    return CACHE_DIR / f"{key}.json"


def load_cache(key: str, ttl_seconds: int) -> Optional[dict]:
    path = _cache_path(key)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        ts = float(data.get("_ts", 0))
        if time.time() - ts > ttl_seconds:
            return None
        return data
    except Exception:
        return None


def save_cache(key: str, payload: dict) -> None:
    payload = dict(payload)
    payload["_ts"] = time.time()
    _cache_path(key).write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


def _query_string(terms: List[str]) -> str:
    return " ".join(t.strip() for t in terms if t.strip())


def _source_label(provider: str) -> str:
    mapping = {
        "pixabay": "Pixabay",
        "pexels": "Pexels",
        "unsplash": "Unsplash",
        "bing": "Bing",
        "flickr": "Flickr",
        "playwright": "Google Images",
        "playwright_google": "Google Images",
        "pinterest": "Pinterest",
        "pinterest_playwright": "Pinterest",
    }
    if provider in mapping:
        return mapping[provider]
    return provider.replace("_", " ").title() if provider else "Online"


def _dedupe(hits: List[ImageHit], limit: int) -> List[ImageHit]:
    seen = set()
    out: List[ImageHit] = []
    for h in hits:
        key = (h.original_url or h.page_url or h.thumbnail_url or "").strip()
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(h)
        if len(out) >= limit:
            break
    return out


def _is_large_enough(hit: ImageHit, min_side: int = 220) -> bool:
    # Evita analizar resultados muy pequenos que no sirven para pose.
    if hit.width is None or hit.height is None:
        return True
    return hit.width >= min_side and hit.height >= min_side


def _extract_google_targets(href: str) -> (str, str):
    if not href:
        return "", ""
    try:
        parsed = urlparse(href)
        qs = parse_qs(parsed.query)
        image_url = unquote((qs.get("imgurl") or [""])[0])
        ref_url = unquote((qs.get("imgrefurl") or [""])[0])
        if image_url or ref_url:
            return image_url, ref_url
        if href.startswith("http"):
            return "", href
        return "", ""
    except Exception:
        return "", ""


# -------------------- Proveedores por API oficial --------------------

def pixabay_search(query: str, limit: int) -> List[ImageHit]:
    key = os.getenv("PIXABAY_API_KEY")
    if not key:
        raise RuntimeError("PIXABAY_API_KEY no configurada")

    url = "https://pixabay.com/api/"
    params = {
        "key": key,
        "q": query,
        "image_type": "photo",
        "per_page": min(limit, 200),
        "safesearch": "true",
    }
    r = requests.get(url, params=params, timeout=DEFAULT_TIMEOUT)
    r.raise_for_status()
    data = r.json()

    out: List[ImageHit] = []
    for hit in data.get("hits", [])[:limit]:
        out.append(
            ImageHit(
                thumbnail_url=hit.get("previewURL", "") or hit.get("webformatURL", ""),
                original_url=hit.get("largeImageURL", "") or hit.get("webformatURL", ""),
                title=hit.get("tags", ""),
                provider="pixabay",
                width=hit.get("imageWidth"),
                height=hit.get("imageHeight"),
                page_url=hit.get("pageURL", ""),
                author=hit.get("user", ""),
                license="Pixabay License",
                extra={"id": hit.get("id"), "type": hit.get("type"), "downloads": hit.get("downloads")},
            )
        )
    return out


def pexels_search(query: str, limit: int) -> List[ImageHit]:
    key = os.getenv("PEXELS_API_KEY")
    if not key:
        raise RuntimeError("PEXELS_API_KEY no configurada")

    url = "https://api.pexels.com/v1/search"
    headers = {"Authorization": key}
    params = {"query": query, "per_page": min(limit, 80)}
    r = requests.get(url, headers=headers, params=params, timeout=DEFAULT_TIMEOUT)
    r.raise_for_status()
    data = r.json()

    out: List[ImageHit] = []
    for photo in data.get("photos", [])[:limit]:
        src = photo.get("src", {})
        out.append(
            ImageHit(
                thumbnail_url=src.get("medium") or src.get("small") or "",
                original_url=src.get("original") or src.get("large") or "",
                title=photo.get("alt", "") or query,
                provider="pexels",
                width=photo.get("width"),
                height=photo.get("height"),
                page_url=photo.get("url", ""),
                author=photo.get("photographer", ""),
                license="Pexels License",
                extra={"id": photo.get("id"), "avg_color": photo.get("avg_color")},
            )
        )
    return out


def unsplash_search(query: str, limit: int) -> List[ImageHit]:
    key = os.getenv("UNSPLASH_ACCESS_KEY")
    if not key:
        raise RuntimeError("UNSPLASH_ACCESS_KEY no configurada")

    url = "https://api.unsplash.com/search/photos"
    headers = {"Accept-Version": "v1"}
    params = {"query": query, "per_page": min(limit, 30), "client_id": key}
    r = requests.get(url, headers=headers, params=params, timeout=DEFAULT_TIMEOUT)
    r.raise_for_status()
    data = r.json()

    out: List[ImageHit] = []
    for item in data.get("results", [])[:limit]:
        urls = item.get("urls", {})
        user = item.get("user", {})
        out.append(
            ImageHit(
                thumbnail_url=urls.get("small") or urls.get("thumb") or "",
                original_url=urls.get("full") or urls.get("regular") or "",
                title=item.get("alt_description") or item.get("description") or query,
                provider="unsplash",
                width=item.get("width"),
                height=item.get("height"),
                page_url=item.get("links", {}).get("html", ""),
                author=user.get("name", ""),
                license="Unsplash License",
                extra={"id": item.get("id"), "color": item.get("color"), "likes": item.get("likes")},
            )
        )
    return out


def flickr_search(query: str, limit: int) -> List[ImageHit]:
    key = os.getenv("FLICKR_API_KEY")
    if not key:
        raise RuntimeError("FLICKR_API_KEY no configurada")

    # Flickr REST: buscamos fotos y pedimos extras (urls y dimensiones)
    url = "https://www.flickr.com/services/rest/"
    params = {
        "method": "flickr.photos.search",
        "api_key": key,
        "text": query,
        "per_page": min(limit, 100),
        "format": "json",
        "nojsoncallback": 1,
        "content_type": 1,
        "safe_search": 1,
        "extras": "url_q,url_m,url_l,o_dims,owner_name",
    }
    r = requests.get(url, params=params, timeout=DEFAULT_TIMEOUT)
    r.raise_for_status()
    data = r.json()

    out: List[ImageHit] = []
    for p in data.get("photos", {}).get("photo", [])[:limit]:
        thumb = p.get("url_q") or p.get("url_m") or ""
        orig = p.get("url_l") or p.get("url_m") or ""
        out.append(
            ImageHit(
                thumbnail_url=thumb,
                original_url=orig,
                title=p.get("title", "") or query,
                provider="flickr",
                width=int(p["o_width"]) if p.get("o_width") else None,
                height=int(p["o_height"]) if p.get("o_height") else None,
                page_url=f"https://www.flickr.com/photos/{p.get('owner')}/{p.get('id')}",
                author=p.get("ownername", ""),
                license="Flickr (depende de foto)",
                extra={"id": p.get("id"), "owner": p.get("owner")},
            )
        )
    return out


def bing_search(query: str, limit: int) -> List[ImageHit]:
    # Opción 1: Azure Bing Search v7
    key = os.getenv("BING_SEARCH_API_KEY")
    endpoint = os.getenv("BING_SEARCH_ENDPOINT", "https://api.bing.microsoft.com/v7.0/images/search")
    if not key:
        raise RuntimeError("BING_SEARCH_API_KEY no configurada")

    headers = {"Ocp-Apim-Subscription-Key": key}
    params = {
        "q": query,
        "count": min(limit, 150),
        "safeSearch": "Moderate",
        "imageType": "Photo",
    }
    r = requests.get(endpoint, headers=headers, params=params, timeout=DEFAULT_TIMEOUT)
    r.raise_for_status()
    data = r.json()

    out: List[ImageHit] = []
    for item in data.get("value", [])[:limit]:
        thumb = item.get("thumbnailUrl", "")
        orig = item.get("contentUrl", "")
        out.append(
            ImageHit(
                thumbnail_url=thumb,
                original_url=orig,
                title=item.get("name", "") or query,
                provider="bing",
                width=item.get("width"),
                height=item.get("height"),
                page_url=item.get("hostPageUrl", ""),
                author=item.get("hostPageDisplayUrl", ""),
                license="(depende de origen)",
                extra={"contentSize": item.get("contentSize"), "encodingFormat": item.get("encodingFormat")},
            )
        )
    return out


# -------------------- Fallback: Browser automation --------------------

async def playwright_google_images(terms: List[str], limit: int) -> List[ImageHit]:
    """Fallback robusto: usa Playwright para renderizar JS y extraer thumbnails reales."""

    try:
        from playwright.async_api import async_playwright
    except ImportError:
        raise RuntimeError("Playwright no instalado con 'pip install playwright' y 'playwright install chromium'")

    query = _query_string(terms)
    url = f"https://www.google.com/search?q={quote_plus(query)}&tbm=isch"

    try:
        async with async_playwright() as p:
            browser = await p.chromium.launch(headless=True, timeout=30000)
            viewport = random.choice([
                {"width": 1366, "height": 768},
                {"width": 1440, "height": 900},
                {"width": 1536, "height": 864},
            ])
            context = await browser.new_context(
                user_agent=(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/124.0.0.0 Safari/537.36"
                ),
                viewport=viewport,
                locale="es-ES",
                timezone_id="Europe/Madrid",
            )
            page = await context.new_page()

            request_cap = 120
            requests_seen = {"n": 0}

            async def route_handler(route):
                req = route.request
                requests_seen["n"] += 1
                if requests_seen["n"] > request_cap:
                    await route.abort()
                    return
                if req.resource_type in {"font", "media", "stylesheet"}:
                    await route.abort()
                    return
                await route.continue_()

            await page.route("**/*", route_handler)
            
            try:
                await page.goto(url, wait_until="domcontentloaded", timeout=60000)
            except Exception as e:
                raise RuntimeError(f"No se pudo cargar la página de Google Images: {str(e)}")

            # Consentimiento (best-effort)
            for selector in [
                "button:has-text('Aceptar todo')",
                "button:has-text('Aceptar')",
                "button:has-text('I agree')",
                "button:has-text('Accept all')",
            ]:
                try:
                    loc = page.locator(selector)
                    if await loc.count() > 0:
                        await loc.first.click(timeout=1500)
                        break
                except Exception:
                    pass

            # Scroll progresivo con pausas pequenas aleatorias para comportamiento humano.
            for _ in range(5):
                try:
                    await page.mouse.wheel(0, random.randint(700, 1200))
                    await page.wait_for_timeout(random.randint(500, 1200))
                except Exception:
                    pass

            imgs = page.locator("img.rg_i")
            try:
                await imgs.first.wait_for(timeout=15000)
            except Exception:
                pass

            rows = await imgs.evaluate_all(
                """
                els => els.map(e => {
                    const thumb = e.currentSrc || e.src || '';
                    const anchor = e.closest('a');
                    const href = anchor ? (anchor.href || '') : '';
                    return { thumb, href };
                }).filter(x => !!x.thumb && !x.thumb.startsWith('data:image'))
                """
            )

            hits: List[ImageHit] = []
            for row in rows:
                thumb = row.get("thumb", "")
                image_url, page_url = _extract_google_targets(row.get("href", ""))
                if not thumb:
                    continue
                hits.append(
                    ImageHit(
                        thumbnail_url=thumb,
                        original_url=image_url or thumb,
                        title=query,
                        provider="playwright_google",
                        page_url=page_url or row.get("href", "") or url,
                        extra={"kind": "url"},
                    )
                )
                if len(hits) >= limit:
                    break

            await context.close()
            await browser.close()
            return hits
    except Exception as e:
        raise RuntimeError(f"Error en Playwright: {str(e)}")


async def playwright_pinterest(terms: List[str], limit: int) -> List[ImageHit]:
    """Fallback Playwright específico para Pinterest."""

    try:
        from playwright.async_api import async_playwright
    except ImportError:
        raise RuntimeError("Playwright no instalado con 'pip install playwright' y 'playwright install chromium'")

    query = _query_string(terms)
    url = f"https://www.pinterest.com/search/pins/?q={quote_plus(query)}"

    try:
        async with async_playwright() as p:
            browser = await p.chromium.launch(headless=True, timeout=30000)
            viewport = random.choice([
                {"width": 1366, "height": 768},
                {"width": 1440, "height": 900},
                {"width": 1536, "height": 864},
            ])
            context = await browser.new_context(
                user_agent=(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/124.0.0.0 Safari/537.36"
                ),
                viewport=viewport,
                locale="es-ES",
                timezone_id="Europe/Madrid",
            )
            page = await context.new_page()

            request_cap = 130
            requests_seen = {"n": 0}

            async def route_handler(route):
                req = route.request
                requests_seen["n"] += 1
                if requests_seen["n"] > request_cap:
                    await route.abort()
                    return
                if req.resource_type in {"font", "media", "stylesheet"}:
                    await route.abort()
                    return
                await route.continue_()

            await page.route("**/*", route_handler)

            try:
                await page.goto(url, wait_until="domcontentloaded", timeout=60000)
            except Exception as e:
                raise RuntimeError(f"No se pudo cargar Pinterest: {str(e)}")

            for selector in [
                "button:has-text('Aceptar todo')",
                "button:has-text('Aceptar')",
                "button:has-text('I agree')",
                "button:has-text('Accept all')",
            ]:
                try:
                    loc = page.locator(selector)
                    if await loc.count() > 0:
                        await loc.first.click(timeout=1500)
                        break
                except Exception:
                    pass

            for _ in range(5):
                try:
                    await page.mouse.wheel(0, random.randint(700, 1400))
                    await page.wait_for_timeout(random.randint(700, 1400))
                except Exception:
                    pass

            imgs = page.locator("img")
            try:
                await imgs.first.wait_for(timeout=15000)
            except Exception:
                pass

            cards = await imgs.evaluate_all(
                """
                els => els.map(e => {
                    const raw = e.currentSrc || e.src || e.getAttribute('data-src') || '';
                    const s = raw.replace('/236x/', '/736x/').replace('/474x/', '/736x/');
                    const anchor = e.closest('a[href*="/pin/"]') || e.closest('a');
                    const href = anchor ? (anchor.href || '') : '';
                    return { src: s, href };
                }).filter(x => !!x.src && x.src.includes('pinimg.com'))
                """
            )

            if not cards:
                srcs = await page.locator("source").evaluate_all(
                    "els => els.map(e => e.srcset || '').filter(Boolean).flatMap(s => s.split(',').map(x => x.trim().split(' ')[0])).filter(Boolean)"
                )
                cards = [{"src": s.replace('/236x/', '/736x/').replace('/474x/', '/736x/'), "href": url} for s in srcs if "pinimg.com" in s]

            hits: List[ImageHit] = []
            for card in cards:
                s = card.get("src", "")
                href = card.get("href", "")
                if s and s.startswith("http"):
                    hits.append(
                        ImageHit(
                            thumbnail_url=s,
                            original_url=s,
                            title=query,
                            provider="pinterest_playwright",
                            page_url=href or url,
                            extra={"kind": "url"},
                        )
                    )
                if len(hits) >= limit:
                    break

            await context.close()
            await browser.close()
            return hits
    except Exception as e:
        raise RuntimeError(f"Error en Pinterest Playwright: {str(e)}")


async def run_search(terms: List[str], limit: int, providers: List[str]) -> (List[ImageHit], Dict[str, str], List[str]):
    query = _query_string(terms)
    errors: Dict[str, str] = {}
    used: List[str] = []

    hits: List[ImageHit] = []

    providers_set = set(p.lower() for p in providers)

    def try_provider(name: str, fn):
        nonlocal hits
        if name not in providers_set:
            return
        used.append(name)
        try:
            provider_hits = fn(query, limit)
            provider_hits = [h for h in provider_hits if _is_large_enough(h)]
            hits.extend(provider_hits)
        except Exception as e:
            errors[name] = str(e)
            import sys
            print(f"[WARN] Error en proveedor '{name}': {str(e)}", file=sys.stderr)

    # APIs oficiales primero: mas estables para online dinamico.
    try_provider("bing", lambda q, l: bing_search(q, l))
    if len(hits) >= limit:
        hits = _dedupe(hits, limit)
        return hits, errors, used
        
    try_provider("pixabay", lambda q, l: pixabay_search(q, l))
    if len(hits) >= limit:
        hits = _dedupe(hits, limit)
        return hits, errors, used
        
    try_provider("pexels", lambda q, l: pexels_search(q, l))
    if len(hits) >= limit:
        hits = _dedupe(hits, limit)
        return hits, errors, used
        
    try_provider("unsplash", lambda q, l: unsplash_search(q, l))
    try_provider("flickr", lambda q, l: flickr_search(q, l))

    # Playwright solo de apoyo para fuentes dinamicas.
    if "playwright" in providers_set and len(hits) < limit:
        used.append("playwright")
        try:
            hits.extend(await playwright_google_images(terms, limit - len(hits)))
        except Exception as e:
            errors["playwright"] = str(e)
            import sys
            print(f"[WARN] Error en Playwright: {str(e)}", file=sys.stderr)

    if "pinterest" in providers_set and len(hits) < limit:
        used.append("pinterest")
        try:
            hits.extend(await playwright_pinterest(terms, limit - len(hits)))
        except Exception as e:
            errors["pinterest"] = str(e)
            import sys
            print(f"[WARN] Error en Pinterest Playwright: {str(e)}", file=sys.stderr)

    hits = _dedupe(hits, limit)
    return hits, errors, used


def main() -> int:
    import sys
    
    parser = argparse.ArgumentParser()
    parser.add_argument("--terms", nargs="+", required=True)
    parser.add_argument("--limit", type=int, default=20)
    parser.add_argument(
        "--providers",
        nargs="+",
        default=["pixabay", "pexels", "unsplash", "bing", "flickr", "playwright"],
    )
    parser.add_argument("--ttl", type=int, default=DEFAULT_TTL_SECONDS)
    parser.add_argument("--session-id", type=str, default="default")
    parser.add_argument("--fresh", action="store_true")
    args = parser.parse_args()

    terms: List[str] = [t.strip() for t in args.terms if t.strip()]
    if not terms:
        print(json.dumps({"error": "No terms"}, ensure_ascii=False))
        return 2

    requested_limit = max(1, min(100, int(args.limit)))
    key = _cache_key(terms, args.providers, requested_limit, args.session_id)
    cached = None if args.fresh else load_cache(key, args.ttl)
    if cached and "results" in cached:
        print(json.dumps({"results": cached["results"], "cached": True, "meta": cached.get("meta", {})}, ensure_ascii=False))
        return 0

    query = _query_string(terms)

    try:
        hits, errors, used = asyncio.run(run_search(terms, requested_limit, args.providers))
        
        # Incluso con errores parciales, si tenemos resultados, es éxito
        if hits:
            payload = {
                "results": [h.to_payload(query) for h in hits],
                "cached": False,
                "meta": {
                    "query": query,
                    "providers_requested": args.providers,
                    "providers_used": used,
                    "errors": errors,
                    "session_id": args.session_id,
                },
            }
            save_cache(key, payload)
            print(json.dumps(payload, ensure_ascii=False))
            sys.stderr.flush()
            return 0
        else:
            # Sin resultados pero sin excepción catastrófica
            if errors:
                print(json.dumps({"error": "All providers failed: " + str(errors), "meta": {"query": query}}, ensure_ascii=False))
            else:
                print(json.dumps({"error": "No results found", "meta": {"query": query}}, ensure_ascii=False))
            sys.stderr.flush()
            return 1
    except Exception as e:
        print(json.dumps({"error": str(e), "meta": {"query": query, "type": type(e).__name__}}, ensure_ascii=False))
        sys.stderr.flush()
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

