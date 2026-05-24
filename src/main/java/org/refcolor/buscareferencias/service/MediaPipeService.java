package org.refcolor.buscareferencias.service;

import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.model.SimilarityBreakdown;
import org.refcolor.buscareferencias.database.DatabaseManager;
import org.refcolor.buscareferencias.client.PythonImageSearchClient;
import org.refcolor.buscareferencias.utils.LocalImagePaths;
import org.refcolor.buscareferencias.utils.PoseToleranceConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

/**
 * Servicio para la integración de MediaPipe.
 */
public class MediaPipeService {

    // Caché temporal en memoria durante la sesión de búsqueda para evitar re-análisis
    private static final java.util.concurrent.ConcurrentMap<String, PoseData> SESSION_POSE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Resolución de rutas locales para miniaturas (sin descargas HTTP).
     */
    public static class ImageCacheService {

        /**
         * Limpia/reinicia la caché de sesión al comenzar una nueva búsqueda.
         */
        public static synchronized void startSessionCache() {
            SESSION_POSE_CACHE.clear();
        }

        /** @deprecated Usar {@link #startSessionCache()} */
        @Deprecated
        public static synchronized void clearSessionCache() {
            startSessionCache();
        }

        public static String resolveLocalPath(String imageSource) {
            String uri = LocalImagePaths.toFileUri(imageSource);
            return uri.isBlank() && imageSource != null ? imageSource : uri;
        }
    }

    /**
     * Limpia la caché de poses en memoria (llamar al iniciar una nueva búsqueda)
     */
    public static void clearSessionPoseCache() {
        SESSION_POSE_CACHE.clear();  // ConcurrentHashMap.clear() nunca lanza excepciones checked (S3)
    }

    /**
     * Vista de solo lectura de la caché en memoria de esta sesión.
     * Usada por SearchService para el merge con la caché persistente de BD.
     */
    public static java.util.Map<String, PoseData> getSessionCache() {
        return java.util.Collections.unmodifiableMap(SESSION_POSE_CACHE);
    }

    private static final Logger logger = LoggerFactory.getLogger(MediaPipeService.class);

    /**
     * Devuelve una pose cacheada (sesión o BD) sin invocar Python.
     */
    public static PoseData peekCachedPose(String imageSource) {
        if (imageSource == null || imageSource.isBlank()) {
            return null;
        }
        String key = LocalImagePaths.toAbsolutePath(imageSource);
        if (key == null) {
            key = imageSource;
        }
        if (SESSION_POSE_CACHE.containsKey(key)) {
            return SESSION_POSE_CACHE.get(key);
        }
        PoseData dbPose = DatabaseManager.getCachedPose(key);
        if (dbPose == null) {
            dbPose = DatabaseManager.getCachedPose(imageSource);
        }
        if (dbPose != null && !dbPose.getAllLandmarks().isEmpty()) {
            SESSION_POSE_CACHE.put(key, dbPose);
        }
        return dbPose;
    }

    /**
     * Analiza una imagen local para extraer la pose usando MediaPipe vía Python.
     */
    public static PoseData analyzeImage(String imageSource) {
        PoseData cached = peekCachedPose(imageSource);
        if (cached != null && !cached.getAllLandmarks().isEmpty()) {
            logger.info("[MEDIAPIPE] Usando pose cacheada para: {}", imageSource);
            return cached;
        }

            logger.info("[MEDIAPIPE] Analizando: {}", imageSource);

        try {
            if (imageSource != null && (imageSource.startsWith("http://") || imageSource.startsWith("https://"))) {
                logger.warn("[MEDIAPIPE] Solo se analizan archivos locales. Ignorado: {}", imageSource);
                return new PoseData();
            }

            imageSource = LocalImagePaths.toAbsolutePath(imageSource);
            if (imageSource == null) {
                logger.warn("[MEDIAPIPE] Ruta local no válida");
                return new PoseData();
            }

            Path imagePath = Paths.get(imageSource);

            if (!Files.exists(imagePath)) {
                logger.warn("[MEDIAPIPE] Archivo no encontrado: {}", imageSource);
                return new PoseData();
            }

            Path scriptPath = PythonImageSearchClient.resolveProjectScript("pose_analyzer.py");
            PythonImageSearchClient.CommandResult result = PythonImageSearchClient.runPythonScript(
                    scriptPath,
                    List.of(imageSource),
                    scriptPath != null ? scriptPath.getParent() : null,
                    60
            );

                    if (result.succeeded()) {
                String outStr = result.stdout();
                if (outStr.contains("\"landmarks\":")) {
                    PoseData pose = parsePoseFromJson(new JSONObject(outStr), imageSource);
                    try { SESSION_POSE_CACHE.put(imageSource, pose); } catch (Exception ignored) {}
                    return pose;
                }
            }
            logger.warn("MediaPipe falló o no detectó nada. exitCode={} error={}",
                    result.exitCode(), result.error());
            if (!result.stderr().isBlank()) {
                logger.warn("MediaPipe stderr: {}", result.stderr().substring(0, Math.min(500, result.stderr().length())));
            }
            return new PoseData();
        } catch (Exception e) {
            logger.error("Error en analyzeImage", e);
            return new PoseData();
        }
    }

    /**
     * Analiza un lote de imágenes con UN SOLO proceso Python (modo --batch).
     * El modelo MediaPipe se carga una única vez → mucho menos RAM y calor.
     * Devuelve un mapa [rutaAbsoluta → PoseData]; las imágenes sin pose detectada
     * no aparecen en el mapa (o aparecen con PoseData vacío si hubo error de parseo).
     */
    public static java.util.Map<String, PoseData> analyzeImageBatch(List<String> imagePaths) {
        return analyzeImageBatch(imagePaths, null);
    }

    /**
     * Same as {@link #analyzeImageBatch(List)} but calls {@code onImageDone} with the running
     * count of images analyzed so far, enabling real-time per-image batch progress reporting.
     */
    public static java.util.Map<String, PoseData> analyzeImageBatch(List<String> imagePaths, Consumer<Integer> onImageDone) {
        java.util.Map<String, PoseData> results = new java.util.LinkedHashMap<>();
        if (imagePaths == null || imagePaths.isEmpty()) {
            return results;
        }

        Path scriptPath = PythonImageSearchClient.resolveProjectScript("pose_analyzer.py");
        if (scriptPath == null) {
            logger.warn("[BATCH] Script pose_analyzer.py no encontrado; batch cancelado.");
            return results;
        }

        // Timeout generoso: ~3 s por imagen es más que suficiente
        int timeoutSeconds = Math.max(120, imagePaths.size() * 3);
        logger.info("[BATCH] Analizando {} imágenes en un solo proceso Python (timeout {}s)…",
                imagePaths.size(), timeoutSeconds);

        List<String> jsonLines = PythonImageSearchClient.runBatchScript(scriptPath, imagePaths, timeoutSeconds, onImageDone);

        for (String line : jsonLines) {
            try {
                JSONObject json = new JSONObject(line);
                String path = json.optString("_path", "");
                if (path.isBlank()) continue;
                if (!json.has("landmarks")) continue;
                PoseData pose = parsePoseFromJson(json, path);
                if (!pose.getAllLandmarks().isEmpty()) {
                    SESSION_POSE_CACHE.put(path, pose);
                }
                results.put(path, pose);
            } catch (Exception e) {
                logger.debug("[BATCH] Error parseando línea batch: {}", e.toString());
            }
        }
        logger.info("[BATCH] {} poses detectadas de {} imágenes.", results.size(), imagePaths.size());

        // Persistir de forma asíncrona para no bloquear el hilo de búsqueda
        if (!results.isEmpty()) {
            final java.util.Map<String, PoseData> toSave = new java.util.HashMap<>(results);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    org.refcolor.buscareferencias.database.DatabaseManager.cacheImagePoses(toSave);
                } catch (Exception ex) {
                    logger.warn("[BATCH] Error guardando poses en BD: {}", ex.getMessage());
                }
            });
        }

        return results;
    }

    /** Convierte el JSON de pose_analyzer.py en un objeto PoseData. */
    private static PoseData parsePoseFromJson(JSONObject json, String imageSource) {
        PoseData pose = new PoseData();
        try {
            if (json.has("debug")) {
                JSONObject debug = json.getJSONObject("debug");
                logger.info("[DEBUG_MP] {}: puntos={} confianza={}",
                        imageSource,
                        debug.optInt("points_found", 0),
                        String.format("%.2f", debug.optDouble("avg_confidence", 0.0)));
            }
            JSONObject landmarks = json.getJSONObject("landmarks");
            for (String key : landmarks.keySet()) {
                int id = Integer.parseInt(key);
                JSONObject lm = landmarks.getJSONObject(key);
                double vis = lm.optDouble("visibility", -1.0);
                if (vis >= 0) {
                    pose.addLandmark(id, lm.getDouble("x"), lm.getDouble("y"), vis);
                } else {
                    pose.addLandmark(id, lm.getDouble("x"), lm.getDouble("y"));
                }
            }
            if (json.has("embedding")) {
                JSONArray embArray = json.getJSONArray("embedding");
                List<Double> embedding = new ArrayList<>();
                for (int i = 0; i < embArray.length(); i++) {
                    embedding.add(embArray.getDouble(i));
                }
                pose.setEmbedding(embedding);
            }
            if (json.has("pose_angles") && json.get("pose_angles") instanceof JSONObject poseAngles) {
                for (String key : poseAngles.keySet()) {
                    try { pose.putPoseAngle(key, poseAngles.getDouble(key)); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            logger.debug("[MEDIAPIPE] Error parseando JSON de pose: {}", e.toString());
        }
        return pose;
    }

    /**
     * Calcula la similitud final y devuelve el desglose completo de componentes.
     *
     * <p>Fórmula con embeddings:  0.40·cosine + 0.35·angles + 0.17·skeleton + 0.08·contour<br>
     * Sin embeddings:            0.60·angles + 0.30·skeleton + 0.10·contour</p>
     */
    public static SimilarityBreakdown calculateSimilarityWithBreakdown(PoseData drawingPose, PoseData imagePose) {
        if (imagePose == null || drawingPose == null
                || imagePose.getAllLandmarks().isEmpty() || drawingPose.getAllJoints().isEmpty()) {
            return SimilarityBreakdown.EMPTY;
        }

        int jointCount    = drawingPose.getAllJoints().size();
        int landmarkCount = imagePose.getAllLandmarks().size();

        double partial    = calculatePartialDrawingSimilarity(drawingPose, imagePose);
        double headSim    = headOnlySimilarity(drawingPose.getAllJoints(), imagePose.getAllLandmarks());
        double cosine     = calculateCosineSimilarity(drawingPose.getEmbedding(), imagePose.getEmbedding());
        double angles     = calculateAngleBasedSimilarity(drawingPose, imagePose);
        double positions  = scoreEndpointPositions2D(drawingPose, imagePose);  // -1 si no aplica
        double skeleton   = calculateSkeletonSimilarity(drawingPose, imagePose);
        double contour    = calculateContourSimilarity(drawingPose, imagePose);
        double coverage   = calculateCoverageFactor(drawingPose, imagePose);

        boolean hasEmbeddings = cosine > 0.0;
        double finalScore = computeWeightedScore(
                jointCount, hasEmbeddings, partial, headSim, cosine, angles, positions, skeleton, contour, coverage);
        finalScore = Math.max(0.0, Math.min(1.0, finalScore));

        logger.info("[SIMILARITY] joints={} partial={} head={} cosine={} angles={} pos={} skeleton={} contour={} coverage={} final={}",
                jointCount,
                String.format("%.4f", partial),   String.format("%.4f", headSim),
                String.format("%.4f", cosine),    String.format("%.4f", angles),
                String.format("%.4f", positions), String.format("%.4f", skeleton),
                String.format("%.4f", contour),   String.format("%.4f", coverage),
                String.format("%.4f", finalScore));

        return new SimilarityBreakdown(cosine, angles, positions, skeleton, contour, coverage,
                finalScore, hasEmbeddings, jointCount, landmarkCount);
    }

    /**
     * Calcula la similitud final (solo el score, sin desglose).
     * Para obtener el desglose completo usa {@link #calculateSimilarityWithBreakdown}.
     */
    public static double calculateSimilarity(PoseData drawingPose, PoseData imagePose) {
        return calculateSimilarityWithBreakdown(drawingPose, imagePose).finalScore();
    }

    /**
     * Selecciona la fórmula de puntuación según el tipo de dibujo y disponibilidad de componentes.
     *
     * <p>Si {@code positions >= 0} (el dibujo tiene manos/pies), se incluye como
     * componente separado con peso propio. Si {@code positions == -1.0} (sin extremidades),
     * su peso se redistribuye al esqueleto para no penalizar dibujos parciales.</p>
     */
    private static double computeWeightedScore(
            int jointCount, boolean hasEmbeddings,
            double partial, double headSim, double cosine,
            double angles, double positions, double skeleton, double contour,
            double coverageFactor) {
        if (jointCount <= 4) {
            // Dibujo parcial (p. ej. solo cabeza): posición y cobertura
            return Math.max(partial, headSim) * coverageFactor;
        }
        boolean hasPos = positions >= 0.0;
        double score;
        if (hasEmbeddings) {
            // Con embedding: cosine captura la pose global; ángulos y posición discriminan extremidades
            score = (0.35 * cosine) + (0.28 * angles)
                  + (hasPos ? 0.15 * positions + 0.14 * skeleton : 0.30 * skeleton)
                  + 0.08 * contour;
        } else {
            // Sin embedding: ángulos + posición son los discriminadores principales
            score = (0.48 * angles)
                  + (hasPos ? 0.17 * positions + 0.25 * skeleton : 0.42 * skeleton)
                  + 0.10 * contour;
        }
        score = Math.max(score, partial * 0.25);
        return score * coverageFactor;
    }

    /**
     * Compara cada parte dibujada con su landmark en coordenadas normalizadas (0-1).
     * Adecuado cuando el dibujo no incluye torso completo.
     */
    private static double calculatePartialDrawingSimilarity(PoseData drawingPose, PoseData imagePose) {
        var joints = drawingPose.getAllJoints();
        var lm = imagePose.getAllLandmarks();
        java.util.Map<AnatomyPart, Integer[]> mapping = partToLandmarkIds();
        double total = 0.0;
        int counted = 0;
        for (var entry : mapping.entrySet()) {
            AnatomyPart part = entry.getKey();
            if (!joints.containsKey(part)) {
                continue;
            }
            javafx.geometry.Point2D drawP = joints.get(part);
            javafx.geometry.Point2D imgP = averageLandmark(lm, entry.getValue());
            if (imgP == null) {
                continue;
            }
            double dist = Math.hypot(drawP.getX() - imgP.getX(), drawP.getY() - imgP.getY());
            // Tolerancia convertida a espacio 0-1 (configuración pensada para espacio normalizado ~[-2,2])
            double tolerance = PoseToleranceConfig.skeletonTolerance(part) * 0.06;
            total += Math.max(0.0, 1.0 - (dist / tolerance));
            counted++;
        }
        if (counted == 0) {
            return 0.0;
        }
        return total / counted;
    }

    /**
     * Regiones corporales que el usuario dibujó, basadas en los joints presentes.
     * HEAD / UPPER / ARMS / LOWER, mapeadas igual que {@link PoseData#getVisibleRegions()}.
     */
    private static java.util.Set<String> getDrawingRegions(java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints) {
        java.util.Set<String> regions = new java.util.HashSet<>();
        if (joints.containsKey(AnatomyPart.HEAD)) regions.add("HEAD");
        if (joints.containsKey(AnatomyPart.TORSO)
         || joints.containsKey(AnatomyPart.SHOULDERS)
         || joints.containsKey(AnatomyPart.LEFT_SHOULDER) || joints.containsKey(AnatomyPart.RIGHT_SHOULDER))
            regions.add("UPPER");
        if (joints.containsKey(AnatomyPart.ARMS)        || joints.containsKey(AnatomyPart.FOREARMS)      || joints.containsKey(AnatomyPart.HANDS)
         || joints.containsKey(AnatomyPart.LEFT_ARM)     || joints.containsKey(AnatomyPart.RIGHT_ARM)
         || joints.containsKey(AnatomyPart.LEFT_FOREARM) || joints.containsKey(AnatomyPart.RIGHT_FOREARM)
         || joints.containsKey(AnatomyPart.LEFT_HAND)    || joints.containsKey(AnatomyPart.RIGHT_HAND))
            regions.add("ARMS");
        if (joints.containsKey(AnatomyPart.HIPS)
         || joints.containsKey(AnatomyPart.LEFT_HIP)     || joints.containsKey(AnatomyPart.RIGHT_HIP)
         || joints.containsKey(AnatomyPart.THIGHS)       || joints.containsKey(AnatomyPart.CALVES)        || joints.containsKey(AnatomyPart.FEET)
         || joints.containsKey(AnatomyPart.LEFT_THIGH)   || joints.containsKey(AnatomyPart.RIGHT_THIGH)
         || joints.containsKey(AnatomyPart.LEFT_CALF)    || joints.containsKey(AnatomyPart.RIGHT_CALF)
         || joints.containsKey(AnatomyPart.LEFT_FOOT)    || joints.containsKey(AnatomyPart.RIGHT_FOOT))
            regions.add("LOWER");
        return regions;
    }

    /**
     * Factor de penalización por cobertura corporal.
     * Penaliza fotos que muestran más partes del cuerpo de las que se dibujaron.
     * Si la foto no tiene datos de visibilidad (caché antigua) devuelve 1.0 sin penalizar.
     */
    private static double calculateCoverageFactor(PoseData drawingPose, PoseData imagePose) {
        if (!imagePose.hasVisibilityData()) return 1.0;
        java.util.Set<String> drawingRegions = getDrawingRegions(drawingPose.getAllJoints());
        java.util.Set<String> photoRegions = imagePose.getVisibleRegions();
        if (drawingRegions.isEmpty()) return 1.0;

        long extraInPhoto = photoRegions.stream().filter(r -> !drawingRegions.contains(r)).count();
        long missingFromPhoto = drawingRegions.stream().filter(r -> !photoRegions.contains(r)).count();

        // La foto muestra MÁS de lo dibujado → penalización MUY leve (0.15/región).
        //   Ejemplo: dibujas cabeza+pies → foto cuerpo completo tiene UPPER+ARMS extra.
        //   No queremos penalizar esas fotos de cuerpo completo que son justo las deseadas.
        // La foto le FALTA algo dibujado → penalización moderada (0.25/región).
        //   Ejemplo: dibujas pies pero la foto no los muestra → sí es un desajuste real.
        double penalty = (extraInPhoto * 0.15 + missingFromPhoto * 0.25) / 4.0;
        return Math.max(0.1, 1.0 - penalty);
    }

    /**
     * Mapea cada joint dibujado al/los landmark(s) de MediaPipe con los que
     * debe compararse posicionalmente.
     *
     * <p><b>Regla general para segmentos:</b> los joints dibujados como líneas
     * son el CENTROIDE de los píxeles del trazo, que coincide con el punto
     * MEDIO del segmento. Por tanto cada joint de segmento se compara contra
     * el promedio de los dos landmarks que delimitan ese segmento:</p>
     * <ul>
     *   <li>LEFT_ARM (trazo hombro→codo) → media(LM11, LM13) = punto medio del húmero</li>
     *   <li>LEFT_FOREARM (trazo codo→muñeca) → media(LM13, LM15) = punto medio del radio</li>
     *   <li>LEFT_THIGH (trazo cadera→rodilla) → media(LM23, LM25)</li>
     *   <li>LEFT_CALF (trazo rodilla→tobillo) → media(LM25, LM27)</li>
     * </ul>
     *
     * <p><b>Joints de punto exacto</b> (SHOULDERS y HIPS): se dibujan como
     * círculos rellenos sobre la articulación → se comparan contra el landmark
     * exacto (LM11/LM12 para hombros; LM23/LM24 para caderas).</p>
     */
    private static java.util.Map<AnatomyPart, Integer[]> partToLandmarkIds() {
        java.util.Map<AnatomyPart, Integer[]> mapping = new java.util.HashMap<>();

        // ── No bilaterales ────────────────────────────────────────────────────
        mapping.put(AnatomyPart.HEAD,  new Integer[]{0});
        // TORSO: centroide de la línea columna ≈ punto medio hombros-caderas
        mapping.put(AnatomyPart.TORSO, new Integer[]{11, 12, 23, 24});

        // ── Hombros y caderas: puntos exactos de articulación ─────────────────
        mapping.put(AnatomyPart.LEFT_SHOULDER,  new Integer[]{11});
        mapping.put(AnatomyPart.RIGHT_SHOULDER, new Integer[]{12});
        mapping.put(AnatomyPart.LEFT_HIP,       new Integer[]{23});
        mapping.put(AnatomyPart.RIGHT_HIP,      new Integer[]{24});
        // Centroides combinados (si no se activó bilateral)
        mapping.put(AnatomyPart.SHOULDERS, new Integer[]{11, 12});
        mapping.put(AnatomyPart.HIPS,      new Integer[]{23, 24});

        // ── Brazos: centroide de segmento = punto medio del hueso ─────────────
        //   LEFT_ARM (hombro→codo) → media(LM11=hombro, LM13=codo)
        mapping.put(AnatomyPart.LEFT_ARM,      new Integer[]{11, 13});
        mapping.put(AnatomyPart.RIGHT_ARM,     new Integer[]{12, 14});
        //   LEFT_FOREARM (codo→muñeca) → media(LM13=codo, LM15=muñeca)
        mapping.put(AnatomyPart.LEFT_FOREARM,  new Integer[]{13, 15});
        mapping.put(AnatomyPart.RIGHT_FOREARM, new Integer[]{14, 16});
        //   LEFT_HAND: pequeño óvalo cerca de la muñeca
        mapping.put(AnatomyPart.LEFT_HAND,     new Integer[]{15});
        mapping.put(AnatomyPart.RIGHT_HAND,    new Integer[]{16});
        // Centroides combinados
        mapping.put(AnatomyPart.ARMS,     new Integer[]{11, 12});
        mapping.put(AnatomyPart.FOREARMS, new Integer[]{13, 14});
        mapping.put(AnatomyPart.HANDS,    new Integer[]{15, 16});

        // ── Piernas: centroide de segmento = punto medio del hueso ────────────
        //   LEFT_THIGH (cadera→rodilla) → media(LM23=cadera, LM25=rodilla)
        mapping.put(AnatomyPart.LEFT_THIGH,  new Integer[]{23, 25});
        mapping.put(AnatomyPart.RIGHT_THIGH, new Integer[]{24, 26});
        //   LEFT_CALF (rodilla→tobillo) → media(LM25=rodilla, LM27=tobillo)
        mapping.put(AnatomyPart.LEFT_CALF,   new Integer[]{25, 27});
        mapping.put(AnatomyPart.RIGHT_CALF,  new Integer[]{26, 28});
        //   LEFT_FOOT: línea corta desde el tobillo
        mapping.put(AnatomyPart.LEFT_FOOT,   new Integer[]{27});
        mapping.put(AnatomyPart.RIGHT_FOOT,  new Integer[]{28});
        // Centroides combinados
        mapping.put(AnatomyPart.THIGHS, new Integer[]{23, 24});
        mapping.put(AnatomyPart.CALVES, new Integer[]{25, 26});
        mapping.put(AnatomyPart.FEET,   new Integer[]{27, 28});

        return mapping;
    }

    private static javafx.geometry.Point2D averageLandmark(
            java.util.Map<Integer, javafx.geometry.Point2D> lm, Integer[] ids) {
        double sumX = 0;
        double sumY = 0;
        int found = 0;
        for (int id : ids) {
            if (lm.containsKey(id)) {
                sumX += lm.get(id).getX();
                sumY += lm.get(id).getY();
                found++;
            }
        }
        if (found == 0) {
            return null;
        }
        return new javafx.geometry.Point2D(sumX / found, sumY / found);
    }

    /**
     * Centro corporal del dibujo para normalización de posiciones.
     * Misma prioridad que MediaPipe usa en la imagen (caderas → torso → centroide).
     */
    private static javafx.geometry.Point2D findDrawCenter(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints) {
        if (joints.containsKey(AnatomyPart.LEFT_HIP) && joints.containsKey(AnatomyPart.RIGHT_HIP))
            return new javafx.geometry.Point2D(
                    (joints.get(AnatomyPart.LEFT_HIP).getX() + joints.get(AnatomyPart.RIGHT_HIP).getX()) / 2.0,
                    (joints.get(AnatomyPart.LEFT_HIP).getY() + joints.get(AnatomyPart.RIGHT_HIP).getY()) / 2.0);
        if (joints.containsKey(AnatomyPart.LEFT_HIP))  return joints.get(AnatomyPart.LEFT_HIP);
        if (joints.containsKey(AnatomyPart.RIGHT_HIP)) return joints.get(AnatomyPart.RIGHT_HIP);
        if (joints.containsKey(AnatomyPart.HIPS))      return joints.get(AnatomyPart.HIPS);
        javafx.geometry.Point2D torso = joints.get(AnatomyPart.TORSO);
        return torso != null ? torso : estimateJointCenter(joints);
    }

    private static double calculateSkeletonSimilarity(PoseData drawingPose, PoseData imagePose) {
        try {
            var joints = drawingPose.getAllJoints();
            var lm = imagePose.getAllLandmarks();

            javafx.geometry.Point2D drawCenter = findDrawCenter(joints);
            javafx.geometry.Point2D imgCenter  = findImageCenter(lm);

            if (drawCenter == null || imgCenter == null) {
                return headOnlySimilarity(joints, lm);
            }

            double drawScale = estimateDrawScale(joints, drawCenter);
            double imgScale = estimateImageScale(lm, imgCenter);
            if (drawScale <= 0) drawScale = 0.15;
            if (imgScale <= 0) imgScale = 0.15;

            java.util.Map<org.refcolor.buscareferencias.model.AnatomyPart, Integer[]> mapping = partToLandmarkIds();

            double total = 0.0;
            double totalWeight = 0.0;
            int counted = 0;
            for (var entry : mapping.entrySet()) {
                var part = entry.getKey();
                if (!joints.containsKey(part)) continue;
                javafx.geometry.Point2D drawP = joints.get(part);
                Integer[] ids = entry.getValue();
                javafx.geometry.Point2D imgP = averageLandmark(lm, ids);
                if (imgP == null) continue;

                double ndx = (drawP.getX() - drawCenter.getX()) / drawScale;
                double ndy = (drawP.getY() - drawCenter.getY()) / drawScale;
                double nix = (imgP.getX() - imgCenter.getX()) / imgScale;
                double niy = (imgP.getY() - imgCenter.getY()) / imgScale;

                double dist = Math.hypot(ndx - nix, ndy - niy);
                double tolerance = PoseToleranceConfig.skeletonTolerance(part);
                double sim = Math.max(0.0, 1.0 - (dist / tolerance));
                // Las extremidades (brazos, manos, pies) ponderan más que torso/caderas
                double pw = getSkeletonPartWeight(part);
                total += sim * pw;
                totalWeight += pw;
                counted++;
            }
            if (counted == 0) {
                return headOnlySimilarity(joints, lm);
            }
            double similarity = total / totalWeight;
            similarity *= Math.min(1.0, Math.max(0.35, counted / 8.0));
            return similarity;
        } catch (Exception e) {
            logger.debug("Error calculating skeleton similarity: {}", e.toString());
            return 0.0;
        }
    }

    /**
     * Score de posición 2D de extremidades: compara dónde están las manos y pies
     * en el espacio corporal normalizado (X e Y respecto al centro, escalado por torso).
     *
     * <p>Complementa a los scorers de ángulo (que comparan la DIRECCIÓN de los segmentos)
     * capturando SI las extremidades están en el cuadrante correcto del cuerpo:
     * mano izquierda arriba vs. mano izquierda a la derecha del cuerpo, etc.</p>
     *
     * @return score en [0,1], o {@code -1.0} si el dibujo no tiene ninguna extremidad.
     */
    private static double scoreEndpointPositions2D(PoseData drawingPose, PoseData imagePose) {
        var joints = drawingPose.getAllJoints();
        var lm     = imagePose.getAllLandmarks();

        javafx.geometry.Point2D drawCenter = findDrawCenter(joints);
        if (drawCenter == null) return -1.0;
        double drawScale = estimateDrawScale(joints, drawCenter);
        if (drawScale <= 0) drawScale = 0.15;

        javafx.geometry.Point2D imgCenter = findImageCenter(lm);
        if (imgCenter == null) return -1.0;
        double imgScale = estimateImageScale(lm, imgCenter);
        if (imgScale <= 0) imgScale = 0.15;

        // Pares: joint del dibujo → landmark MediaPipe correspondiente al extremo
        record Pair(AnatomyPart part, int lmId) {}
        var endpoints = java.util.List.of(
                new Pair(AnatomyPart.LEFT_HAND,  15),
                new Pair(AnatomyPart.RIGHT_HAND, 16),
                new Pair(AnatomyPart.LEFT_FOOT,  27),
                new Pair(AnatomyPart.RIGHT_FOOT, 28));

        double totalScore = 0;
        int counted = 0;
        final double tolerance = 1.0;  // 1 longitud de torso (escalada) de tolerancia

        for (var ep : endpoints) {
            if (!joints.containsKey(ep.part()) || !lm.containsKey(ep.lmId())) continue;
            javafx.geometry.Point2D drawP = joints.get(ep.part());
            javafx.geometry.Point2D imgP  = lm.get(ep.lmId());

            // Normalizar por centro y escala propios de cada fuente (dibujo / imagen)
            double ndx = (drawP.getX() - drawCenter.getX()) / drawScale;
            double ndy = (drawP.getY() - drawCenter.getY()) / drawScale;
            double nix = (imgP.getX()  - imgCenter.getX())  / imgScale;
            double niy = (imgP.getY()  - imgCenter.getY())  / imgScale;

            double dist = Math.hypot(ndx - nix, ndy - niy);
            totalScore += Math.max(0.0, 1.0 - (dist / tolerance));
            counted++;
        }

        return counted > 0 ? totalScore / counted : -1.0;
    }

    /**
     * Peso de cada parte en la similitud de esqueleto.
     * Las extremidades (brazos, manos, pies) ponderan más: un brazo en posición
     * incorrecta debe bajar más el score que la cabeza ligeramente desplazada.
     */
    private static double getSkeletonPartWeight(AnatomyPart part) {
        return switch (part) {
            case ARMS,      LEFT_ARM,      RIGHT_ARM      -> 2.0;
            case FOREARMS,  LEFT_FOREARM,  RIGHT_FOREARM  -> 2.0;
            case HANDS,     LEFT_HAND,     RIGHT_HAND     -> 2.5;
            case THIGHS,    LEFT_THIGH,    RIGHT_THIGH    -> 1.5;
            case CALVES,    LEFT_CALF,     RIGHT_CALF     -> 1.5;
            case FEET,      LEFT_FOOT,     RIGHT_FOOT     -> 1.5;
            default -> 1.0;   // HEAD, TORSO, SHOULDERS, HIPS y splits internos
        };
    }

    private static double headOnlySimilarity(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        if (!joints.containsKey(AnatomyPart.HEAD) || !lm.containsKey(0)) {
            return 0.0;
        }
        javafx.geometry.Point2D drawHead = joints.get(AnatomyPart.HEAD);
        javafx.geometry.Point2D imgHead = lm.get(0);
        double dist = Math.hypot(drawHead.getX() - imgHead.getX(), drawHead.getY() - imgHead.getY());
        // Tolerancia convertida a espacio 0-1
        double tolerance = PoseToleranceConfig.skeletonTolerance(AnatomyPart.HEAD) * 0.06;
        return Math.max(0.0, 1.0 - (dist / tolerance));
    }

    /**
     * Localiza el centro del cuerpo en la imagen usando la prioridad:
     * caderas (23,24) → hombros (11,12) → nariz (0).
     * Devuelve null si ningún landmark de referencia está disponible.
     */
    private static javafx.geometry.Point2D findImageCenter(
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        if (lm.containsKey(23) && lm.containsKey(24)) {
            return new javafx.geometry.Point2D(
                    (lm.get(23).getX() + lm.get(24).getX()) / 2.0,
                    (lm.get(23).getY() + lm.get(24).getY()) / 2.0);
        }
        if (lm.containsKey(11) && lm.containsKey(12)) {
            return new javafx.geometry.Point2D(
                    (lm.get(11).getX() + lm.get(12).getX()) / 2.0,
                    (lm.get(11).getY() + lm.get(12).getY()) / 2.0);
        }
        return lm.containsKey(0) ? lm.get(0) : null;
    }

    private static javafx.geometry.Point2D estimateJointCenter(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints) {
        if (joints == null || joints.isEmpty()) {
            return null;
        }
        double sx = 0;
        double sy = 0;
        for (var p : joints.values()) {
            sx += p.getX();
            sy += p.getY();
        }
        int n = joints.size();
        return new javafx.geometry.Point2D(sx / n, sy / n);
    }

    /**
     * Estima la escala del dibujo para la normalización del esqueleto.
     *
     * <p>Prioridad de referencia (de más a menos precisa):</p>
     * <ol>
     *   <li>LEFT_SHOULDER ↔ LEFT_HIP — misma referencia que MediaPipe usa en imagen</li>
     *   <li>RIGHT_SHOULDER ↔ RIGHT_HIP — idem lado derecho</li>
     *   <li>SHOULDERS ↔ HIPS — centroides combinados</li>
     *   <li>HEAD ↔ TORSO — fallback heredado</li>
     *   <li>Distancia máxima al centro — último recurso</li>
     * </ol>
     */
    private static double estimateDrawScale(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            javafx.geometry.Point2D center) {
        // Prioridad 1: bilateral hombro-cadera (misma referencia que la imagen)
        if (joints.containsKey(AnatomyPart.LEFT_SHOULDER) && joints.containsKey(AnatomyPart.LEFT_HIP))
            return joints.get(AnatomyPart.LEFT_SHOULDER).distance(joints.get(AnatomyPart.LEFT_HIP));
        if (joints.containsKey(AnatomyPart.RIGHT_SHOULDER) && joints.containsKey(AnatomyPart.RIGHT_HIP))
            return joints.get(AnatomyPart.RIGHT_SHOULDER).distance(joints.get(AnatomyPart.RIGHT_HIP));
        // Prioridad 2: centroides combinados hombros-caderas
        if (joints.containsKey(AnatomyPart.SHOULDERS) && joints.containsKey(AnatomyPart.HIPS))
            return joints.get(AnatomyPart.SHOULDERS).distance(joints.get(AnatomyPart.HIPS));
        // Prioridad 3: cabeza-torso (fallback)
        if (joints.containsKey(AnatomyPart.HEAD) && joints.containsKey(AnatomyPart.TORSO))
            return joints.get(AnatomyPart.HEAD).distance(joints.get(AnatomyPart.TORSO));
        double maxDist = 0.0;
        for (var p : joints.values()) maxDist = Math.max(maxDist, p.distance(center));
        return maxDist;
    }

    /**
     * Estima la escala de la imagen para la normalización del esqueleto.
     *
     * <p>Usa la misma referencia anatómica que {@link #estimateDrawScale}:
     * distancia hombro ↔ cadera del mismo lado. Esto garantiza que dibujo e imagen
     * queden normalizados en la misma escala y sus posiciones sean comparables.</p>
     */
    private static double estimateImageScale(
            java.util.Map<Integer, javafx.geometry.Point2D> lm,
            javafx.geometry.Point2D center) {
        // Prioridad 1: hombro izq ↔ cadera izq (misma referencia que el dibujo)
        if (lm.containsKey(11) && lm.containsKey(23))
            return lm.get(11).distance(lm.get(23));
        // Prioridad 2: hombro der ↔ cadera der
        if (lm.containsKey(12) && lm.containsKey(24))
            return lm.get(12).distance(lm.get(24));
        // Prioridad 3: promedio hombros ↔ promedio caderas
        if ((lm.containsKey(11) || lm.containsKey(12)) && (lm.containsKey(23) || lm.containsKey(24))) {
            javafx.geometry.Point2D sh = averageLandmark(lm,
                    lm.containsKey(11) && lm.containsKey(12) ? new Integer[]{11,12}
                    : lm.containsKey(11) ? new Integer[]{11} : new Integer[]{12});
            javafx.geometry.Point2D hip = averageLandmark(lm,
                    lm.containsKey(23) && lm.containsKey(24) ? new Integer[]{23,24}
                    : lm.containsKey(23) ? new Integer[]{23} : new Integer[]{24});
            if (sh != null && hip != null) return sh.distance(hip);
        }
        // Fallback: cabeza ↔ hombros (legacy)
        if (lm.containsKey(0) && (lm.containsKey(11) || lm.containsKey(12))) {
            javafx.geometry.Point2D shoulder = lm.containsKey(11) && lm.containsKey(12)
                    ? new javafx.geometry.Point2D((lm.get(11).getX() + lm.get(12).getX()) / 2.0,
                      (lm.get(11).getY() + lm.get(12).getY()) / 2.0)
                    : lm.getOrDefault(11, lm.get(12));
            return lm.get(0).distance(shoulder);
        }
        double maxDist = 0.0;
        for (var p : lm.values()) maxDist = Math.max(maxDist, p.distance(center));
        return maxDist;
    }

    private static double calculateContourSimilarity(PoseData drawingPose, PoseData imagePose) {
        try {
            var joints = drawingPose.getAllJoints();
            var lm = imagePose.getAllLandmarks();
            double[] drawBounds = getBoundsFromJoints(joints);
            double[] imgBounds = getBoundsFromLandmarks(lm);
            if (drawBounds == null || imgBounds == null) return 0.0;
            double drawRatio = drawBounds[2] / Math.max(1e-6, drawBounds[3]);
            double imgRatio = imgBounds[2] / Math.max(1e-6, imgBounds[3]);
            double diff = Math.abs(drawRatio - imgRatio);
            double sim = Math.max(0.0, 1.0 - (diff / Math.max(drawRatio, imgRatio)));
            return Math.max(0.0, Math.min(1.0, sim));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static double[] getBoundsFromJoints(java.util.Map<org.refcolor.buscareferencias.model.AnatomyPart, javafx.geometry.Point2D> joints) {
        if (joints == null || joints.isEmpty()) return null;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (var p : joints.values()) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
        }
        return new double[]{minX, minY, maxX - minX, maxY - minY};
    }

    private static double[] getBoundsFromLandmarks(java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        if (lm == null || lm.isEmpty()) return null;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (var p : lm.values()) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
        }
        return new double[]{minX, minY, maxX - minX, maxY - minY};
    }

    /**
     * Combina 7 componentes de similitud angular para mayor precisión.
     * Cada scorer devuelve double[]{score×weight, weight}; si no aplica, devuelve {0, 0}.
     *
     * <ul>
     *   <li>torso tilt        — cabeza respecto a hombros (w=3.0)</li>
     *   <li>arm angle         — dirección hombro→codo     (w=2.0)</li>
     *   <li>forearm angle     — dirección codo→muñeca     (w=1.5) ★ nuevo</li>
     *   <li>hands raised      — altura de manos (continuo, bilateral) (w=2.0)</li>
     *   <li>leg angle         — dirección cadera→rodilla  (w=1.5)</li>
     *   <li>calf angle        — dirección rodilla→tobillo (w=1.0) ★ nuevo</li>
     *   <li>stance width      — separación de pies normalizada (w=1.0) ★ nuevo</li>
     * </ul>
     */
    private static double calculateAngleBasedSimilarity(PoseData drawingPose, PoseData imagePose) {
        var joints = drawingPose.getAllJoints();
        var lm    = imagePose.getAllLandmarks();
        double totalScore = 0;
        double totalWeight = 0;
        for (double[] c : new double[][]{
                scoreTorsoTilt(joints, lm),
                scoreArmAngle(joints, lm),
                scoreForearmAngle(joints, lm),
                scoreHandsRaised(joints, lm),
                scoreLegAngle(joints, lm),
                scoreCalfAngle(joints, lm),
                scoreStanceWidth(joints, lm)}) {
            totalScore  += c[0];
            totalWeight += c[1];
        }
        return totalWeight > 0 ? totalScore / totalWeight : 0;
    }

    /** Inclinación del torso: cabeza respecto a hombros. */
    private static double[] scoreTorsoTilt(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        if (!joints.containsKey(AnatomyPart.HEAD) || !joints.containsKey(AnatomyPart.TORSO)) return new double[]{0, 0};
        if (!lm.containsKey(0) || !lm.containsKey(11) || !lm.containsKey(12)) return new double[]{0, 0};
        double drawAngle = Math.atan2(
                joints.get(AnatomyPart.HEAD).getY() - joints.get(AnatomyPart.TORSO).getY(),
                joints.get(AnatomyPart.HEAD).getX() - joints.get(AnatomyPart.TORSO).getX());
        double midX = (lm.get(11).getX() + lm.get(12).getX()) / 2;
        double midY = (lm.get(11).getY() + lm.get(12).getY()) / 2;
        double imgAngle = Math.atan2(lm.get(0).getY() - midY, lm.get(0).getX() - midX);
        double diff = Math.abs(drawAngle - imgAngle);
        while (diff > Math.PI) diff = 2 * Math.PI - diff;
        return new double[]{(1.0 - (diff / Math.PI)) * 3.0, 3.0};
    }

    /**
     * Compara la dirección del brazo en el dibujo con la del brazo en la foto.
     *
     * <p><b>Modo óptimo (con SHOULDER joints)</b>: usa el joint de hombro exacto
     * ({@code LEFT_SHOULDER}) como origen y el joint de antebrazo ({@code LEFT_FOREARM})
     * como destino. Esto compara la dirección hombro→codo en drawing e imagen usando
     * la misma referencia anatómica (hombro = LM 11, codo = LM 13).</p>
     *
     * <p><b>Modo bilateral sin hombros</b>: usa el centroide del brazo superior
     * ({@code LEFT_ARM}) como proxy del hombro, con la misma dirección a imagen.</p>
     *
     * <p><b>Fallback sin bilateral</b>: ángulo de codo clásico con joints combinados.</p>
     */
    private static double[] scoreArmAngle(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        double tolerance = PoseToleranceConfig.armAngleTolerance();
        double totalScore = 0;
        int counted = 0;

        // ── Modo óptimo: LEFT_SHOULDER → LEFT_FOREARM vs LM11 → LM13 ─────────
        // El joint SHOULDER está en la articulación exacta (igual que LM11),
        // por lo que el vector dibujo ≈ vector imagen para cualquier pose.
        if (joints.containsKey(AnatomyPart.LEFT_SHOULDER) && joints.containsKey(AnatomyPart.LEFT_FOREARM)
                && lm.containsKey(11) && lm.containsKey(13)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.LEFT_SHOULDER), joints.get(AnatomyPart.LEFT_FOREARM));
            double imgDir  = vectorAngleDeg(lm.get(11), lm.get(13));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (joints.containsKey(AnatomyPart.RIGHT_SHOULDER) && joints.containsKey(AnatomyPart.RIGHT_FOREARM)
                && lm.containsKey(12) && lm.containsKey(14)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.RIGHT_SHOULDER), joints.get(AnatomyPart.RIGHT_FOREARM));
            double imgDir  = vectorAngleDeg(lm.get(12), lm.get(14));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (counted > 0) return new double[]{(totalScore / counted) * 2.0, 2.0};

        // ── Bilateral sin hombros: LEFT_ARM (centroide) → LEFT_FOREARM ────────
        if (joints.containsKey(AnatomyPart.LEFT_ARM) && joints.containsKey(AnatomyPart.LEFT_FOREARM)
                && lm.containsKey(11) && lm.containsKey(13)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.LEFT_ARM), joints.get(AnatomyPart.LEFT_FOREARM));
            double imgDir  = vectorAngleDeg(lm.get(11), lm.get(13));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (joints.containsKey(AnatomyPart.RIGHT_ARM) && joints.containsKey(AnatomyPart.RIGHT_FOREARM)
                && lm.containsKey(12) && lm.containsKey(14)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.RIGHT_ARM), joints.get(AnatomyPart.RIGHT_FOREARM));
            double imgDir  = vectorAngleDeg(lm.get(12), lm.get(14));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (counted > 0) return new double[]{(totalScore / counted) * 2.0, 2.0};

        // ── Fallback: joints combinados (sin detección bilateral) ────────────
        if (!joints.containsKey(AnatomyPart.TORSO)
                || !joints.containsKey(AnatomyPart.ARMS)
                || !joints.containsKey(AnatomyPart.FOREARMS)) return new double[]{0, 0};
        double drawAngle = PoseData.calculateAngle(
                joints.get(AnatomyPart.TORSO), joints.get(AnatomyPart.ARMS), joints.get(AnatomyPart.FOREARMS));
        double best = 0;
        if (lm.containsKey(11) && lm.containsKey(13) && lm.containsKey(15))
            best = Math.max(best, angleMatchScore(drawAngle,
                    PoseData.calculateAngle(lm.get(11), lm.get(13), lm.get(15)), tolerance));
        if (lm.containsKey(12) && lm.containsKey(14) && lm.containsKey(16))
            best = Math.max(best, angleMatchScore(drawAngle,
                    PoseData.calculateAngle(lm.get(12), lm.get(14), lm.get(16)), tolerance));
        return best > 0 ? new double[]{best * 2.0, 2.0} : new double[]{0, 0};
    }

    /**
     * Compara la dirección del antebrazo (codo→muñeca) en el dibujo con la de la foto.
     *
     * <p><b>Modo óptimo (bilateral)</b>: usa el centroide del antebrazo dibujado
     * ({@code LEFT_FOREARM}) como origen y la muñeca ({@code LEFT_HAND}) como destino.
     * Esto equivale a la dirección LM13→LM15 en la imagen.</p>
     *
     * <p><b>Fallback combinado</b>: usa {@code FOREARMS→HANDS} si no hay bilaterales.</p>
     */
    private static double[] scoreForearmAngle(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        double tolerance = PoseToleranceConfig.armAngleTolerance();
        double totalScore = 0;
        int counted = 0;

        // ── Bilateral: LEFT_FOREARM → LEFT_HAND vs LM13 → LM15 ──────────────
        if (joints.containsKey(AnatomyPart.LEFT_FOREARM) && joints.containsKey(AnatomyPart.LEFT_HAND)
                && lm.containsKey(13) && lm.containsKey(15)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.LEFT_FOREARM), joints.get(AnatomyPart.LEFT_HAND));
            double imgDir  = vectorAngleDeg(lm.get(13), lm.get(15));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (joints.containsKey(AnatomyPart.RIGHT_FOREARM) && joints.containsKey(AnatomyPart.RIGHT_HAND)
                && lm.containsKey(14) && lm.containsKey(16)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.RIGHT_FOREARM), joints.get(AnatomyPart.RIGHT_HAND));
            double imgDir  = vectorAngleDeg(lm.get(14), lm.get(16));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (counted > 0) return new double[]{(totalScore / counted) * 1.5, 1.5};

        // ── Fallback: combinado FOREARMS → HANDS ─────────────────────────────
        if (joints.containsKey(AnatomyPart.FOREARMS) && joints.containsKey(AnatomyPart.HANDS)
                && (lm.containsKey(13) || lm.containsKey(14))
                && (lm.containsKey(15) || lm.containsKey(16))) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.FOREARMS), joints.get(AnatomyPart.HANDS));
            double best = 0;
            if (lm.containsKey(13) && lm.containsKey(15))
                best = Math.max(best, angleMatchScore(drawDir, vectorAngleDeg(lm.get(13), lm.get(15)), tolerance));
            if (lm.containsKey(14) && lm.containsKey(16))
                best = Math.max(best, angleMatchScore(drawDir, vectorAngleDeg(lm.get(14), lm.get(16)), tolerance));
            if (best > 0) return new double[]{best * 1.5, 1.5};
        }
        return new double[]{0, 0};
    }

    /** Ángulo en grados del vector desde {@code from} hasta {@code to}. */
    private static double vectorAngleDeg(javafx.geometry.Point2D from, javafx.geometry.Point2D to) {
        return Math.toDegrees(Math.atan2(to.getY() - from.getY(), to.getX() - from.getX()));
    }

    /**
     * Compara la altura de cada mano respecto al cuerpo — puntuación continua y bilateral.
     *
     * <p>Para cada mano (izquierda y derecha por separado) calcula la altura normalizada
     * relativa al rango vertical cabeza–cadera. El score es continuo: una mano a la
     * misma altura relativa que en la foto puntúa 1.0; un desvío del 50 % del rango
     * corporal puntúa 0.0. Esto distingue con precisión: manos sobre la cabeza,
     * a la altura de los hombros, a la cadera o por debajo.</p>
     *
     * <p>Soporta joints bilaterales ({@code LEFT_HAND} / {@code RIGHT_HAND}) y el
     * centroide combinado ({@code HANDS}) como fallback.</p>
     */
    private static double[] scoreHandsRaised(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        if (!joints.containsKey(AnatomyPart.HEAD)) return new double[]{0, 0};
        if (!lm.containsKey(15) && !lm.containsKey(16)) return new double[]{0, 0};

        // ── Referencias verticales del dibujo ─────────────────────────────
        double drawTopY = joints.get(AnatomyPart.HEAD).getY();
        double drawBotY = getDrawHipCenterY(joints, drawTopY + 0.5);
        double bodyRangeDraw = Math.max(Math.abs(drawBotY - drawTopY), 0.05);

        // ── Referencias verticales de la imagen ───────────────────────────
        double imgTopY  = lm.containsKey(0) ? lm.get(0).getY() : 0.1;
        double imgBotY  = getImageHipCenterY(lm, imgTopY + 0.5);
        double bodyRangeImg = Math.max(Math.abs(imgBotY - imgTopY), 0.05);

        double totalScore = 0;
        int counted = 0;

        // ── Mano izquierda: LEFT_HAND (o HANDS) vs LM15 ──────────────────
        javafx.geometry.Point2D drawLH = joints.containsKey(AnatomyPart.LEFT_HAND)
                ? joints.get(AnatomyPart.LEFT_HAND) : joints.get(AnatomyPart.HANDS);
        if (drawLH != null && lm.containsKey(15)) {
            double drawNorm = (drawLH.getY() - drawTopY) / bodyRangeDraw;
            double imgNorm  = (lm.get(15).getY() - imgTopY) / bodyRangeImg;
            totalScore += Math.max(0.0, 1.0 - (Math.abs(drawNorm - imgNorm) / 0.5));
            counted++;
        }
        // ── Mano derecha: RIGHT_HAND (o HANDS) vs LM16 ───────────────────
        javafx.geometry.Point2D drawRH = joints.containsKey(AnatomyPart.RIGHT_HAND)
                ? joints.get(AnatomyPart.RIGHT_HAND) : joints.get(AnatomyPart.HANDS);
        if (drawRH != null && lm.containsKey(16)) {
            double drawNorm = (drawRH.getY() - drawTopY) / bodyRangeDraw;
            double imgNorm  = (lm.get(16).getY() - imgTopY) / bodyRangeImg;
            totalScore += Math.max(0.0, 1.0 - (Math.abs(drawNorm - imgNorm) / 0.5));
            counted++;
        }

        if (counted == 0) return new double[]{0, 0};
        return new double[]{(totalScore / counted) * 2.0, 2.0};
    }

    /**
     * Ángulo de la pierna: dirección cadera→rodilla por lado.
     *
     * <p><b>Modo óptimo (con HIP joints)</b>: usa el joint de cadera exacto
     * ({@code LEFT_HIP}) como origen y el centroide de pantorrilla ({@code LEFT_CALF})
     * como destino. Compara cadera→punto-medio-tibia en dibujo e imagen.</p>
     *
     * <p><b>Modo bilateral sin caderas</b>: usa el centroide del muslo como proxy.</p>
     *
     * <p><b>Fallback</b>: ángulo de rodilla clásico con joints combinados.</p>
     */
    private static double[] scoreLegAngle(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        double tolerance = PoseToleranceConfig.legAngleTolerance();
        double totalScore = 0;
        int counted = 0;

        // ── Modo óptimo: LEFT_HIP → LEFT_CALF vs LM23 → LM25 ────────────────
        if (joints.containsKey(AnatomyPart.LEFT_HIP) && joints.containsKey(AnatomyPart.LEFT_CALF)
                && lm.containsKey(23) && lm.containsKey(25)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.LEFT_HIP), joints.get(AnatomyPart.LEFT_CALF));
            double imgDir  = vectorAngleDeg(lm.get(23), lm.get(25));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (joints.containsKey(AnatomyPart.RIGHT_HIP) && joints.containsKey(AnatomyPart.RIGHT_CALF)
                && lm.containsKey(24) && lm.containsKey(26)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.RIGHT_HIP), joints.get(AnatomyPart.RIGHT_CALF));
            double imgDir  = vectorAngleDeg(lm.get(24), lm.get(26));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (counted > 0) return new double[]{(totalScore / counted) * 1.5, 1.5};

        // ── Bilateral sin caderas: centroide muslo → centroide pantorrilla ────
        if (joints.containsKey(AnatomyPart.LEFT_THIGH) && joints.containsKey(AnatomyPart.LEFT_CALF)
                && lm.containsKey(23) && lm.containsKey(25)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.LEFT_THIGH), joints.get(AnatomyPart.LEFT_CALF));
            double imgDir  = vectorAngleDeg(lm.get(23), lm.get(25));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (joints.containsKey(AnatomyPart.RIGHT_THIGH) && joints.containsKey(AnatomyPart.RIGHT_CALF)
                && lm.containsKey(24) && lm.containsKey(26)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.RIGHT_THIGH), joints.get(AnatomyPart.RIGHT_CALF));
            double imgDir  = vectorAngleDeg(lm.get(24), lm.get(26));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (counted > 0) return new double[]{(totalScore / counted) * 1.5, 1.5};

        // ── Fallback: joints combinados ───────────────────────────────────────
        if (!joints.containsKey(AnatomyPart.THIGHS)
                || !joints.containsKey(AnatomyPart.CALVES)
                || !joints.containsKey(AnatomyPart.FEET)) return new double[]{0, 0};
        double drawAngle = PoseData.calculateAngle(
                joints.get(AnatomyPart.THIGHS), joints.get(AnatomyPart.CALVES), joints.get(AnatomyPart.FEET));
        double best = 0;
        if (lm.containsKey(23) && lm.containsKey(25) && lm.containsKey(27))
            best = Math.max(best, angleMatchScore(drawAngle,
                    PoseData.calculateAngle(lm.get(23), lm.get(25), lm.get(27)), tolerance));
        if (lm.containsKey(24) && lm.containsKey(26) && lm.containsKey(28))
            best = Math.max(best, angleMatchScore(drawAngle,
                    PoseData.calculateAngle(lm.get(24), lm.get(26), lm.get(28)), tolerance));
        return best > 0 ? new double[]{best * 1.5, 1.5} : new double[]{0, 0};
    }

    /**
     * Compara la dirección de la pantorrilla (rodilla→tobillo) en el dibujo con la foto.
     *
     * <p><b>Modo óptimo (bilateral)</b>: usa el centroide de la pantorrilla dibujada
     * ({@code LEFT_CALF}) como origen y el tobillo ({@code LEFT_FOOT}) como destino,
     * comparando con LM25→LM27 en la imagen. Distingue piernas extendidas de dobladas.</p>
     *
     * <p><b>Fallback combinado</b>: usa {@code CALVES→FEET} si no hay bilaterales.</p>
     */
    private static double[] scoreCalfAngle(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        double tolerance = PoseToleranceConfig.legAngleTolerance();
        double totalScore = 0;
        int counted = 0;

        // ── Bilateral: LEFT_CALF → LEFT_FOOT vs LM25 → LM27 ─────────────────
        if (joints.containsKey(AnatomyPart.LEFT_CALF) && joints.containsKey(AnatomyPart.LEFT_FOOT)
                && lm.containsKey(25) && lm.containsKey(27)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.LEFT_CALF), joints.get(AnatomyPart.LEFT_FOOT));
            double imgDir  = vectorAngleDeg(lm.get(25), lm.get(27));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (joints.containsKey(AnatomyPart.RIGHT_CALF) && joints.containsKey(AnatomyPart.RIGHT_FOOT)
                && lm.containsKey(26) && lm.containsKey(28)) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.RIGHT_CALF), joints.get(AnatomyPart.RIGHT_FOOT));
            double imgDir  = vectorAngleDeg(lm.get(26), lm.get(28));
            totalScore += angleMatchScore(drawDir, imgDir, tolerance);
            counted++;
        }
        if (counted > 0) return new double[]{(totalScore / counted) * 1.0, 1.0};

        // ── Fallback: combinado CALVES → FEET ────────────────────────────────
        if (joints.containsKey(AnatomyPart.CALVES) && joints.containsKey(AnatomyPart.FEET)
                && (lm.containsKey(25) || lm.containsKey(26))
                && (lm.containsKey(27) || lm.containsKey(28))) {
            double drawDir = vectorAngleDeg(joints.get(AnatomyPart.CALVES), joints.get(AnatomyPart.FEET));
            double best = 0;
            if (lm.containsKey(25) && lm.containsKey(27))
                best = Math.max(best, angleMatchScore(drawDir, vectorAngleDeg(lm.get(25), lm.get(27)), tolerance));
            if (lm.containsKey(26) && lm.containsKey(28))
                best = Math.max(best, angleMatchScore(drawDir, vectorAngleDeg(lm.get(26), lm.get(28)), tolerance));
            if (best > 0) return new double[]{best * 1.0, 1.0};
        }
        return new double[]{0, 0};
    }

    /**
     * Compara el ancho de postura (separación de pies normalizada por el ancho de caderas).
     *
     * <p>Distingue posturas cerradas (pies juntos) de posturas abiertas en A (pies separados).
     * La separación se normaliza por el ancho de caderas para ser invariante a la escala.</p>
     */
    private static double[] scoreStanceWidth(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        // Necesita ambos pies en el dibujo y en la imagen
        if (!joints.containsKey(AnatomyPart.LEFT_FOOT) || !joints.containsKey(AnatomyPart.RIGHT_FOOT))
            return new double[]{0, 0};
        if (!lm.containsKey(27) || !lm.containsKey(28)) return new double[]{0, 0};

        double drawFootSpread = Math.abs(
                joints.get(AnatomyPart.LEFT_FOOT).getX() - joints.get(AnatomyPart.RIGHT_FOOT).getX());
        double imgFootSpread = Math.abs(lm.get(27).getX() - lm.get(28).getX());

        double drawRef = getDrawHipWidth(joints);
        double imgRef  = getImageHipWidth(lm);

        double drawRatio = drawFootSpread / Math.max(drawRef, 0.05);
        double imgRatio  = imgFootSpread  / Math.max(imgRef,  0.05);

        // Tolerancia: ±1.5 anchos de cadera
        double sim = Math.max(0.0, 1.0 - (Math.abs(drawRatio - imgRatio) / 1.5));
        return new double[]{sim * 1.0, 1.0};
    }

    // ── Helpers de referencia corporal ────────────────────────────────────────

    /** Ancho de caderas en el dibujo (fallback: hombros × 0.8, luego valor por defecto). */
    private static double getDrawHipWidth(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints) {
        if (joints.containsKey(AnatomyPart.LEFT_HIP) && joints.containsKey(AnatomyPart.RIGHT_HIP))
            return Math.abs(joints.get(AnatomyPart.LEFT_HIP).getX() - joints.get(AnatomyPart.RIGHT_HIP).getX());
        if (joints.containsKey(AnatomyPart.LEFT_SHOULDER) && joints.containsKey(AnatomyPart.RIGHT_SHOULDER))
            return Math.abs(joints.get(AnatomyPart.LEFT_SHOULDER).getX() - joints.get(AnatomyPart.RIGHT_SHOULDER).getX()) * 0.8;
        return 0.15;
    }

    /** Ancho de caderas en la imagen MediaPipe (fallback: hombros × 0.8). */
    private static double getImageHipWidth(
            java.util.Map<Integer, javafx.geometry.Point2D> lm) {
        if (lm.containsKey(23) && lm.containsKey(24))
            return Math.abs(lm.get(23).getX() - lm.get(24).getX());
        if (lm.containsKey(11) && lm.containsKey(12))
            return Math.abs(lm.get(11).getX() - lm.get(12).getX()) * 0.8;
        return 0.15;
    }

    /** Y del centro de caderas en el dibujo (para normalización vertical de manos). */
    private static double getDrawHipCenterY(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints, double defaultVal) {
        if (joints.containsKey(AnatomyPart.LEFT_HIP) && joints.containsKey(AnatomyPart.RIGHT_HIP))
            return (joints.get(AnatomyPart.LEFT_HIP).getY() + joints.get(AnatomyPart.RIGHT_HIP).getY()) / 2.0;
        if (joints.containsKey(AnatomyPart.HIPS))  return joints.get(AnatomyPart.HIPS).getY();
        if (joints.containsKey(AnatomyPart.TORSO)) return joints.get(AnatomyPart.TORSO).getY();
        return defaultVal;
    }

    /** Y del centro de caderas en la imagen MediaPipe. */
    private static double getImageHipCenterY(
            java.util.Map<Integer, javafx.geometry.Point2D> lm, double defaultVal) {
        if (lm.containsKey(23) && lm.containsKey(24))
            return (lm.get(23).getY() + lm.get(24).getY()) / 2.0;
        if (lm.containsKey(23)) return lm.get(23).getY();
        if (lm.containsKey(24)) return lm.get(24).getY();
        return defaultVal;
    }

    /** Normaliza la diferencia de ángulo (0-360) a un score [0,1] según tolerancia. */
    private static double angleMatchScore(double drawAngle, double imgAngle, double tolerance) {
        double diff = Math.abs(drawAngle - imgAngle);
        if (diff > 180) diff = 360 - diff;
        return Math.max(0.0, 1.0 - (diff / tolerance));
    }

    private static double calculateCosineSimilarity(List<Double> vec1, List<Double> vec2) {
        if (vec1 == null || vec2 == null || vec1.size() != vec2.size() || vec1.isEmpty()) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vec1.size(); i++) {
            double a = vec1.get(i);
            double b = vec2.get(i);
            dotProduct += a * b;
            normA += a * a;  // x*x evita boxing y es más rápido que Math.pow(x,2) (P2)
            normB += b * b;
        }
        if (normA <= 0 || normB <= 0) return 0.0;
        double similarity = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        if (Double.isNaN(similarity)) return 0.0;
        // Cosine similarity va de -1 a 1, lo normalizamos a [0, 1]
        return Math.max(0.0, Math.min(1.0, (similarity + 1.0) / 2.0));
    }

}



