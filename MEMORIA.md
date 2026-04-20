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
- **Paleta de colores:** Cada uno es una parte del cuerpo (colores de alta visibilidad):
    - Rojo (#B71C1C): Cabeza
    - Azul (#0D47A1): Torso
    - Ámbar (#FBC02D): Brazos
    - Naranja (#E65100): Antebrazos
    - Púrpura (#4A148C): Manos
    - Verde (#1B5E20): Muslos
    - Cian (#006064): Pantorrillas
    - Rosa (#880E4F): Pies

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

### 📦 Hito 2: Procesamiento + Términos de Búsqueda (50%) ✅
- [x] El programa detecta qué partes del cuerpo has dibujado.
- [x] Generación de frases de búsqueda automáticas (ej: "human upper body").
- [x] Base de datos funcionando para guardar dibujos y usuarios.
- [x] Estructura inicial para meter MediaPipe.

### 📦 Hito 3: Búsqueda + Análisis de Imágenes (75%) 🔴
- [ ] Generador de términos avanzado (detecta si el brazo está levantado, etc.).
- [ ] Interfaz con lista de términos editable y hueco para la galería.
- [ ] Lógica de búsqueda web preparada (simulada por ahora).
- [ ] Diseño de cómo se van a comparar los ángulos de las poses.

### 📦 Hito 4: Comparación de Poses + Ordenación (100%) 🔴
- [x] Planificación del nuevo algoritmo de ángulos y clustering.
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

Este apartado detalla los cambios realizados para profesionalizar la base del **25% (Hito 1)** y corregir fallos estructurales.

### 4.1 Errores Técnicos Corregidos (Bugs)
*   **Conflicto de Módulos (SQLite):** Se eliminó el requerimiento explícito del módulo SQLite en `module-info.java` debido a incompatibilidades con algunos entornos de desarrollo. Se ha mantenido la carga dinámica mediante `Class.forName("org.sqlite.JDBC")`, permitiendo que el driver funcione correctamente desde el *classpath* sin romper el sistema de módulos de JavaFX.
*   **Error SLF4J (StaticLoggerBinder):** Se corrigió el error `Failed to load class "org.slf4j.impl.StaticLoggerBinder"` añadiendo la dependencia `slf4j-simple` al `pom.xml` y requiriendo el módulo `org.slf4j` en `module-info.java`. Esto asegura que las librerías que dependen de SLF4J (como el driver de SQLite) funcionen correctamente y no bloqueen el arranque de la aplicación en ciertos entornos.
*   **No Suitable Driver (SQLite):** En entornos modulares de Java, el driver de SQLite no siempre se cargaba automáticamente. Se solucionó forzando su carga con `Class.forName("org.sqlite.JDBC")` en el `DatabaseManager`.
*   **IllegalStateException en Análisis:** Se corrigió el fallo al intentar capturar el lienzo desde un hilo secundario (`Thread-3`). Ahora el sistema captura la imagen en el hilo de la interfaz (`UI Thread`) y procesa los datos en segundo plano, cumpliendo con las normas de hilos de JavaFX.
*   **Pincel Inmóvil:** El sistema no dibujaba nada si solo se hacía clic (sin arrastrar). Se corrigió en `handleMousePressed` forzando un trazado inicial.
*   **Sincronización Undo/Redo:** Se arregló el fallo de "primer clic inútil" al deshacer, gestionando correctamente la pila de estados (el estado inicial se guarda ahora al arrancar).
*   **Análisis Eficiente:** El `DrawingProcessor` se ha optimizado. Antes recorría el lienzo 8 veces (una por color); ahora lo hace en una sola pasada **O(W*H)**, reduciendo drásticamente el uso de CPU.
*   **Fluidez de la UI:** El procesamiento del dibujo se ha movido a un hilo separado (`Task`), evitando que la ventana se congele durante el análisis. Se añadió una barra de progreso.

### 4.2 Mejoras de Arquitectura y Limpieza
*   **Desacoplamiento (MVC real):** Se eliminó el uso de colores de JavaFX en el modelo. Ahora se usa un **Enum `AnatomyPart`** que guarda nombres y códigos hexadecimales, facilitando futuros tests sin depender de la interfaz.
*   **Eliminación de Código Muerto:** Se borraron las plantillas por defecto (`HelloController`, `hello-view.fxml`) y se renombró la aplicación a `BuscaReferenciasApp`. También se eliminó el método redundante `saveState()` en el controlador, unificando la lógica de guardado de estados en `saveCurrentState()`.
*   **Gestión de Estilos:** Se extrajeron todos los estilos CSS de los archivos Java y FXML a un archivo externo `style.css`, siguiendo las buenas prácticas de diseño.
*   **Corrección del Borde Invisible:** Se detectó un área blanca en el borde del lienzo donde no se podía dibujar debido a que el contenedor (`VBox`) tenía fondo blanco y un borde interno. Se ha corregido haciendo transparente el contenedor y forzando que el `Canvas` sea el que gestione su propio fondo blanco, alineando perfectamente el área visual con el área funcional.
*   **Estilización del Estado:** Se ajustaron los colores de la barra de estado y los mensajes de análisis en el CSS (`#statusLabel`) para usar tonos grises oscuros profesionales y evitar confusiones visuales con errores.

### 4.3 Mejoras Algorítmicas y de Depuración
*   **Robustez de Color (HSB):** El sistema ya no usa comparación RGB simple, que fallaba con los bordes suavizados (antialiasing). Ahora usa el modelo **HSB (Matiz)**, que es mucho más fiable para detectar colores. Se ha ajustado el umbral a 10 grados para evitar confusiones entre Rojo (Cabeza) y Rosa (Pies), que tienen matices cercanos.
*   **Nueva Paleta de Colores (Hito 2):** Se han actualizado los colores de la anatomía por tonos más oscuros y profesionales (Material Design 700-900). Esto soluciona el problema de visibilidad del Amarillo, Cian y Rosa sobre el fondo blanco del lienzo, mejorando el contraste sin afectar a la lógica de detección HSB.
*   **Estructura de Pose:** `PoseData` ahora usa tipos seguros (`AnatomyPart`) en lugar de simples cadenas de texto, evitando errores de escritura en el código.
*   **Visibilidad de Logs:** Se ha ajustado la salida por terminal del análisis de pose. Ahora utiliza `System.out` con mensajes claros y descriptivos, asegurando que los resultados sean visibles en la consola del IDE sin aparecer resaltados en rojo como si fueran errores críticos.

## 5. Mejoras de Calidad de Código y Testing

Siguiendo las mejores prácticas de ingeniería de software, se han aplicado las siguientes mejoras:

### 5.1 Refactorización de Maven
*   **Nombres y Convenciones:** El `groupId` se ha cambiado a `org.refcolor` y el `artifactId` a `busca-referencias`, cumpliendo con las convenciones estándar de Maven.
*   **Limpieza de Dependencias:** Se han eliminado los módulos de JavaFX no utilizados (`web`, `swing`, `media`), reduciendo el tamaño del proyecto y simplificando la configuración.

### 5.2 Estructuras de Datos y Rendimiento
*   **De Stack a Deque:** Se ha sustituido el uso de `java.util.Stack` por `java.util.ArrayDeque` para gestionar el historial (Deshacer/Rehacer), tal como recomienda Oracle por ser una estructura más eficiente y moderna.
*   **Uso de Loggers:** Se ha eliminado `System.out.println` en favor de `java.util.logging.Logger`. Esto permite una mejor gestión de los mensajes de depuración y errores, cumpliendo con los estándares profesionales.

### 5.3 Control de Versiones (Git)
*   Se ha optimizado el archivo `.gitignore` para ignorar correctamente todos los archivos de configuración de IntelliJ IDEA (`.idea/`) y los ficheros de log (`*.log`), manteniendo el repositorio limpio de archivos locales.

### 5.4 Introducción de Tests Unitarios
*   Se ha creado la estructura de carpetas `src/test/java` para alojar pruebas automatizadas.
*   **AnatomyPartTest:** Verifica que los colores y nombres de la anatomía sean correctos.
*   **DrawingProcessorTest:** Comprueba que la lógica de detección de colores (HSB) sea robusta ante variaciones de tono y saturación.

## 6. Evolución del Algoritmo (Hito 4)

El enfoque inicial basado en **centroides simples** (un solo punto por color) se ha descartado para el Hito 4. Si el usuario dibuja ambos brazos, el centroide caería en el centro del pecho, perdiendo toda la información de la pose.

### Nuevo enfoque: Análisis de Componentes y Vectores

1.  **Separación de Limbos (Clustering):** Se implementará un algoritmo de agrupación (como K-Means simple o búsqueda de componentes conectados) para detectar si un color (ej: Brazos) se encuentra en dos zonas distintas del lienzo.
2.  **Identificación Lateral:** Se usará el **Torso** como eje central. Los componentes a la izquierda del centro del torso se marcarán como "Izquierda" y los de la derecha como "Derecha".
3.  **Extracción de Ángulos (Vectores):** 
    - Se calcularán los vectores entre las articulaciones (ej: Hombro -> Codo -> Muñeca).
    - El ángulo se obtendrá mediante el producto escalar de estos vectores.
4.  **Normalización de Pose:** La pose detectada se escalará a un tamaño estándar para compararla con los datos de **MediaPipe**, ignorando el tamaño o posición absoluta del dibujo en el lienzo.
5.  **Puntuación de Similitud:** Se usará una suma ponderada de las diferencias de ángulos (la inclinación del torso y los brazos tendrá más peso que la de las manos).

## 7. Funcionamiento de la Base de Datos

El sistema utiliza **SQLite** como motor de base de datos persistente. Se ha elegido por ser ligero, no requerir un servidor externo y almacenar toda la información en un único archivo (`buscareferencias.db`), lo que facilita la portabilidad de la aplicación.

### 7.1 Esquema de Tablas (Modelo Relacional)

La base de datos está organizada en cuatro tablas que permiten mantener un historial completo:

1.  **Usuarios:** Almacena la información de los usuarios. Actualmente se usa un usuario local por defecto, pero está preparada para el sistema de Login con GitHub.
    *   Campos: `id_usuario`, `nombre_usuario`, `id_github`, `fecha_creacion`.
2.  **Dibujos:** Guarda cada "monigote" analizado. La pose se almacena como una cadena de texto que describe las coordenadas de cada articulación detectada.
    *   Campos: `id_dibujo`, `id_usuario` (FK), `datos_pose`, `fecha_creacion`.
3.  **Busquedas:** Relaciona un dibujo con los términos de búsqueda generados (ej: "standing pose").
    *   Campos: `id_busqueda`, `id_dibujo` (FK), `terminos_busqueda`, `fecha_busqueda`.
4.  **Resultados:** Almacenará las imágenes encontradas en la web para cada búsqueda, incluyendo su URL y la puntuación de parecido.
    *   Campos: `id_resultado`, `id_busqueda` (FK), `url_imagen`, `url_origen`, `puntuacion_similitud`.

### 7.2 Lógica de Persistencia

*   **Inicialización automática:** Al arrancar la aplicación, el `DatabaseManager` comprueba si las tablas existen y las crea si es necesario. También inserta el usuario inicial de forma automática.
*   **Transacciones Seguras:** Cuando el usuario pulsa "Analizar", el sistema guarda el dibujo y los términos de búsqueda de forma atómica. Si falla el guardado de los términos, se hace un *rollback* para que no queden dibujos huérfanos sin su búsqueda asociada.
*   **Carga Dinámica:** Para evitar problemas en Java 17+, el driver de SQLite se carga explícitamente, asegurando que la conexión funcione tanto en el entorno de desarrollo como en el ejecutable final.
