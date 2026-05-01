package org.refcolor.buscareferencias.utils;

import javafx.geometry.Point2D;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;

import java.util.ArrayList;
import java.util.List;

public class SearchTermGenerator {

    public static List<String> generateTerms(PoseData pose) {
        List<String> terms = new ArrayList<>();
        
        Point2D head = pose.getJoint(AnatomyPart.HEAD);
        Point2D torso = pose.getJoint(AnatomyPart.TORSO);
        Point2D hands = pose.getJoint(AnatomyPart.HANDS);
        Point2D feet = pose.getJoint(AnatomyPart.FEET);

        boolean hasHead = head != null;
        boolean hasTorso = torso != null;
        boolean hasLegs = pose.getJoint(AnatomyPart.THIGHS) != null || pose.getJoint(AnatomyPart.CALVES) != null || feet != null;
        boolean hasArms = pose.getJoint(AnatomyPart.ARMS) != null || pose.getJoint(AnatomyPart.FOREARMS) != null || hands != null;

        // 1. Categoría de encuadre
        String frame = "";
        if (hasHead && hasTorso && hasLegs) {
            frame = "full body";
        } else if (hasHead && hasTorso) {
            frame = "upper body";
        } else if (hasTorso && hasLegs) {
            frame = "lower body";
        }

        // 2. Detección de acciones específicas
        String action = "";
        if (hasHead && hands != null && hands.getY() < head.getY()) {
            action = "arms raised";
        } else if (torso != null && hands != null && hands.getY() < torso.getY()) {
            action = "arms up";
        }

        // 3. Pose (Parado vs Sentado)
        String posture = "";
        if (torso != null && feet != null) {
            double verticalDist = feet.getY() - torso.getY();
            if (verticalDist < 120) {
                posture = "sitting";
            } else {
                posture = "standing";
            }
        }

        // Construcción de frases de búsqueda
        if (!frame.isEmpty()) {
            terms.add(frame + " pose reference");
        }
        
        if (!action.isEmpty()) {
            terms.add("human " + action + " reference");
            if (!frame.isEmpty()) {
                terms.add(frame + " " + action);
            }
        }
        
        if (!posture.isEmpty()) {
            terms.add(posture + " anatomy reference");
        }

        // Término genérico si está vacío
        if (terms.isEmpty()) {
            terms.add("human anatomy pose reference");
        } else {
            // Siempre añadir un término base
            terms.add("drawing reference");
        }

        return terms;
    }
}
