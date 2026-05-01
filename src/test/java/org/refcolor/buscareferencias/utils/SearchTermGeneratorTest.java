package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SearchTermGeneratorTest {

    @Test
    public void testGenerateTermsFullBody() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.HEAD, 300, 50);
        pose.addJoint(AnatomyPart.TORSO, 300, 200);
        pose.addJoint(AnatomyPart.FEET, 300, 450);

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("full body pose reference"));
        assertTrue(terms.contains("standing anatomy reference"));
    }

    @Test
    public void testGenerateTermsArmsRaised() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.HEAD, 300, 100);
        pose.addJoint(AnatomyPart.HANDS, 300, 50); // Manos arriba de la cabeza

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("human arms raised reference"));
    }

    @Test
    public void testGenerateTermsSitting() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.TORSO, 300, 200);
        pose.addJoint(AnatomyPart.FEET, 300, 300); // Distancia vertical 100 (< 120)

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("sitting anatomy reference"));
    }

    @Test
    public void testGenerateTermsUpperBody() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.HEAD, 300, 50);
        pose.addJoint(AnatomyPart.TORSO, 300, 200);

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("upper body pose reference"));
        assertFalse(terms.contains("full body pose reference"));
    }

    @Test
    public void testGenerateTermsWithArms() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.TORSO, 300, 200);
        pose.addJoint(AnatomyPart.HANDS, 300, 100);

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("human arms up reference"));
    }

    @Test
    public void testGenerateTermsGeneric() {
        PoseData pose = new PoseData();

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("human anatomy pose reference"));
    }
}
