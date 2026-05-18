package org.refcolor.buscareferencias.utils;

import javafx.geometry.Point2D;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public class SearchTermGenerator {

    private static final double SITTING_THRESHOLD = 120.0;

    public static List<String> generateTerms(PoseData pose) {
        Set<String> terms = new LinkedHashSet<>();
        
        Point2D head = pose.getJoint(AnatomyPart.HEAD);
        Point2D torso = pose.getJoint(AnatomyPart.TORSO);
        Point2D hands = pose.getJoint(AnatomyPart.HANDS);
        Point2D feet = pose.getJoint(AnatomyPart.FEET);

        boolean hasHead = head != null;
        boolean hasTorso = torso != null;
        boolean hasLegs = pose.getJoint(AnatomyPart.THIGHS) != null || pose.getJoint(AnatomyPart.CALVES) != null || feet != null;
        boolean hasArms = pose.getJoint(AnatomyPart.ARMS) != null || pose.getJoint(AnatomyPart.FOREARMS) != null || hands != null;

        String frame = "";
        if (hasHead && hasTorso && hasLegs) frame = "full body";
        else if (hasHead && hasTorso) frame = "upper body";
        else if (hasTorso && hasLegs) frame = "lower body";
        else if (hasHead) frame = "portrait reference";

        String action = "";
        if (hasHead && hands != null && hands.getY() < head.getY()) action = "arms raised";
        else if (torso != null && hands != null && hands.getY() < torso.getY()) action = "arms up";
        else if (hasArms) action = "dynamic pose";

        String posture = "";
        if (torso != null && feet != null) {
            double verticalDist = Math.abs(feet.getY() - torso.getY());
            posture = verticalDist < SITTING_THRESHOLD ? "sitting" : "standing";
        }

        String anatomy = "anatomy reference";
        if (hasHead && hasTorso && hasLegs) anatomy = "full body anatomy reference";
        else if (hasHead && hasTorso) anatomy = "upper body anatomy reference";
        else if (hasTorso && hasLegs) anatomy = "lower body anatomy reference";

        // Construcción de frases inteligentes
        String base = (frame + " " + posture + " " + action).trim();
        if (base.isEmpty()) base = "human pose";

        terms.add(base + " reference pinterest");
        terms.add(base + " anatomy reference google images");
        terms.add(base + " figure reference pexels");
        terms.add(anatomy + " pinterest");
        terms.add((posture.isEmpty() ? "standing pose" : posture + " pose") + " reference");
        terms.add((action.isEmpty() ? "human pose" : action) + " anatomy reference");
        
        return new ArrayList<>(terms);
    }
}
