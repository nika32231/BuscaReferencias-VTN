#!/usr/bin/env python3
"""
Script de prueba rápida para verificar que image_search_engine.py funciona.
Uso: python test_search.py
"""

import subprocess
import json
import sys
from pathlib import Path

def test_image_search():
    """Prueba el motor de búsqueda en Python."""
    
    script_path = Path(__file__).parent / "image_search_engine.py"
    
    if not script_path.exists():
        print(f"❌ Script no encontrado: {script_path}")
        return False
    
    print(f"🎯 Testando: {script_path}")
    print("=" * 60)
    
    # Test 1: Sin providers (solo fallback local)
    print("\n✅ Test 1: Búsqueda con Pixabay (requiere API key)")
    cmd = [
        sys.executable, 
        str(script_path),
        "--terms", "human reference pose",
        "--limit", "3",
        "--providers", "pixabay"
    ]
    
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        print(f"Exit code: {result.returncode}")
        
        if result.returncode == 0:
            data = json.loads(result.stdout)
            if "results" in data:
                print(f"✅ Resultados encontrados: {len(data['results'])} imágenes")
                if data['results']:
                    print(f"   Primera URL: {data['results'][0].get('thumbnail_url')[:60]}...")
            else:
                print(f"⚠️  Sin resultados pero sin error: {data.get('error', 'unknown')}")
        else:
            print(f"❌ Error en Python: {result.stderr[:200]}")
            
    except subprocess.TimeoutExpired:
        print("❌ Timeout (timeout>30s)")
    except json.JSONDecodeError as e:
        print(f"❌ JSON inválido: {e}")
        print(f"stdout: {result.stdout[:200]}")
    except Exception as e:
        print(f"❌ Error: {e}")
        return False
    
    # Test 2: Playwright (lento pero funciona sin keys)
    print("\n✅ Test 2: Búsqueda con Playwright (primera vez toma 20-30s)")
    cmd = [
        sys.executable,
        str(script_path),
        "--terms", "pose reference",
        "--limit", "3",
        "--providers", "playwright"
    ]
    
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
        print(f"Exit code: {result.returncode}")
        
        if result.returncode == 0:
            data = json.loads(result.stdout)
            if "results" in data:
                print(f"✅ Resultados Playwright: {len(data['results'])} imágenes")
                if data['results']:
                    first_url = data['results'][0].get('thumbnail_url', '')
                    print(f"   Provider: {data['results'][0].get('provider')}")
                    print(f"   URL: {first_url[:60]}...")
        else:
            print(f"⚠️  Playwright falló (pos normal sin instalación completa)")
            if "Playwright not installed" in result.stderr or "playwright" in result.stderr.lower():
                print("   Instala: pip install playwright && playwright install chromium")
                
    except subprocess.TimeoutExpired:
        print("⏱️  Timeout en Playwright (>90s)")
    except json.JSONDecodeError:
        print(f"❌ JSON inválido en Playwright")
    except Exception as e:
        print(f"⚠️  Error Playwright: {e}")
    
    print("\n" + "=" * 60)
    print("✅ Tests completados. Revisa los resultados arriba.")
    return True

if __name__ == "__main__":
    success = test_image_search()
    sys.exit(0 if success else 1)

