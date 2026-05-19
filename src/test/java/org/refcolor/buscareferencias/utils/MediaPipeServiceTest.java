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
        drawing.addJoint(org.refcolor.buscareferencias.model.AnatomyPart.HEAD, 0.5, 0.2);
        drawing.addJoint(org.refcolor.buscareferencias.model.AnatomyPart.TORSO, 0.5, 0.45);

        PoseData image = new PoseData();
        image.addLandmark(0, 0.5, 0.2);
        image.addLandmark(11, 0.4, 0.4);
        image.addLandmark(12, 0.6, 0.4);

        double similarity = MediaPipeService.calculateSimilarity(drawing, image);
        assertTrue(similarity > 0.3, "La similitud debería ser > 0, actual: " + similarity);
    }

    @Test
    public void testHeadOnlyDrawingScoresAboveZero() {
        PoseData drawing = new PoseData();
        drawing.addJoint(org.refcolor.buscareferencias.model.AnatomyPart.HEAD, 0.5, 0.25);

        PoseData image = new PoseData();
        image.addLandmark(0, 0.52, 0.28);
        image.addLandmark(11, 0.4, 0.5);
        image.addLandmark(12, 0.6, 0.5);

        double similarity = MediaPipeService.calculateSimilarity(drawing, image);
        assertTrue(similarity > 0.1, "Solo cabeza dibujada debe puntuar > 0, actual: " + similarity);
    }
}
