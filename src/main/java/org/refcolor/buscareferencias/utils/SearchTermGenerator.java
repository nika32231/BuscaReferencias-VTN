package org.refcolor.buscareferencias.utils;

import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;

import java.util.ArrayList;
import java.util.List;

public class SearchTermGenerator {

    public static List<String> generateTerms(PoseData pose) {
        List<String> terms = new ArrayList<>();
        
        boolean hasHead = pose.getJoint(AnatomyPart.HEAD) != null;
        boolean hasTorso = pose.getJoint(AnatomyPart.TORSO) != null;
        boolean hasLegs = pose.getJoint(AnatomyPart.THIGHS) != null || pose.getJoint(AnatomyPart.CALVES) != null || pose.getJoint(AnatomyPart.FEET) != null;
        boolean hasArms = pose.getJoint(AnatomyPart.ARMS) != null || pose.getJoint(AnatomyPart.FOREARMS) != null || pose.getJoint(AnatomyPart.HANDS) != null;

        // Términos básicos según partes presentes
        if (hasHead && hasTorso && hasLegs) {
            terms.add("full body anatomy reference");
            terms.add("standing pose reference");
        } else if (hasHead && hasTorso && !hasLegs) {
            terms.add("upper body anatomy reference");
            terms.add("portrait pose reference");
        }

        if (hasArms) {
            terms.add("human arms pose reference");
        }

        // Si no hay nada específico, términos genéricos
        if (terms.isEmpty()) {
            terms.add("human anatomy pose reference");
        }

        return terms;
    }
}
