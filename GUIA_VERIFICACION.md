# Guía de Verificación - Conversión a Referencias 100% Locales

## ✅ Todos los cambios han sido implementados

Este documento te ayudará a verificar que todos los cambios se hayan aplicado correctamente.

---

## 🔍 Verificación de Cambios

### 1. MediaPipeService.java - ✅ VERIFICADO

#### Cambio 1: Normalizar rutas locales en getLocalThumbnailPath
**Ubicación**: Línea ~100  
**Debe contener**:
```java
if (!imageUrl.startsWith("http")) {
    try {
        if (imageUrl.startsWith("file:")) {
            return Paths.get(new java.net.URI(imageUrl))
                    .toAbsolutePath()
                    .normalize()
                    .toUri()
                    .toString();
        }
        return Paths.get(imageUrl)
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();
    } catch (Exception e) {
        return imageUrl;
    }
}
```

#### Cambio 2: Normalizar y procesar rutas en analyzeImage
**Ubicación**: Línea ~234-280  
**Debe contener**:
- Normalización de rutas `file:` usando `new java.net.URI()`
- Normalización de rutas locales usando `Paths.get().toAbsolutePath().normalize()`
- Verificación de existencia: `if (!Files.exists(imagePath)) return new PoseData();`

#### Cambio 3: Mejorar precisión penalizando poses incompletas
**Ubicación**: Línea ~453-457  
**Debe contener**:
```java
if (counted == 0) return 0.0;
double similarity = total / counted;
// Penalizar poses incompletas
similarity *= Math.min(1.0, counted / 15.0);
return similarity;
```

### 2. SearchTermGenerator.java - ✅ VERIFICADO

**Ubicación**: Línea 57  
**Antes**:
```java
terms.add(base + " figure reference pexels");
```

**Después**:
```java
terms.add(base + " figure reference");
```

### 3. PexelsService.java - ✅ VERIFICADO

**Ubicación**: Línea ~54-57  
**Debe contener**:
```java
public static List<ImageResult> searchImages(List<String> terms, int perPage, String orientation, String size) {
    logger.info("[PEXELS] Desactivado. Solo se usarán imágenes locales.");
    return List.of();
}
```

✅ **Verificación**: El método debe tener 3 líneas solamente. Todo el código de descargas HTTP ha sido removido.

### 4. SearchService.java - ✅ VERIFICADO

#### Cambio 1: ANALYSIS_LIMIT
**Ubicación**: Línea 30  
**Antes**: `private static final int ANALYSIS_LIMIT = 24;`  
**Después**: `private static final int ANALYSIS_LIMIT = 200;`

#### Cambio 2: Analizar TODAS las imágenes
**Ubicación**: Línea 134  
**Antes**: `for (Path path : files.stream().limit(Math.max(1, limit)).toList())`  
**Después**: `for (Path path : files)`

#### Cambio 3: Validar poses antes de similitud
**Ubicación**: Línea 68-82  
**Debe contener**:
```java
try {
    imagePose = MediaPipeService.analyzeImage(analysisSource);
} catch (Exception e) {
    logger.warn("[SEARCH] Archivo inválido...");
    score = -1.0;
    return buildDisplayResult(...);
}

if (imagePose != null && !imagePose.getAllJoints().isEmpty()) {
    score = MediaPipeService.calculateSimilarity(drawingPose, imagePose);
} else {
    score = -1.0;
}
```

#### Cambio 4: Removeif para filtrar inválidos
**Ubicación**: Línea 106  
**Debe contener**:
```java
results.removeIf(r -> r.getScore() < 0);
```

---

## 🧪 Pruebas Recomendadas

### Paso 1: Compilar el proyecto
```bash
cd C:\Users\Usuario\IdeaProjects\BuscadorReferenciasColores\BuscaReferencias
.\mvnw clean compile
```

✅ **Esperado**: Compilación exitosa sin errores

### Paso 2: Verificar que cache/thumbnails exista
```bash
ls cache/thumbnails/
```

✅ **Esperado**: Múltiples archivos .jpg, .png, etc.

### Paso 3: Ejecutar búsqueda por dibujo
1. Abre la aplicación
2. Dibuja una pose simple
3. Presiona "Buscar referencias"

✅ **Esperado**:
- La búsqueda comienza sin conectar a internet
- Se analizan múltiples imágenes de cache/thumbnails
- Los resultados muestran poses similares al dibujo
- Los logs muestran `[MEDIAPIPE] Analizando:` para cada imagen
- NO aparece `[PEXELS]` en los logs (o muestra "Desactivado")

### Paso 4: Revisar logs
Busca en los logs:
- ✅ `[MEDIAPIPE] Analizando: file://...` - Rutas locales normalizadas
- ✅ `[SIMILARITY] Score calculated 0.xxxx for` - Similitudes calculadas
- ✅ `results.removeIf` - Resultados inválidos filtrados
- ✅ `[PEXELS] Desactivado` - Confirmación que Pexels no activa
- ❌ NO debe haber errores de conexión HTTP
- ❌ NO debe haber referencias a API keys de Pexels

### Paso 5: Verificar ranking
1. Los resultados deben estar ordenados por score (mayor a menor)
2. Poses con score = -1.0 NO deben aparecer
3. Poses vacías NO deben aparecer

---

## 📋 Checklist Final

- [ ] MediaPipeService.java compilado sin errores
- [ ] SearchService.java compilado sin errores  
- [ ] PexelsService.java compilado sin errores
- [ ] SearchTermGenerator.java compilado sin errores
- [ ] ANALYSIS_LIMIT = 200
- [ ] `for (Path path : files)` sin limit
- [ ] `results.removeIf(r -> r.getScore() < 0)` presente
- [ ] Try-catch alrededor de analyzeImage
- [ ] PexelsService retorna `List.of()` inmediatamente
- [ ] Búsqueda carga imágenes de cache/thumbnails
- [ ] Búsqueda NO hace llamadas a internet
- [ ] Logs muestran `[MEDIAPIPE]` y `[SEARCH]`
- [ ] Logs NO muestran errores de Pexels
- [ ] Resultados están bien rankeados
- [ ] Archivos corruptos son ignorados

---

## 🚨 Si hay Errores

### Compilación fallida
**Causa probable**: Faltan imports  
**Solución**:
1. Verifica que los archivos tengan los imports necesarios:
   - `import java.nio.file.Paths;`
   - `import java.nio.file.Files;`
   - `import java.nio.file.Path;`
   - `import java.net.URI;`

### Búsqueda sin resultados
**Causa probable**: cache/thumbnails vacío o no existe  
**Solución**:
1. Crea la carpeta si no existe
2. Añade imágenes en formatos: .jpg, .png, .webp, etc.
3. Verifica permisos de lectura

### Resultados de mala calidad
**Causa probable**: Poses incompletas siendo no penalizadas  
**Solución**:
1. Verifica que el cambio de penalización esté aplicado (línea ~456)
2. Aumenta el valor de penalización si es necesario

### Errores de ruta file:
**Causa probable**: URI no está siendo normalizada correctamente  
**Solución**:
1. Verifica el bloque de normalización en getLocalThumbnailPath
2. Añade logs de debug

---

## 📊 Métricas Esperadas

**Antes**:
- 24 imágenes analizadas máximo
- Dependencia en internet
- Poses inválidas en resultados
- Errores con rutas locales

**Después**:
- 200+ imágenes analizadas
- 100% local
- Poses inválidas filtradas
- Rutas normalizadas correctamente

---

## 💾 Archivos Modificados

1. ✅ `src/main/java/org/refcolor/buscareferencias/service/MediaPipeService.java`
2. ✅ `src/main/java/org/refcolor/buscareferencias/service/SearchService.java`
3. ✅ `src/main/java/org/refcolor/buscareferencias/service/PexelsService.java`
4. ✅ `src/main/java/org/refcolor/buscareferencias/utils/SearchTermGenerator.java`

---

## 📞 Soporte

Si encuentras problemas:
1. Verifica el archivo CAMBIOS_REALIZADOS.md para detalles completos
2. Revisa los logs en tiempo real mientras pruebas
3. Confirma que cache/thumbnails tiene imágenes válidas
4. Verifica que Java está correctamente configurado

---

**Versión**: 1.0  
**Fecha**: Mayo 2026  
**Estado**: ✅ COMPLETADO

