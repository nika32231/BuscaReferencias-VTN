package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.PoseData;
import static org.junit.jupiter.api.Assertions.*;

public class MediaPipeServiceTest {

    @Test
    public void testAnalyzeImageStructure() {
        PoseData pose = MediaPipeService.analyzeImage("test.jpg");
        assertNotNull(pose, "El servicio debería devolver un objeto PoseData aunque sea un mock");
    }

    @Test
    public void testCalculateSimilarityStructure() {
        PoseData pose1 = new PoseData();
        PoseData pose2 = new PoseData();
        double similarity = MediaPipeService.calculateSimilarity(pose1, pose2);
        
        assertTrue(similarity >= 0.0 && similarity <= 1.0, "La similitud debe estar entre 0 y 1");
    }
}
