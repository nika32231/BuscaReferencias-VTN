package org.refcolor.buscareferencias.service;

import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;
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

        public static synchronized void startSessionCache() {
            try { MediaPipeService.clearSessionPoseCache(); } catch (Exception ignored) {}
        }

        public static synchronized void clearSessionCache() {
            try { MediaPipeService.clearSessionPoseCache(); } catch (Exception ignored) {}
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
        try { SESSION_POSE_CACHE.clear(); } catch (Exception ignored) {}
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
     * Calcula la similitud final combinando varios componentes reales.
     * Fórmula base: 0.45 cosine + 0.25 pose angles + 0.20 skeleton + 0.10 contour
     * Si no hay embeddings, redistribuye los pesos: 0.50 pose angles + 0.35 skeleton + 0.15 contour
     */
    public static double calculateSimilarity(PoseData drawingPose, PoseData imagePose) {
        if (imagePose == null || drawingPose == null) return 0.0;
        if (imagePose.getAllLandmarks().isEmpty() || drawingPose.getAllJoints().isEmpty()) return 0.0;

        int jointCount = drawingPose.getAllJoints().size();
        double partial = calculatePartialDrawingSimilarity(drawingPose, imagePose);
        double headSim = headOnlySimilarity(drawingPose.getAllJoints(), imagePose.getAllLandmarks());

        double cosine = calculateCosineSimilarity(drawingPose.getEmbedding(), imagePose.getEmbedding());
        double angles = calculateAngleBasedSimilarity(drawingPose, imagePose);
        double skeleton = calculateSkeletonSimilarity(drawingPose, imagePose);
        double contour = calculateContourSimilarity(drawingPose, imagePose);

        double coverageFactor = calculateCoverageFactor(drawingPose, imagePose);

        double finalScore;
        boolean hasEmbeddings = cosine > 0.0;
        if (jointCount <= 4) {
            // Dibujo parcial (p. ej. solo cabeza): posición y cobertura
            finalScore = Math.max(partial, headSim) * coverageFactor;
        } else if (hasEmbeddings) {
            finalScore = ((0.45 * cosine) + (0.25 * angles) + (0.20 * skeleton) + (0.10 * contour)) * coverageFactor;
        } else {
            finalScore = (0.50 * angles) + (0.35 * skeleton) + (0.15 * contour);
            finalScore = Math.max(finalScore, partial * 0.25);
            finalScore *= coverageFactor;
        }
        finalScore = Math.max(0.0, Math.min(1.0, finalScore));
        logger.info("[SIMILARITY] joints={} partial={} head={} cosine={} angles={} skeleton={} contour={} coverage={} final={}",
                jointCount,
                String.format("%.4f", partial), String.format("%.4f", headSim),
                String.format("%.4f", cosine), String.format("%.4f", angles),
                String.format("%.4f", skeleton), String.format("%.4f", contour),
                String.format("%.4f", coverageFactor),
                String.format("%.4f", finalScore));
        return finalScore;
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
        if (joints.containsKey(AnatomyPart.TORSO)) regions.add("UPPER");
        if (joints.containsKey(AnatomyPart.ARMS) || joints.containsKey(AnatomyPart.FOREARMS) || joints.containsKey(AnatomyPart.HANDS)) regions.add("ARMS");
        if (joints.containsKey(AnatomyPart.THIGHS) || joints.containsKey(AnatomyPart.CALVES) || joints.containsKey(AnatomyPart.FEET)) regions.add("LOWER");
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

        // La foto muestra más de lo dibujado → penalización fuerte
        // Faltan partes que se dibujaron → penalización leve
        double penalty = (extraInPhoto * 0.7 + missingFromPhoto * 0.15) / 4.0;
        return Math.max(0.1, 1.0 - penalty);
    }

    private static java.util.Map<AnatomyPart, Integer[]> partToLandmarkIds() {
        java.util.Map<AnatomyPart, Integer[]> mapping = new java.util.HashMap<>();
        mapping.put(AnatomyPart.HEAD, new Integer[]{0});
        mapping.put(AnatomyPart.ARMS, new Integer[]{11, 12});
        mapping.put(AnatomyPart.FOREARMS, new Integer[]{13, 14});
        mapping.put(AnatomyPart.HANDS, new Integer[]{15, 16});
        mapping.put(AnatomyPart.THIGHS, new Integer[]{23, 24});
        mapping.put(AnatomyPart.CALVES, new Integer[]{25, 26});
        mapping.put(AnatomyPart.FEET, new Integer[]{27, 28});
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

    private static double calculateSkeletonSimilarity(PoseData drawingPose, PoseData imagePose) {
        try {
            var joints = drawingPose.getAllJoints();
            var lm = imagePose.getAllLandmarks();

            javafx.geometry.Point2D drawCenter = joints.get(org.refcolor.buscareferencias.model.AnatomyPart.TORSO);
            if (drawCenter == null) {
                drawCenter = estimateJointCenter(joints);
            }

            javafx.geometry.Point2D imgCenter = null;
            if (lm.containsKey(23) && lm.containsKey(24)) {
                imgCenter = new javafx.geometry.Point2D((lm.get(23).getX() + lm.get(24).getX()) / 2.0, (lm.get(23).getY() + lm.get(24).getY()) / 2.0);
            } else if (lm.containsKey(11) && lm.containsKey(12)) {
                imgCenter = new javafx.geometry.Point2D((lm.get(11).getX() + lm.get(12).getX()) / 2.0, (lm.get(11).getY() + lm.get(12).getY()) / 2.0);
            } else if (lm.containsKey(0)) {
                imgCenter = lm.get(0);
            }

            if (drawCenter == null || imgCenter == null) {
                return headOnlySimilarity(joints, lm);
            }

            double drawScale = estimateDrawScale(joints, drawCenter);
            double imgScale = estimateImageScale(lm, imgCenter);
            if (drawScale <= 0) drawScale = 0.15;
            if (imgScale <= 0) imgScale = 0.15;

            java.util.Map<org.refcolor.buscareferencias.model.AnatomyPart, Integer[]> mapping = partToLandmarkIds();

            double total = 0.0;
            int counted = 0;
            for (var entry : mapping.entrySet()) {
                var part = entry.getKey();
                if (!joints.containsKey(part)) continue;
                javafx.geometry.Point2D drawP = joints.get(part);
                Integer[] ids = entry.getValue();
                javafx.geometry.Point2D imgP = null;
                int found = 0;
                double sumX = 0, sumY = 0;
                for (int id : ids) {
                    if (lm.containsKey(id)) {
                        sumX += lm.get(id).getX();
                        sumY += lm.get(id).getY();
                        found++;
                    }
                }
                if (found == 0) continue;
                imgP = new javafx.geometry.Point2D(sumX / found, sumY / found);

                double ndx = (drawP.getX() - drawCenter.getX()) / drawScale;
                double ndy = (drawP.getY() - drawCenter.getY()) / drawScale;
                double nix = (imgP.getX() - imgCenter.getX()) / imgScale;
                double niy = (imgP.getY() - imgCenter.getY()) / imgScale;

                double dist = Math.hypot(ndx - nix, ndy - niy);
                double tolerance = PoseToleranceConfig.skeletonTolerance(part);
                double sim = Math.max(0.0, 1.0 - (dist / tolerance));
                total += sim;
                counted++;
            }
            if (counted == 0) {
                return headOnlySimilarity(joints, lm);
            }
            double similarity = total / counted;
            similarity *= Math.min(1.0, Math.max(0.35, counted / 8.0));
            return similarity;
        } catch (Exception e) {
            logger.debug("Error calculating skeleton similarity: {}", e.toString());
            return 0.0;
        }
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

    private static double estimateDrawScale(
            java.util.Map<AnatomyPart, javafx.geometry.Point2D> joints,
            javafx.geometry.Point2D center) {
        if (joints.containsKey(AnatomyPart.HEAD) && joints.containsKey(AnatomyPart.TORSO)) {
            return joints.get(AnatomyPart.HEAD).distance(joints.get(AnatomyPart.TORSO));
        }
        double maxDist = 0.0;
        for (var p : joints.values()) {
            maxDist = Math.max(maxDist, p.distance(center));
        }
        return maxDist;
    }

    private static double estimateImageScale(
            java.util.Map<Integer, javafx.geometry.Point2D> lm,
            javafx.geometry.Point2D center) {
        if (lm.containsKey(0) && (lm.containsKey(11) || lm.containsKey(12))) {
            javafx.geometry.Point2D shoulder = lm.containsKey(11) && lm.containsKey(12)
                    ? new javafx.geometry.Point2D((lm.get(11).getX() + lm.get(12).getX()) / 2.0,
                    (lm.get(11).getY() + lm.get(12).getY()) / 2.0)
                    : lm.getOrDefault(11, lm.get(12));
            return lm.get(0).distance(shoulder);
        }
        double maxDist = 0.0;
        for (var p : lm.values()) {
            maxDist = Math.max(maxDist, p.distance(center));
        }
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

    private static double calculateAngleBasedSimilarity(PoseData drawingPose, PoseData imagePose) {
        double totalScore = 0;
        double totalWeight = 0;
        var lm = imagePose.getAllLandmarks();
        var joints = drawingPose.getAllJoints();
        
        // 1. Inclinación del Torso
        if (joints.containsKey(AnatomyPart.HEAD) && joints.containsKey(AnatomyPart.TORSO)) {
            double drawTorsoAngle = Math.atan2(joints.get(AnatomyPart.HEAD).getY() - joints.get(AnatomyPart.TORSO).getY(),
                                               joints.get(AnatomyPart.HEAD).getX() - joints.get(AnatomyPart.TORSO).getX());
            if (lm.containsKey(0) && lm.containsKey(11) && lm.containsKey(12)) {
                double midShoulderX = (lm.get(11).getX() + lm.get(12).getX()) / 2;
                double midShoulderY = (lm.get(11).getY() + lm.get(12).getY()) / 2;
                double imgTorsoAngle = Math.atan2(lm.get(0).getY() - midShoulderY, lm.get(0).getX() - midShoulderX);
                double diff = Math.abs(drawTorsoAngle - imgTorsoAngle);
                while (diff > Math.PI) diff = 2 * Math.PI - diff;
                totalScore += (1.0 - (diff / Math.PI)) * 3.0;
                totalWeight += 3.0;
            }
        }

        // 2. Ángulos de los brazos
        if (joints.containsKey(AnatomyPart.TORSO) && joints.containsKey(AnatomyPart.ARMS) && joints.containsKey(AnatomyPart.FOREARMS)) {
            double drawArmAngle = PoseData.calculateAngle(joints.get(AnatomyPart.TORSO), joints.get(AnatomyPart.ARMS), joints.get(AnatomyPart.FOREARMS));
            double bestArmScore = 0;
            if (lm.containsKey(11) && lm.containsKey(13) && lm.containsKey(15)) {
                double imgArmL = PoseData.calculateAngle(lm.get(11), lm.get(13), lm.get(15));
                double diff = Math.abs(drawArmAngle - imgArmL);
                if (diff > 180) diff = 360 - diff;
                bestArmScore = Math.max(bestArmScore, 1.0 - (diff / PoseToleranceConfig.armAngleTolerance()));
            }
            if (lm.containsKey(12) && lm.containsKey(14) && lm.containsKey(16)) {
                double imgArmR = PoseData.calculateAngle(lm.get(12), lm.get(14), lm.get(16));
                double diff = Math.abs(drawArmAngle - imgArmR);
                if (diff > 180) diff = 360 - diff;
                bestArmScore = Math.max(bestArmScore, 1.0 - (diff / PoseToleranceConfig.armAngleTolerance()));
            }
            if (bestArmScore > 0) {
                totalScore += bestArmScore * 2.0;
                totalWeight += 2.0;
            }
        }

        // 3. Brazos levantados
        if (joints.containsKey(AnatomyPart.HEAD) && joints.containsKey(AnatomyPart.HANDS)) {
            boolean drawHandsUp = joints.get(AnatomyPart.HANDS).getY() < joints.get(AnatomyPart.HEAD).getY();
            if (lm.containsKey(15) || lm.containsKey(16)) {
                double headY = lm.containsKey(0) ? lm.get(0).getY() : 0.2;
                boolean imgHandsUp = (lm.containsKey(15) && lm.get(15).getY() < headY) || (lm.containsKey(16) && lm.get(16).getY() < headY);
                if (drawHandsUp == imgHandsUp) totalScore += 2.0;
                totalWeight += 2.0;
            }
        }

        // 4. Piernas
        if (joints.containsKey(AnatomyPart.THIGHS) && joints.containsKey(AnatomyPart.CALVES) && joints.containsKey(AnatomyPart.FEET)) {
             double drawLegAngle = PoseData.calculateAngle(joints.get(AnatomyPart.THIGHS), joints.get(AnatomyPart.CALVES), joints.get(AnatomyPart.FEET));
             double bestLegScore = 0;
             if (lm.containsKey(23) && lm.containsKey(25) && lm.containsKey(27)) {
                 double imgLegL = PoseData.calculateAngle(lm.get(23), lm.get(25), lm.get(27));
                 double diff = Math.abs(drawLegAngle - imgLegL);
                 if (diff > 180) diff = 360 - diff;
                 bestLegScore = Math.max(bestLegScore, 1.0 - (diff / PoseToleranceConfig.legAngleTolerance()));
             }
             if (lm.containsKey(24) && lm.containsKey(26) && lm.containsKey(28)) {
                 double imgLegR = PoseData.calculateAngle(lm.get(24), lm.get(26), lm.get(28));
                 double diff = Math.abs(drawLegAngle - imgLegR);
                 if (diff > 180) diff = 360 - diff;
                 bestLegScore = Math.max(bestLegScore, 1.0 - (diff / PoseToleranceConfig.legAngleTolerance()));
             }
             if (bestLegScore > 0) {
                 totalScore += bestLegScore * 1.5;
                 totalWeight += 1.5;
             }
        }
        return totalWeight > 0 ? totalScore / totalWeight : 0;
    }

    private static double calculateCosineSimilarity(List<Double> vec1, List<Double> vec2) {
        if (vec1 == null || vec2 == null || vec1.size() != vec2.size() || vec1.isEmpty()) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vec1.size(); i++) {
            dotProduct += vec1.get(i) * vec2.get(i);
            normA += Math.pow(vec1.get(i), 2);
            normB += Math.pow(vec2.get(i), 2);
        }
        if (normA <= 0 || normB <= 0) return 0.0;
        double similarity = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        if (Double.isNaN(similarity)) return 0.0;
        // Cosine similarity va de -1 a 1, lo normalizamos a [0, 1]
        return Math.max(0.0, Math.min(1.0, (similarity + 1.0) / 2.0));
    }

}



