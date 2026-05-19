package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchTermGeneratorTest {

    @Test
    void fullBodyTermsAreLocalDescriptions() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.HEAD, 0.5, 0.1);
        pose.addJoint(AnatomyPart.TORSO, 0.5, 0.4);
        pose.addJoint(AnatomyPart.THIGHS, 0.5, 0.6);
        pose.addJoint(AnatomyPart.FEET, 0.5, 0.9);

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.stream().anyMatch(t -> t.contains("cuerpo completo")));
        assertFalse(terms.stream().anyMatch(t -> t.contains("google") || t.contains("pinterest") || t.contains("pexels")));
    }

    @Test
    void armsRaisedTerm() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.HEAD, 0.5, 0.4);
        pose.addJoint(AnatomyPart.HANDS, 0.5, 0.1);

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.stream().anyMatch(t -> t.contains("brazos levantados")));
    }

    @Test
    void sittingPostureUsesNormalizedCoords() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.TORSO, 0.5, 0.4);
        pose.addJoint(AnatomyPart.FEET, 0.5, 0.55);

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.stream().anyMatch(t -> t.contains("sentado")));
    }
}
