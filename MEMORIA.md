# Memoria del Proyecto: Buscador de Referencias Artísticas por Colores

**Autora:** Verónica Tello Nozal  
**Ciclo:** Desarrollo de Aplicaciones Multiplataforma (DAM)  
**Proyecto:** Aplicación para buscar poses con un stickman de colores

## 1. De qué trata el proyecto (Objetivo)
Este proyecto es una aplicación de escritorio hecha en Java que ayuda a artistas y estudiantes a encontrar fotos de poses rápidamente. En vez de escribir mucho en Google, el usuario dibuja un "monigote" (stickman) en un lienzo. Cada parte del cuerpo tiene un color fijo (por ejemplo, rojo para la cabeza, azul para el torso).

La idea no es que una IA invente la imagen, sino que el programa busque fotos reales que ya existan en sitios como Pinterest o Google Images, comparando el dibujo con las fotos usando visión artificial.

## 2. Cómo funciona
### 2.1 Herramientas para dibujar
Hay un lienzo (canvas) con lo básico:
- **Pincel:** Para pintar con los colores de la paleta.
- **Deshacer/Rehacer:** Por si te equivocas al trazar.
- **Goma y Limpiar:** Para borrar partes o vaciar todo el dibujo.
- **Paleta de colores:** Cada uno es una parte del cuerpo:
    - Rojo: Cabeza
    - Azul: Torso
    - Amarillo: Brazos
    - Naranja: Antebrazos
    - Morado: Manos
    - Verde: Muslos
    - Cyan: Pantorrillas
    - Rosa: Pies

### 2.2 Análisis del dibujo
1. **Detectar colores:** El programa mira qué has pintado y dónde.
2. **Crear el esqueleto:** Traduce los colores a puntos y líneas (un stickman estructurado).
3. **Palabras de búsqueda:** Genera frases como "arm raised pose reference" según cómo esté el monigote.
4. **MediaPipe:** Esta herramienta analiza las fotos de internet para sacarles el "esqueleto" y compararlo con el dibujo.
5. **Similitud:** Calcula cuánto se parecen los ángulos de los brazos y piernas.

### 2.3 Resultados
Las fotos aparecen en una galería ordenadas de la que más se parece a la que menos. Si haces clic en una, se te abre la web original en el navegador.

## 3. Estructura del programa (Arquitectura)
He usado el modelo **MVC (Modelo-Vista-Controlador)** para que el código esté ordenado:
- **Vista:** La pantalla que ve el usuario (JavaFX).
- **Controlador:** El "cerebro" que une el dibujo con las búsquedas.
- **Módulos:** Los que procesan el dibujo, buscan en la web y usan MediaPipe.
- **Base de Datos:** SQLite para guardar los dibujos, usuarios y el historial.

---

## Proceso de Desarrollo (Hitos)

### 📦 Hito 1: El Lienzo de Dibujo Funcional (25%) ✅
- [x] Ventana principal con el canvas y los botones.
- [x] Herramientas de dibujo (colores, goma, limpiar).
- [x] Funcionamiento de Deshacer/Rehacer.
- [x] El sistema ya "lee" los colores del canvas por consola.

### 📦 Hito 2: Procesamiento + Términos de Búsqueda (50%) 🔴
- [ ] El programa detecta qué partes del cuerpo has dibujado.
- [ ] Generación de frases de búsqueda automáticas (ej: "human upper body").
- [ ] Base de datos funcionando para guardar dibujos y usuarios.
- [ ] Estructura inicial para meter MediaPipe.

### 📦 Hito 3: Búsqueda + Análisis de Imágenes (75%) 🔴
- [ ] Generador de términos avanzado (detecta si el brazo está levantado, etc.).
- [ ] Interfaz con lista de términos editable y hueco para la galería.
- [ ] Lógica de búsqueda web preparada (simulada por ahora).
- [ ] Diseño de cómo se van a comparar los ángulos de las poses.

### 📦 Hito 4: Comparación de Poses + Ordenación (100%) 🔴
- [ ] Algoritmo final para calcular ángulos y puntuación (0 a 1).
- [ ] Mostrar las imágenes ordenadas por parecido.
- [ ] Abrir los enlaces de las fotos en el navegador.
- [ ] Historial de búsquedas terminado y pulido visual.

### ✨ Bonus de ui
- [x] Mejorar el aspecto visual de los botones y la paleta (iconos, bordes redondeados).
- [ ] Añadir temas (oscuro/claro) para que sea más cómodo trabajar.
- [ ] Animaciones suaves al cambiar de herramienta o limpiar el lienzo.
- [ ] Barra de progreso visual cuando el sistema esté analizando la pose.

### 🌐 Despliegue en la Web (GitHub Pages)
Aunque el proyecto es una aplicación de escritorio JavaFX, se puede lanzar como página web en GitHub de la siguiente manera:
1.  **Tecnología**: Usaríamos **JPro** o **CheerpJ**. Estas herramientas permiten que el código Java se ejecute en el navegador convirtiéndolo a WebAssembly/HTML5.
2.  **GitHub Actions**: Configuraríamos un flujo de trabajo automático que, al subir cambios, compile el proyecto para la web.
3.  **Alojamiento**: GitHub Pages alojaría los archivos estáticos generados.
4.  **Ventaja**: El usuario no tendría que instalar Java en su ordenador para usar el buscador.

### 🔑 Autenticación y Cuentas (Login con GitHub)
Para que cada usuario pueda guardar sus dibujos y búsquedas, se podría implementar un sistema de **Login con GitHub (OAuth2)**:
1.  **Cómo funciona**: El usuario pulsa un botón de "Iniciar sesión con GitHub", se abre una ventana del navegador para dar permiso, y la aplicación recibe el ID del usuario de GitHub.
2.  **Guardado de datos**: En la base de datos, cada dibujo y búsqueda se guardaría vinculado a ese ID único de GitHub.
3.  **Seguridad**: No necesitamos guardar contraseñas, ya que GitHub se encarga de la seguridad.
4.  **Experiencia**: Al ser un proyecto alojado en GitHub, es natural que los usuarios utilicen su propia cuenta para tener su historial personalizado.

---

## 4. Bitácora de Desarrollo: Errores, Visuales y Mejoras

Este apartado detalla los cambios realizados tras estabilizar el proyecto en su versión del **25% (Hito 1)**.

### 4.1 Errores Corregidos (Bugs)
*   **Detección Fantasma de "Pies":** El sistema leía el color rosa (pies) en zonas donde no se había pintado nada. Se solucionó configurando el lector para que ignore píxeles con baja opacidad (transparentes).
*   **Pérdida de Transparencia:** Al usar "Deshacer" o "Rehacer", el lienzo a veces perdía su fondo transparente y se volvía opaco, lo que rompía el análisis posterior. Ahora se usan parámetros de captura (`SnapshotParameters`) con fondo transparente.
*   **Comportamiento de la Goma:** Al borrar, a veces parecía que el lienzo "se limpiaba" de forma extraña. Se ha unificado el uso de `clearRect` para asegurar un borrado por áreas limpio y real.

### 4.2 Mejoras Visuales (UI)
*   **Enmarcado del Lienzo:** Se ha añadido un borde oscuro de 2px, un fondo blanco sólido y una sombra paralela (`dropshadow`) para que el área de dibujo destaque claramente sobre el fondo de la aplicación.
*   **Iconos en Herramientas:** Se han añadido iconos (🗑, ↩, ✎, 🧽, 🔍) a todos los botones principales para que su función sea intuitiva de un vistazo.
*   **Paleta con Indicadores:** Los botones de colores se han pasado a fondo blanco (para mayor legibilidad) y se ha añadido un **cuadrado de color real** al lado de cada nombre (ej: un cuadrito rojo al lado de "Cabeza").
*   **Tooltips e Información:** Se han añadido textos flotantes descriptivos al pasar el ratón sobre los botones y una barra de estado que indica qué herramienta y color tienes seleccionado actualmente.

### 4.3 Mejoras de Funcionamiento
*   **Alineación Ordenada:** Los botones de la paleta se han alineado a la izquierda para que los indicadores de color y los nombres queden perfectamente en columna.
*   **Análisis Robusto:** El botón de "Analizar" ahora es más preciso al ignorar el fondo, permitiendo que el sistema solo detecte los trazos reales del usuario.
