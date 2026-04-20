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
        pose.addJoint(AnatomyPart.THIGHS, 300, 350);

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("full body anatomy reference"));
        assertTrue(terms.contains("standing pose reference"));
    }

    @Test
    public void testGenerateTermsUpperBody() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.HEAD, 300, 50);
        pose.addJoint(AnatomyPart.TORSO, 300, 200);

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("upper body anatomy reference"));
        assertTrue(terms.contains("portrait pose reference"));
        assertFalse(terms.contains("full body anatomy reference"));
    }

    @Test
    public void testGenerateTermsWithArms() {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.ARMS, 200, 150);

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("human arms pose reference"));
    }

    @Test
    public void testGenerateTermsGeneric() {
        PoseData pose = new PoseData();

        List<String> terms = SearchTermGenerator.generateTerms(pose);
        assertTrue(terms.contains("human anatomy pose reference"));
    }
}
