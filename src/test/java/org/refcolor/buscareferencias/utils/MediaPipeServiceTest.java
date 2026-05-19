package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.service.MediaPipeService;
import static org.junit.jupiter.api.Assertions.*;

public class MediaPipeServiceTest {

    @Test
    public void testAnalyzeImageStructure() {
        PoseData pose = MediaPipeService.analyzeImage("test.jpg");
        assertNotNull(pose, "El servicio debería devolver un objeto PoseData aunque sea un mock");
    }

    @Test
    public void testAnalyzeImageRejectsRemoteUrls() {
        PoseData pose = MediaPipeService.analyzeImage("https://example.com/photo.jpg");
        assertTrue(pose.getAllLandmarks().isEmpty());
    }

    @Test
    public void testCalculateSimilarityValue() {
        PoseData drawing = new PoseData();
        drawing.addJoint(org.refcolor.buscareferencias.model.AnatomyPart.HEAD, 100, 50);
        drawing.addJoint(org.refcolor.buscareferencias.model.AnatomyPart.TORSO, 100, 150);
        
        PoseData image = new PoseData();
        image.addLandmark(0, 0.5, 0.2); // Head
        image.addLandmark(11, 0.4, 0.4); // L shoulder
        image.addLandmark(12, 0.6, 0.4); // R shoulder
        
        double similarity = MediaPipeService.calculateSimilarity(drawing, image);
        assertTrue(similarity > 0.5, "La similitud para poses verticales debería ser alta, actual: " + similarity);
    }
}
