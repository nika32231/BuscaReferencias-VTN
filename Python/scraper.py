import sys
import json
import asyncio
from playwright.async_api import async_playwright

async def search_pinterest(query, limit=10):
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        context = await browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        )
        page = await context.new_page()
        
        url = f"https://www.pinterest.com/search/pins/?q={query}"
        await page.goto(url)
        
        try:
            await page.wait_for_selector("img", timeout=10000)
        except:
            pass

        # Scroll
        for _ in range(2):
            await page.mouse.wheel(0, 1000)
            await asyncio.sleep(1)

        imgs = await page.query_selector_all("img")
        results = []
        for img in imgs:
            src = await img.get_attribute("src")
            if src and "i.pinimg.com" in src:
                results.append(src)
            if len(results) >= limit:
                break
        
        await browser.close()
        return results

async def search_google(query, limit=10):
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        context = await browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        )
        page = await context.new_page()
        
        url = f"https://www.google.com/search?q={query}&tbm=isch"
        await page.goto(url)
        
        # Accept cookies
        try:
            # Seleccionar botones comunes de aceptación de cookies en Google
            buttons = await page.query_selector_all("button")
            for btn in buttons:
                text = await btn.inner_text()
                if "Acept" in text or "Agree" in text:
                    await btn.click()
                    break
        except:
            pass

        try:
            await page.wait_for_selector("img", timeout=10000)
        except:
            pass

        imgs = await page.query_selector_all("img")
        results = []
        for img in imgs:
            src = await img.get_attribute("src")
            if src and (src.startswith("http") or src.startswith("data:image")):
                results.append(src)
            if len(results) >= limit:
                break
        
        await browser.close()
        return results

async def main():
    if len(sys.argv) < 3:
        print(json.dumps({"error": "Usage: scraper.py <source> <query>"}))
        return

    source = sys.argv[1]
    query = sys.argv[2]
    
    try:
        if source == "pinterest":
            urls = await search_pinterest(query)
        elif source == "google":
            urls = await search_google(query)
        else:
            urls = []
            
        print(json.dumps({"urls": urls}))
    except Exception as e:
        print(json.dumps({"error": str(e)}))

if __name__ == "__main__":
    asyncio.run(main())
