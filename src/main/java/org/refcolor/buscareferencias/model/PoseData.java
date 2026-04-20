package org.refcolor.buscareferencias.model;

import javafx.geometry.Point2D;
import java.util.HashMap;
import java.util.Map;

public class PoseData {
    private final Map<AnatomyPart, Point2D> joints = new HashMap<>();

    public void addJoint(AnatomyPart part, double x, double y) {
        joints.put(part, new Point2D(x, y));
    }

    public Point2D getJoint(AnatomyPart part) {
        return joints.get(part);
    }

    public Map<AnatomyPart, Point2D> getAllJoints() {
        return joints;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PoseData{");
        joints.forEach((part, point) -> sb.append(part.getName()).append("=(").append(point.getX()).append(",").append(point.getY()).append(") "));
        sb.append("}");
        return sb.toString();
    }
}
