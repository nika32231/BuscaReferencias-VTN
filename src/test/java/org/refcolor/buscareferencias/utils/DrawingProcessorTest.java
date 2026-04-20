package org.refcolor.buscareferencias.utils;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.AnatomyPart;
import static org.junit.jupiter.api.Assertions.*;

public class DrawingProcessorTest {

    @Test
    public void testIsSimilarHSB_ExactMatch() {
        Color headColor = Color.web(AnatomyPart.HEAD.getHexColor());
        assertTrue(DrawingProcessor.isSimilarHSB(headColor, headColor));
    }

    @Test
    public void testIsSimilarHSB_SimilarHue() {
        Color headColor = Color.web("#FF0000"); // Hue 0
        Color slightlyDifferentRed = Color.hsb(5, 1.0, 1.0); // Hue 5
        assertTrue(DrawingProcessor.isSimilarHSB(slightlyDifferentRed, headColor));
    }

    @Test
    public void testIsSimilarHSB_DifferentHue() {
        Color red = Color.RED;
        Color blue = Color.BLUE;
        assertFalse(DrawingProcessor.isSimilarHSB(red, blue));
    }

    @Test
    public void testIsSimilarHSB_LowSaturation() {
        Color red = Color.RED;
        Color greyishRed = Color.hsb(0, 0.1, 0.5); 
        assertFalse(DrawingProcessor.isSimilarHSB(greyishRed, red));
    }

    @Test
    public void testIsSimilarHSB_RedVsPink() {
        Color red = Color.web(AnatomyPart.HEAD.getHexColor());
        Color pink = Color.web(AnatomyPart.FEET.getHexColor());
        // Red hue is 0, Pink hue is ~348. Diff is 12. 
        // With threshold 15, they are considered similar, which is a BUG.
        assertFalse(DrawingProcessor.isSimilarHSB(red, pink), "Red (Head) and Pink (Feet) should be distinct");
    }
}
