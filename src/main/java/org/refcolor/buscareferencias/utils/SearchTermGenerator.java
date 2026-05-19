package org.refcolor.buscareferencias.utils;

import javafx.geometry.Point2D;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Genera etiquetas descriptivas de la pose detectada en el dibujo (solo uso local/BD).
 */
public class SearchTermGenerator {

    private static final double SITTING_THRESHOLD = 0.35;

    public static List<String> generateTerms(PoseData pose) {
        Set<String> terms = new LinkedHashSet<>();

        Point2D head = pose.getJoint(AnatomyPart.HEAD);
        Point2D torso = pose.getJoint(AnatomyPart.TORSO);
        Point2D hands = pose.getJoint(AnatomyPart.HANDS);
        Point2D feet = pose.getJoint(AnatomyPart.FEET);

        boolean hasHead = head != null;
        boolean hasTorso = torso != null;
        boolean hasLegs = pose.getJoint(AnatomyPart.THIGHS) != null
                || pose.getJoint(AnatomyPart.CALVES) != null
                || feet != null;
        boolean hasArms = pose.getJoint(AnatomyPart.ARMS) != null
                || pose.getJoint(AnatomyPart.FOREARMS) != null
                || hands != null;

        String frame;
        if (hasHead && hasTorso && hasLegs) frame = "cuerpo completo";
        else if (hasHead && hasTorso) frame = "torso superior";
        else if (hasTorso && hasLegs) frame = "torso inferior";
        else if (hasHead) frame = "retrato";
        else frame = "pose humana";

        String action = "";
        if (hasHead && hands != null && hands.getY() < head.getY()) action = "brazos levantados";
        else if (torso != null && hands != null && hands.getY() < torso.getY()) action = "brazos arriba";
        else if (hasArms) action = "pose dinámica";

        String posture = "";
        if (torso != null && feet != null) {
            double verticalDist = Math.abs(feet.getY() - torso.getY());
            posture = verticalDist < SITTING_THRESHOLD ? "sentado" : "de pie";
        }

        String base = (frame + " " + posture + " " + action).trim().replaceAll("\\s+", " ");
        if (base.isBlank()) base = "referencia de pose";

        terms.add(base);
        terms.add(base + " referencia anatómica");
        terms.add((posture.isEmpty() ? "de pie" : posture) + " referencia");
        terms.add((action.isEmpty() ? "pose" : action) + " referencia local");

        return new ArrayList<>(terms);
    }
}
