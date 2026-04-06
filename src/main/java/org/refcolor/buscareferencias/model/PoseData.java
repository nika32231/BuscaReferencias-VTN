package org.refcolor.buscareferencias.model;

import javafx.geometry.Point2D;
import java.util.HashMap;
import java.util.Map;

public class PoseData {
    private final Map<String, Point2D> joints = new HashMap<>();

    public void addJoint(String name, double x, double y) {
        joints.put(name, new Point2D(x, y));
    }

    public Point2D getJoint(String name) {
        return joints.get(name);
    }

    public Map<String, Point2D> getAllJoints() {
        return joints;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PoseData{");
        joints.forEach((name, point) -> sb.append(name).append("=(").append(point.getX()).append(",").append(point.getY()).append(") "));
        sb.append("}");
        return sb.toString();
    }
}
