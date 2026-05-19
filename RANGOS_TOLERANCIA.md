# 🎯 BÚSQUEDA CON RANGOS DE TOLERANCIA (APROXIMADAS)

## Información de los Cambios

Se ha ajustado el sistema de búsqueda de referencias para permitir **poses similares pero NO exactas**, con márgenes de tolerancia alrededor de cada parte del cuerpo.

---

## 🔄 Rangos de Tolerancia Aplicados

### 1. **Distancia de Posición de Partes del Cuerpo**
**Archivo**: `MediaPipeService.java` - `calculateSkeletonSimilarity()` (línea ~449)

**Cambio**:
```java
// Antes: permitía 1.5 unidades de desviación
double sim = Math.max(0.0, 1.0 - (dist / 1.5));

// Ahora: permite 2.5 unidades de desviación (67% MÁS tolerancia)
double sim = Math.max(0.0, 1.0 - (dist / 2.5));
```

**Impacto**: 
- Cabeza, brazos, manos, piernas y pies pueden estar hasta 67% más lejos de su posición exacta
- Sigue buscando poses similares en lugar de exactas
- Rango de variación: ±25% mayor

### 2. **Ángulos de Brazos**
**Archivo**: `MediaPipeService.java` - `calculateAngleBasedSimilarity()` (línea ~536, 542)

**Cambio**:
```java
// Antes: permitía diferencias hasta 180 grados (0.5 × similitud máxima)
bestArmScore = Math.max(bestArmScore, 1.0 - (diff / 180.0));

// Ahora: permite diferencias hasta 360 grados (doble tolerancia)
bestArmScore = Math.max(bestArmScore, 1.0 - (diff / 360.0));
```

**Impacto**:
- Los brazos pueden estar en ángulos significativamente diferentes
- Por ejemplo: brazo estirado vs. brazo doblado pueden coincidir
- Score aún sube si el ángulo general es similar

### 3. **Ángulos de Piernas**
**Archivo**: `MediaPipeService.java` - `calculateAngleBasedSimilarity()` (línea ~569, 575)

**Cambio**:
```java
// Antes: permitía diferencias hasta 180 grados
bestLegScore = Math.max(bestLegScore, 1.0 - (diff / 180.0));

// Ahora: permite diferencias hasta 360 grados
bestLegScore = Math.max(bestLegScore, 1.0 - (diff / 360.0));
```

**Impacto**:
- Las piernas pueden tener ángulos muy diferentes
- Flexibilidad para diversos ángulos de rodillas y tobillos
- Mayor compatibilidad con variaciones poses similares

### 4. **Contorno General**
**Archivo**: `MediaPipeService.java` - `calculateContourSimilarity()` (línea ~476)

**Estado**: Ya era bastante tolerante, se mantiene sin cambios

---

## 📊 Cómo Funciona Ahora

### Ejemplo 1: Pose de Pie Frontal
```
Dibujo:  Persona de pie, brazos a los lados
Busca:   - Personas de pie (tolerancia: altura±25%)
         - Brazos a los lados (tolerancia: ángulo±180°)
         - Piernas estiradas (tolerancia: ángulo±180°)
         
Resultados:
  ✅ Posiciones muy similares
  ✅ Angles ligeramente diferentes
  ✅ Proporciones corporales parecidas
  ❌ Persona acostada (demasiada diferencia)
```

### Ejemplo 2: Pose Sentado
```
Dibujo:  Persona sentada, brazos levantados
Busca:   - Personas sentadas (tolerancia: altura±25%)
         - Brazos levantados (tolerancia: ángulo±180°)
         - Rodillas flexionadas (tolerancia: ángulo±180°)
         
Resultados:
  ✅ Sentado con brazos levantados (exacto)
  ✅ Sentado con brazos parcialmente levantados
  ✅ Posición sentada similar
  ❌ Persona de pie (estructura diferente)
```

---

## 🎯 Rango de Aceptación

### Niveles de Similitud
```
Score 0.90 - 1.0: Postura prácticamente idéntica
Score 0.70 - 0.90: Postura muy similar (rango aceptable)
Score 0.50 - 0.70: Postura relativamente similar (aceptable)
Score 0.30 - 0.50: Postura genéricamente similar (marginal)
Score 0.00 - 0.30: Postura diferente (rechazable)
```

---

## ⚙️ Configurabilidad

Si necesitas **más o menos tolerancia**, puedes ajustar:

### Para MÁS Tolerancia (más resultados)
```java
// Aumenta los divisores en:
double sim = Math.max(0.0, 1.0 - (dist / 3.0));  // era 2.5

bestArmScore = Math.max(bestArmScore, 1.0 - (diff / 450.0));  // era 360.0
```

### Para MENOS Tolerancia (resultados más exactos)
```java
// Disminuye los divisores en:
double sim = Math.max(0.0, 1.0 - (dist / 2.0));  // era 2.5

bestArmScore = Math.max(bestArmScore, 1.0 - (diff / 270.0));  // era 360.0
```

---

## 📋 Resumen de Cambios

| Aspecto | Antes | Ahora | Cambio |
|---------|-------|-------|--------|
| Distancia posición | 1.5 unidades | 2.5 unidades | +67% |
| Ángulo brazos | 180° máx | 360° máx | +100% |
| Ángulo piernas | 180° máx | 360° máx | +100% |
| Contorno | Igual | Igual | Sin cambios |

---

## 🧪 Cómo Probar

### Test 1: Misma Pose - Brazos Diferentes
1. Dibuja persona con brazos a los lados
2. Busca referencias
3. Resultado: Personas con brazos en diferentes ángulos (±180°)

### Test 2: Diferentes Ángulos de Piernas
1. Dibuja persona sentada
2. Busca referencias
3. Resultado: Personas sentadas con rodillas en diferentes ángulos

### Test 3: Proporciones Similares
1. Dibuja persona de estatura normal
2. Busca referencias
3. Resultado: Personas con proporciones corporales parecidas

---

## 💡 Beneficios

✅ **Más Resultados**: Encuentra más poses similares  
✅ **Flexibilidad**: Acepta variaciones naturales del cuerpo  
✅ **Realismo**: Las poses no tienen que ser idénticas  
✅ **Utilidad**: Mejor base de referencias para artistas  
✅ **Naturalidad**: Reconoce que cada persona es diferente  

---

## ⚠️ Consideraciones

- **Score Menor**: Resultados tendrán scores un poco menores (más tolerancia)
- **Más Opciones**: A cambio, habrá múltiples opciones similares
- **Menos Exactitud**: No busca poses exactas, sino similares
- **Mejor Balance**: Equilibrio entre variedad y similitud

---

## 🔧 Ubicación de Cambios en Código

```java
✅ MediaPipeService.java - línea ~449
   └─ Distancia de partes del cuerpo

✅ MediaPipeService.java - líneas ~536, 542
   └─ Ángulos de brazos

✅ MediaPipeService.java - líneas ~569, 575
   └─ Ángulos de piernas
```

---

**Resultado**: 🎉 Búsqueda de poses **aproximadas** pero **similares**

Las fotos se buscan localmente en `cache/thumbnails` y se comparan permitiendo variaciones naturales en:
- Posición de partes del cuerpo (±25%)
- Ángulos de brazos (±180°)
- Ángulos de piernas (±180°)
- Proporciones corporales (flexible)


