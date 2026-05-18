package org.refcolor.buscareferencias.service;

import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.database.DatabaseManager;
import org.refcolor.buscareferencias.client.PythonImageSearchClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.net.HttpURLConnection;

/**
 * Servicio para la integración de MediaPipe.
 */
public class MediaPipeService {

    /**
     * Servicio para descargar y cachear imágenes localmente para generar miniaturas reales.
     */
    public static class ImageCacheService {
        private static final String CACHE_DIR = "cache/thumbnails";
        // Directorio usado solo para la búsqueda/ sesión actual. Se limpia al iniciar una nueva búsqueda.
        private static volatile String SESSION_CACHE_DIR = null; // e.g. "cache/current_search"
        private static final long CACHE_TTL_DAYS = 7;

        static {
            try {
                Files.createDirectories(Paths.get(CACHE_DIR));
                cleanupExpiredCacheFiles();
            } catch (Exception e) {
                logger.error("No se pudo crear el directorio de caché", e);
            }
        }

        /**
         * Inicia una nueva sesión de búsqueda. Esto crea y limpia el directorio
         * temporal usado para almacenar miniaturas de la búsqueda actual.
         * Llamar antes de iniciar una nueva búsqueda online para evitar acumular imágenes.
         */
        public static synchronized void startSessionCache() {
            try {
                SESSION_CACHE_DIR = "cache/current_search";
                Path sessionPath = Paths.get(SESSION_CACHE_DIR);
                if (Files.exists(sessionPath)) {
                    // Borrar contenido existente (no eliminar el directorio en sí)
                    try (var stream = Files.list(sessionPath)) {
                        stream.filter(Files::isRegularFile).forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
                    } catch (Exception ignored) {}
                } else {
                    Files.createDirectories(sessionPath);
                }
            } catch (Exception e) {
                logger.warn("No se pudo iniciar sesión de caché temporal: {}", e.toString());
            }
        }

        /**
         * Limpia (elimina) el directorio de la sesión actual si existe.
         */
        public static synchronized void clearSessionCache() {
            if (SESSION_CACHE_DIR == null) return;
            try {
                Path sessionPath = Paths.get(SESSION_CACHE_DIR);
                if (Files.isDirectory(sessionPath)) {
                    try (var stream = Files.list(sessionPath)) {
                        stream.filter(Files::isRegularFile).forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                logger.debug("Error limpiando session cache: {}", e.toString());
            } finally {
                SESSION_CACHE_DIR = null;
            }
        }

        public static String getLocalThumbnailPath(String imageUrl) {
            if (imageUrl == null || imageUrl.isEmpty()) return null;
            if (!imageUrl.startsWith("http")) return imageUrl;

            try {
                String hash = hashUrl(imageUrl);
                // Si existe una sesión activa usamos su directorio para almacenar las miniaturas
                Path cachedFile = SESSION_CACHE_DIR != null
                        ? Paths.get(SESSION_CACHE_DIR, hash + ".jpg")
                        : Paths.get(CACHE_DIR, hash + ".jpg");

                if (Files.exists(cachedFile)) {
                    return cachedFile.toUri().toString();
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(cachedFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                return cachedFile.toUri().toString();
            } catch (Exception e) {
                logger.error("Error al cachear imagen: " + imageUrl, e);
                return imageUrl;
            }
        }

        private static String hashUrl(String url) throws Exception {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashInBytes = md.digest(url.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hashInBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }

        private static void cleanupExpiredCacheFiles() {
            try {
                Path cachePath = Paths.get(CACHE_DIR);
                if (!Files.isDirectory(cachePath)) {
                    return;
                }

                Instant cutoff = Instant.now().minus(Duration.ofDays(CACHE_TTL_DAYS));
                try (var stream = Files.list(cachePath)) {
                    stream.filter(Files::isRegularFile).forEach(path -> {
                        try {
                            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
                            if (lastModified.isBefore(cutoff)) {
                                Files.deleteIfExists(path);
                            }
                        } catch (Exception ignored) {
                            // La limpieza no debe romper la caché.
                        }
                    });
                }
            } catch (Exception e) {
                logger.debug("No se pudo limpiar la caché temporal: {}", e.toString());
            }
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(MediaPipeService.class);

    /**
     * Analiza una imagen (URL o local) para extraer la pose usando MediaPipe vía Python.
     */
    public static PoseData analyzeImage(String imageSource) {
        // Primero intentamos buscar en caché de base de datos
        PoseData cachedPose = DatabaseManager.getCachedPose(imageSource);
        if (cachedPose != null) {
            logger.info("Usando pose cacheada para: " + imageSource);
            return cachedPose;
        }

        logger.info("MediaPipe analizando: " + imageSource);
        Path tempFile = null;
        try {
            if (imageSource.startsWith("http")) {
                tempFile = Files.createTempFile("mp_img_", ".jpg");
                try (var in = new URL(imageSource).openStream()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
                imageSource = tempFile.toAbsolutePath().toString();
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
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Calcula la similitud entre la pose del dibujo y la de la imagen.
     * Sistema híbrido: Embeddings (Coseno) + Ángulos + (Próximamente Contornos)
     */
    public static double calculateSimilarity(PoseData drawingPose, PoseData imagePose) {
        if (imagePose.getAllLandmarks().isEmpty() || drawingPose.getAllJoints().isEmpty()) {
            return 0.0;
        }

        boolean hasEmbeddings = !drawingPose.getEmbedding().isEmpty() && !imagePose.getEmbedding().isEmpty();
        double structuralScore = calculateStructuralScore(drawingPose, imagePose);

        if (!hasEmbeddings) {
            return structuralScore;
        }

        // Cuando existen embeddings, los combinamos con la estructura para mantener estabilidad.
        double embeddingScore = calculateCosineSimilarity(drawingPose.getEmbedding(), imagePose.getEmbedding());
        return (embeddingScore * 0.7) + (structuralScore * 0.3);
    }

    private static double calculateStructuralScore(PoseData drawingPose, PoseData imagePose) {
        double totalScore = 0;
        double totalWeight = 0;

        var lm = imagePose.getAllLandmarks();
        var joints = drawingPose.getAllJoints();

        // Reutilizamos la lógica de ángulos existente, pero encapsulada
        // ... (resto del código de ángulos) ...
        return calculateAngleBasedSimilarity(drawingPose, imagePose);
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
                bestArmScore = Math.max(bestArmScore, 1.0 - (diff / 180.0));
            }
            if (lm.containsKey(12) && lm.containsKey(14) && lm.containsKey(16)) {
                double imgArmR = PoseData.calculateAngle(lm.get(12), lm.get(14), lm.get(16));
                double diff = Math.abs(drawArmAngle - imgArmR);
                if (diff > 180) diff = 360 - diff;
                bestArmScore = Math.max(bestArmScore, 1.0 - (diff / 180.0));
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
                 bestLegScore = Math.max(bestLegScore, 1.0 - (diff / 180.0));
             }
             if (lm.containsKey(24) && lm.containsKey(26) && lm.containsKey(28)) {
                 double imgLegR = PoseData.calculateAngle(lm.get(24), lm.get(26), lm.get(28));
                 double diff = Math.abs(drawLegAngle - imgLegR);
                 if (diff > 180) diff = 360 - diff;
                 bestLegScore = Math.max(bestLegScore, 1.0 - (diff / 180.0));
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
