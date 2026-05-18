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
        org.json.JSONObject json = new org.json.JSONObject(jsonStr);
        json.keySet().forEach(key -> poseAngles.put(key, json.getDouble(key)));
    }

    public String getLandmarksJson() {
        org.json.JSONObject json = new org.json.JSONObject();
        landmarks.forEach((id, point) -> {
            org.json.JSONObject p = new org.json.JSONObject();
            p.put("x", point.getX());
            p.put("y", point.getY());
            json.put(String.valueOf(id), p);
        });
        return json.toString();
    }

    public String getEmbeddingJson() {
        return new org.json.JSONArray(embedding).toString();
    }

    public void setLandmarksFromJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) return;
        org.json.JSONObject json = new org.json.JSONObject(jsonStr);
        json.keySet().forEach(key -> {
            org.json.JSONObject p = json.getJSONObject(key);
            addLandmark(Integer.parseInt(key), p.getDouble("x"), p.getDouble("y"));
        });
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
