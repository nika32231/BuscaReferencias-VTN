# 🆘 TROUBLESHOOTING RÁPIDO & GUÍA DE EJECUCIÓN

## ⚡ EJECUCIÓN RAPID (5 MINUTOS)

### Paso 1: Instalar Python deps (si no los tienes)

```powershell
# En PowerShell en la carpeta del proyecto:
pip install mediapipe opencv-python playwright requests
python -m playwright install chromium
```

**Si toma mucho tiempo**: Playwright descarga Chromium (~200MB), es normal

---

### Paso 2: Compilar

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\jbr'
cd C:\Users\Usuario\IdeaProjects\BuscadorReferenciasColores\BuscaReferencias
.\mvnw.cmd clean javafx:run
```

**La app debería abrir en ~10-15 segundos**

---

### Paso 3: Test rápido búsqueda

1. Dibuja algo rápido en el canvas con cualquier color
2. Click "🔍 Analizar Pose" → verás términos en la derecha
3. Click "🚀 Lanzar Búsqueda Web" → debería cargar imágenes REALES

**Observa la consola para logs `[SEARCH]`**

---

## 🐛 ERRORES COMUNES & SOLUCIONES

### ❌ Error: "JAVA_HOME not found"

```powershell
# Solución 1: IntelliJ
$env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\jbr'

# Solución 2: Busca tu Java
Get-ChildItem 'C:\Program Files\Java' -Directory
# Usa la ruta encontrada, ej:
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
```

---

### ❌ Error: "No compiler is provided in this environment"

**Causas**: Tienes JRE en lugar de JDK

```powershell
# Verifica que JAVA_HOME apunta a JDK (tiene carpeta 'bin' con 'javac')
dir $env:JAVA_HOME\bin
```

Si solo ves `java.exe` pero NO `javac.exe` → **tienes JRE, no JDK**

**Solución**:
```powershell
# Usa JBR de IntelliJ (tiene compilador):
$env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\jbr'
```

---

### ❌ Error: "ModuleNotFoundError: No module named 'playwright'"

```powershell
# Instala faltantes:
pip install playwright

# Segunda parte (crítica):
python -m playwright install chromium

# Mismo con pip3 si pip no funciona:
pip3 install playwright
python3 -m playwright install chromium
```

---

### ❌ Error: "[SEARCH] Ningún comando Python funcionó"

**Significa**: Java no puede ejecutar Python

Soluciones en orden:

```powershell
# 1. Verifica Python está en PATH:
python --version     # Debería imprimir version

# 2. Si no:
python no se reconoce
# → Agrega C:\Python312 (o tu versión) a PATH de Windows

# 3. Si sigue sin funcionar, prueba directa:
python.exe image_search_engine.py --terms "test" --limit 3

# 4. Si eso tampoco funciona:
py -3 image_search_engine.py --terms "test" --limit 3
```

---

### ⏳ Error: Timeout o "Playwright toma mucho"

**Es NORMAL la primera vez**:
- Playwright descarga Chromium: 10-30 segundos
- Google Images es lento con JS: 15-30 segundos
- Total: hasta 60 segundos es aceptable

**Si supera 60s**: Timeout y prueba otra búsqueda

---

### ❌ Error: "No results" en galería

**Posibles causas** (en orden de probabilidad):

1. **Internet no disponible**
   - Verifica ping a google.com
   
2. **APIs bloqueadas por red corporativa**
   - Prueba: `curl https://pixabay.com/api/`
   
3. **Playwright no instalado**
   - Revisa: `python -m playwright install chromium`
   
4. **Script Python no en la ruta correcta**
   - Verifica: `image_search_engine.py` existe en raíz del proyecto
   - Verifica: ruta absoluta en PlaywrightScraper.java logs

**Debug paso a paso:**
```powershell
# Terminal 1: Mira los logs
# (la consola de IntelliJ o .\mvnw.cmd javafx:run)

# Terminal 2: Prueba el script Python directamente
python image_search_engine.py --terms "human pose" --limit 5
# Si funciona, verás JSON con "results"

# Si no funciona, verás "error"
```

---

## 📊 LOGS DE ÉXITO (QUÉ BUSCAR)

En la consola debería ver esto:

```
[STARTUP] start() begin  safeMode=false
[STARTUP] Cargando FXML...
[STARTUP] UI preparada en 543 ms
[STARTUP] stage.show() complete en 987 ms

[UI] DrawingController.initialize() end en 234 ms

# Ahora dibujas...
# Haces click en "Analizar Pose"

[UI] DrawingController.initialize() end en 234 ms

# Haces click en "Lanzar Búsqueda Web"

[SEARCH] Llamando a image_search_engine.py: [human pose reference, standing pose] con providers: [pixabay, pexels, unsplash, bing, flickr, playwright]
[SEARCH] Intentando comando: py -3 ...
[SEARCH] Éxito con comando: py -3
[SEARCH] Salida JSON recibida: 2048 bytes
[SEARCH] Se extrajeron 12 URLs de los resultados.

# Galería aparece con imágenes ✅
```

---

## 🧪 TEST RÁPIDO SIN GUI

Para verificar que Python funciona sin ejecutar toda la app:

```powershell
cd C:\Users\Usuario\IdeaProjects\BuscadorReferenciasColores\BuscaReferencias

# Test directo del script:
python image_search_engine.py --terms "reference pose" --limit 3 --providers pixabay

# Si funciona, verás:
# {"results": [...], "cached": false, "meta": {...}}

# Test con el helper:
python test_search.py

# Debería mostrar:
# ✅ Test 1: Búsqueda con Pixabay (requiere API key)
# ✅ Test 2: Búsqueda con Playwright (primera vez toma 20-30s)
```

---

## 🚀 PERFORMANCE TIPS

### Para búsquedas MÁS RÁPIDAS:

1. **Configura API keys** (20x más rápido que Playwright):
```powershell
$env:PIXABAY_API_KEY = "get_free_key_from_pixabay.com"
$env:PEXELS_API_KEY = "get_free_key_from_pexels.com"
```

2. **USA `py` en lugar de `python`** (ligeramente más rápido on Windows):
   - PlaywrightScraper.java ya lo intenta primero

3. **Caché**: Las búsquedas iguales usan caché (TTL 1 hora):
   - Busca "human pose" → 2s
   - Busca lo mismo otra vez → 0.05s ✅

---

## 📞 CONTACT & LOGS

Si algo falla, revisa estos logs:

1. **Consola de IntelliJ** → errores Java
2. **Stderr de Python** → errores de script
3. **INSTANCIA del motor**: `cache/search/` → ficheros caché

Comando para ver los últimos 100 logs:
```powershell
# En proyecto:
Get-Content -Path "cache/search/*.json" -Tail 10
```

---

## ✅ CHECKLIST RÁPIDO

- [ ] Python instalado: `python --version`
- [ ] pip works: `pip --version`
- [ ] Deps instalados: `pip list | findstr playwright`
- [ ] Playwright descargado: `python -m playwright install chromium`
- [ ] Java compilable: `javac -version`
- [ ] JAVA_HOME válido: `dir $env:JAVA_HOME\bin\javac.exe`
- [ ] Script existe: `Test-Path image_search_engine.py`

Si todos los checks son ✅, la app debería funcionar!

---

## 🎯 RESUMEN

La arquitectura es:

```
Botón "Buscar" 
    ↓
Java Thread
    ↓
Llama: python image_search_engine.py
    ↓
Python: Intenta APIs rápidas
    ↓
Si fallan: uses Playwright (lento)
    ↓
Devuelve JSON con URLs
    ↓
Java: Descarga y cachea
    ↓
Java: Muestra en galería
    ↓
Usuario: Ve imágenes reales ✅
```

Si algo falla en cualquier paso, la app lo maneja gracefully.

---

¡Prueba ahora! 🚀

Si tienes dudiosas, revisa:
1. INSTALL_AND_RUN.md → paso a paso
2. PHASE_1_SUMMARY.md → qué se cambió
3. Los logs `[SEARCH]` en la consola

