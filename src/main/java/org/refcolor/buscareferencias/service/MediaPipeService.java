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
     * Analiza una imagen (URL o local) para extraer la pose usando MediaPipe vía Python.
     */
    public static PoseData analyzeImage(String imageSource) {
        // 1) Intentar caché de sesión en memoria
        if (imageSource != null && SESSION_POSE_CACHE.containsKey(imageSource)) {
            logger.info("[MEDIAPIPE] Usando pose cacheada en sesión para: {}", imageSource);
            return SESSION_POSE_CACHE.get(imageSource);
        }

        // 2) Intentamos buscar en caché de base de datos
        PoseData cachedPose = DatabaseManager.getCachedPose(imageSource);
        if (cachedPose != null) {
            logger.info("[MEDIAPIPE] Usando pose cacheada en BD para: {}", imageSource);
            // Guardamos también en caché de sesión para evitar múltiples lecturas
            SESSION_POSE_CACHE.put(imageSource, cachedPose);
            return cachedPose;
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
                    PoseData pose = new PoseData();
                    JSONObject json = new JSONObject(outStr);
                    
                    // Logs detallados para validación (Hito 6)
                    if (json.has("debug")) {
                        JSONObject debug = json.getJSONObject("debug");
                        logger.info("[DEBUG_MP] Imagen: {}, Puntos: {}, Confianza: {}", 
                            imageSource,
                            debug.getInt("points_found"), 
                            String.format("%.2f", debug.getDouble("avg_confidence")));
                        
                        if (debug.has("hu_moments")) {
                             logger.info("[DEBUG_MP] Hu Moments: {}", debug.get("hu_moments"));
                        }
                    }

                    // Parsear Landmarks
                    JSONObject landmarks = json.getJSONObject("landmarks");
                    for (String key : landmarks.keySet()) {
                        int id = Integer.parseInt(key);
                        JSONObject lm = landmarks.getJSONObject(key);
                        pose.addLandmark(id, lm.getDouble("x"), lm.getDouble("y"));
                    }

                    // Parsear Embedding
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
                            try {
                                pose.putPoseAngle(key, poseAngles.getDouble(key));
                            } catch (Exception ignored) {
                                // Si un campo viene mal formado, lo ignoramos sin romper el flujo.
                            }
                        }
                    }
                    
                    // Guardar en caché de sesión para evitar re-análisis durante la búsqueda
                    try { SESSION_POSE_CACHE.put(imageSource, pose); } catch (Exception ignored) {}
                    return pose;
                }
            }
            logger.warn("MediaPipe falló o no detectó nada. Se devuelve pose vacía. exitCode={} error={}",
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
     * Calcula la similitud final combinando varios componentes reales.
     * Fórmula base: 0.45 cosine + 0.25 pose angles + 0.20 skeleton + 0.10 contour
     * Si no hay embeddings, redistribuye los pesos: 0.50 pose angles + 0.35 skeleton + 0.15 contour
     */
    public static double calculateSimilarity(PoseData drawingPose, PoseData imagePose) {
        if (imagePose == null || drawingPose == null) return 0.0;
        if (imagePose.getAllLandmarks().isEmpty() || drawingPose.getAllJoints().isEmpty()) return 0.0;

        double cosine = calculateCosineSimilarity(drawingPose.getEmbedding(), imagePose.getEmbedding());
        double angles = calculateAngleBasedSimilarity(drawingPose, imagePose);
        double skeleton = calculateSkeletonSimilarity(drawingPose, imagePose);
        double contour = calculateContourSimilarity(drawingPose, imagePose);

        double finalScore;
        boolean hasEmbeddings = cosine > 0.0;
        if (hasEmbeddings) {
            // Con embeddings: usar pesos originales
            finalScore = (0.45 * cosine) + (0.25 * angles) + (0.20 * skeleton) + (0.10 * contour);
        } else {
            // Sin embeddings: redistribuir pesos para no perder señal
            finalScore = (0.50 * angles) + (0.35 * skeleton) + (0.15 * contour);
        }
        finalScore = Math.max(0.0, Math.min(1.0, finalScore));
        logger.info("[SIMILARITY] components: cosine={} angles={} skeleton={} contour={} final={} (hasEmbeddings={})",
                String.format("%.4f", cosine), String.format("%.4f", angles), String.format("%.4f", skeleton), String.format("%.4f", contour), String.format("%.4f", finalScore), hasEmbeddings);
        return finalScore;
    }

    private static double calculateSkeletonSimilarity(PoseData drawingPose, PoseData imagePose) {
        try {
            var joints = drawingPose.getAllJoints();
            var lm = imagePose.getAllLandmarks();

            javafx.geometry.Point2D drawCenter = joints.getOrDefault(org.refcolor.buscareferencias.model.AnatomyPart.TORSO, null);
            javafx.geometry.Point2D imgCenter = null;
            if (lm.containsKey(23) && lm.containsKey(24)) {
                imgCenter = new javafx.geometry.Point2D((lm.get(23).getX() + lm.get(24).getX()) / 2.0, (lm.get(23).getY() + lm.get(24).getY()) / 2.0);
            } else if (lm.containsKey(11) && lm.containsKey(12)) {
                imgCenter = new javafx.geometry.Point2D((lm.get(11).getX() + lm.get(12).getX()) / 2.0, (lm.get(11).getY() + lm.get(12).getY()) / 2.0);
            }
            if (drawCenter == null || imgCenter == null) return 0.0;

            double drawScale = 0.0;
            if (joints.containsKey(org.refcolor.buscareferencias.model.AnatomyPart.HEAD) && joints.containsKey(org.refcolor.buscareferencias.model.AnatomyPart.TORSO)) {
                drawScale = joints.get(org.refcolor.buscareferencias.model.AnatomyPart.HEAD).distance(joints.get(org.refcolor.buscareferencias.model.AnatomyPart.TORSO));
            }
            double imgScale = 0.0;
            if (lm.containsKey(0) && (lm.containsKey(11) || lm.containsKey(12))) {
                javafx.geometry.Point2D shoulder = lm.containsKey(11) && lm.containsKey(12) ?
                        new javafx.geometry.Point2D((lm.get(11).getX() + lm.get(12).getX()) / 2.0, (lm.get(11).getY() + lm.get(12).getY()) / 2.0) :
                        lm.get(11) != null ? new javafx.geometry.Point2D(lm.get(11).getX(), lm.get(11).getY()) : new javafx.geometry.Point2D(lm.get(12).getX(), lm.get(12).getY());
                imgScale = new javafx.geometry.Point2D(lm.get(0).getX(), lm.get(0).getY()).distance(shoulder);
            }
            if (drawScale <= 0 || imgScale <= 0) return 0.0;

            java.util.Map<org.refcolor.buscareferencias.model.AnatomyPart, Integer[]> mapping = new java.util.HashMap<>();
            mapping.put(org.refcolor.buscareferencias.model.AnatomyPart.HEAD, new Integer[]{0});
            mapping.put(org.refcolor.buscareferencias.model.AnatomyPart.ARMS, new Integer[]{11,12});
            mapping.put(org.refcolor.buscareferencias.model.AnatomyPart.FOREARMS, new Integer[]{13,14});
            mapping.put(org.refcolor.buscareferencias.model.AnatomyPart.HANDS, new Integer[]{15,16});
            mapping.put(org.refcolor.buscareferencias.model.AnatomyPart.THIGHS, new Integer[]{23,24});
            mapping.put(org.refcolor.buscareferencias.model.AnatomyPart.CALVES, new Integer[]{25,26});
            mapping.put(org.refcolor.buscareferencias.model.AnatomyPart.FEET, new Integer[]{27,28});

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
            if (counted == 0) return 0.0;
            double similarity = total / counted;
            // Penalizar poses incompletas
            similarity *= Math.min(1.0, counted / 15.0);
            return similarity;
        } catch (Exception e) {
            logger.debug("Error calculating skeleton similarity: {}", e.toString());
            return 0.0;
        }
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



