package org.refcolor.buscareferencias.model;

import javafx.geometry.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class PoseData {
    private final Map<AnatomyPart, Point2D> joints = new HashMap<>();
    private final Map<Integer, Point2D> landmarks = new HashMap<>();
    private final Map<Integer, Double> landmarkVisibility = new HashMap<>();
    private final List<Double> embedding = new ArrayList<>();
    private final Map<String, Double> poseAngles = new LinkedHashMap<>();

    public void addJoint(AnatomyPart part, double x, double y) {
        joints.put(part, new Point2D(x, y));
    }

    public Point2D getJoint(AnatomyPart part) {
        return joints.get(part);
    }

    public Map<AnatomyPart, Point2D> getAllJoints() {
        return joints;
    }

    public void addLandmark(int id, double x, double y) {
        landmarks.put(id, new Point2D(x, y));
    }

    public void addLandmark(int id, double x, double y, double visibility) {
        landmarks.put(id, new Point2D(x, y));
        if (visibility >= 0) {
            landmarkVisibility.put(id, visibility);
        }
    }

    public double getLandmarkVisibility(int id) {
        return landmarkVisibility.getOrDefault(id, -1.0);
    }

    public boolean hasVisibilityData() {
        return !landmarkVisibility.isEmpty();
    }

    /**
     * Devuelve las regiones corporales visibles en esta pose (umbral de visibilidad 0.5).
     * Regiones: HEAD (lm 0-4), UPPER (lm 11-12), ARMS (lm 13-16), LOWER (lm 23-28).
     */
    public java.util.Set<String> getVisibleRegions() {
        java.util.Set<String> regions = new java.util.HashSet<>();
        if (!hasVisibilityData()) return regions;
        double threshold = 0.5;
        for (int id : new int[]{0, 1, 2, 3, 4}) {
            if (landmarkVisibility.getOrDefault(id, 0.0) > threshold) { regions.add("HEAD"); break; }
        }
        for (int id : new int[]{11, 12}) {
            if (landmarkVisibility.getOrDefault(id, 0.0) > threshold) { regions.add("UPPER"); break; }
        }
        for (int id : new int[]{13, 14, 15, 16}) {
            if (landmarkVisibility.getOrDefault(id, 0.0) > threshold) { regions.add("ARMS"); break; }
        }
        for (int id : new int[]{23, 24, 25, 26, 27, 28}) {
            if (landmarkVisibility.getOrDefault(id, 0.0) > threshold) { regions.add("LOWER"); break; }
        }
        return regions;
    }

    //noinspection unused
    public Point2D getLandmark(int id) {
        return landmarks.get(id);
    }

    public Map<Integer, Point2D> getAllLandmarks() {
        return landmarks;
    }

    public void setEmbedding(List<Double> embedding) {
        this.embedding.clear();
        if (embedding != null) {
            this.embedding.addAll(embedding);
        }
    }

    public List<Double> getEmbedding() {
        return embedding;
    }

    public void putPoseAngle(String name, Double value) {
        if (name == null || name.isBlank() || value == null) return;
        poseAngles.put(name, value);
    }

    public Map<String, Double> getPoseAngles() {
        return poseAngles;
    }

    public String getPoseAnglesJson() {
        return new org.json.JSONObject(poseAngles).toString();
    }

    public void setPoseAnglesFromJson(String jsonStr) {
        poseAngles.clear();
        if (jsonStr == null || jsonStr.isBlank()) return;
        try {
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            // getDouble puede lanzar JSONException si el valor no es numérico (B4)
            json.keySet().forEach(key -> {
                try { poseAngles.put(key, json.getDouble(key)); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    public String getLandmarksJson() {
        org.json.JSONObject json = new org.json.JSONObject();
        landmarks.forEach((id, point) -> {
            org.json.JSONObject p = new org.json.JSONObject();
            p.put("x", point.getX());
            p.put("y", point.getY());
            double vis = landmarkVisibility.getOrDefault(id, -1.0);
            if (vis >= 0) p.put("v", vis);
            json.put(String.valueOf(id), p);
        });
        return json.toString();
    }

    public String getEmbeddingJson() {
        return new org.json.JSONArray(embedding).toString();
    }

    /**
     * Serializa los joints del dibujo del usuario como JSON estructurado.
     * Formato: {@code {"Cabeza":{"x":0.48,"y":0.23}, "Torso":{"x":0.50,"y":0.45}, ...}}
     *
     * <p>A diferencia de {@link #toString()}, produce JSON estándar consultable y
     * parseable, en lugar de una cadena de depuración de formato libre. Usar este
     * método al persistir los datos de pose del dibujo en la tabla {@code Dibujos}.</p>
     */
    public String getJointsJson() {
        org.json.JSONObject json = new org.json.JSONObject();
        joints.forEach((part, point) -> {
            org.json.JSONObject p = new org.json.JSONObject();
            p.put("x", point.getX());
            p.put("y", point.getY());
            json.put(part.getName(), p);
        });
        return json.toString();
    }

    public void setLandmarksFromJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) return;
        try {
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            json.keySet().forEach(key -> {
                try {
                    // Integer.parseInt puede lanzar NumberFormatException con BD corrupta (B5)
                    int id = Integer.parseInt(key);
                    org.json.JSONObject p = json.getJSONObject(key);
                    double vis = p.optDouble("v", -1.0);
                    if (vis >= 0) {
                        addLandmark(id, p.getDouble("x"), p.getDouble("y"), vis);
                    } else {
                        addLandmark(id, p.getDouble("x"), p.getDouble("y"));
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    public void setEmbeddingFromJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) return;
        org.json.JSONArray array = new org.json.JSONArray(jsonStr);
        List<Double> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            list.add(array.getDouble(i));
        }
        setEmbedding(list);
    }

    /**
     * Calcula el ángulo entre tres puntos.
     */
    public static double calculateAngle(Point2D a, Point2D b, Point2D c) {
        double ang1 = Math.atan2(a.getY() - b.getY(), a.getX() - b.getX());
        double ang2 = Math.atan2(c.getY() - b.getY(), c.getX() - b.getX());
        double angle = Math.toDegrees(ang1 - ang2);
        if (angle < 0) angle += 360;
        return angle;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PoseData{");
        if (!joints.isEmpty()) {
            sb.append("joints=[");
            joints.forEach((part, point) -> sb.append(part.getName()).append("=(").append(point.getX()).append(",").append(point.getY()).append(") "));
            sb.append("]");
        }
        if (!landmarks.isEmpty()) {
            sb.append(" landmarks_count=").append(landmarks.size());
        }
        if (!poseAngles.isEmpty()) {
            sb.append(" poseAngles_count=").append(poseAngles.size());
        }
        sb.append("}");
        return sb.toString();
    }
}
