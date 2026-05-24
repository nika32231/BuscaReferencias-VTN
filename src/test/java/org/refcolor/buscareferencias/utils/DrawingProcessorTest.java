package org.refcolor.buscareferencias.utils;

import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;
import static org.junit.jupiter.api.Assertions.*;

public class DrawingProcessorTest {

    // ── Helper: crea una imagen sintética con píxeles pintados ────────────────

    /**
     * Crea una {@link WritableImage} de 900×600 con fondo de lienzo (#FAF8F6)
     * y pinta una banda horizontal sólida con el color indicado.
     *
     * @param color   color de los píxeles del trazo
     * @param xFrom   columna inicial (inclusive)
     * @param xTo     columna final (exclusive)
     * @param yFrom   fila inicial (inclusive)
     * @param yTo     fila final (exclusive)
     */
    private static WritableImage makeCanvasImage(Color color,
                                                  int xFrom, int xTo,
                                                  int yFrom, int yTo) {
        return makeCanvasImage(new int[][]{{xFrom, xTo, yFrom, yTo}}, color);
    }

    private static WritableImage makeCanvasImage(int[][] strokes, Color color) {
        int W = 900, H = 600;
        WritableImage img = new WritableImage(W, H);
        var pw = img.getPixelWriter();
        Color bg = Color.web("#FAF8F6");
        for (int x = 0; x < W; x++)
            for (int y = 0; y < H; y++)
                pw.setColor(x, y, bg);
        for (int[] s : strokes)
            for (int x = s[0]; x < s[1]; x++)
                for (int y = s[2]; y < s[3]; y++)
                    pw.setColor(x, y, color);
        return img;
    }

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

    // ── Bilateral detection tests ─────────────────────────────────────────────

    /**
     * Los colores explícitos LEFT_ARM y RIGHT_ARM se detectan de forma independiente.
     * Cada uno genera su propio joint; la posición horizontal no importa para
     * la detección (el split por posición se aplica solo a los combinados como SHOULDERS).
     */
    @Test
    public void testBilateralDetection_TwoArmStrokes() {
        Color leftArmColor  = Color.web(AnatomyPart.LEFT_ARM.getHexColor());
        Color rightArmColor = Color.web(AnatomyPart.RIGHT_ARM.getHexColor());

        // Crear imagen con ambos colores (lados izquierdo y derecho respectivamente)
        int W = 900, H = 600;
        WritableImage img = new WritableImage(W, H);
        var pw = img.getPixelWriter();
        Color bg = Color.web("#FAF8F6");
        for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) pw.setColor(x, y, bg);
        // LEFT_ARM: banda en semilado izquierdo  (nx ≈ 0.11–0.44)
        for (int x = 100; x < 400; x++) for (int y = 200; y < 215; y++) pw.setColor(x, y, leftArmColor);
        // RIGHT_ARM: banda en semilado derecho (nx ≈ 0.56–0.89)
        for (int x = 500; x < 800; x++) for (int y = 200; y < 215; y++) pw.setColor(x, y, rightArmColor);

        PoseData pose = DrawingProcessor.processImage(img);

        assertNotNull(pose.getJoint(AnatomyPart.LEFT_ARM),
                "LEFT_ARM debe detectarse con su color explícito");
        assertNotNull(pose.getJoint(AnatomyPart.RIGHT_ARM),
                "RIGHT_ARM debe detectarse con su color explícito");

        // Los joints deben estar en su semilado correspondiente
        assertTrue(pose.getJoint(AnatomyPart.LEFT_ARM).getX() < 0.5,
                "LEFT_ARM centroide debe estar en x < 0.5");
        assertTrue(pose.getJoint(AnatomyPart.RIGHT_ARM).getX() >= 0.5,
                "RIGHT_ARM centroide debe estar en x >= 0.5");
    }

    /**
     * Incluso trazos pequeños (5×5 = 25 px) con colores explícitos LEFT/RIGHT_ARM
     * se detectan correctamente (no hay umbral mínimo para paleta explícita).
     */
    @Test
    public void testBilateralDetection_ShortStrokes_ShouldFire() {
        Color leftArmColor  = Color.web(AnatomyPart.LEFT_ARM.getHexColor());
        Color rightArmColor = Color.web(AnatomyPart.RIGHT_ARM.getHexColor());

        int W = 900, H = 600;
        WritableImage img = new WritableImage(W, H);
        var pw = img.getPixelWriter();
        Color bg = Color.web("#FAF8F6");
        for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) pw.setColor(x, y, bg);
        // Trazo muy pequeño (5×5 = 25 px) de cada color
        for (int x = 200; x < 205; x++) for (int y = 200; y < 205; y++) pw.setColor(x, y, leftArmColor);
        for (int x = 700; x < 705; x++) for (int y = 200; y < 205; y++) pw.setColor(x, y, rightArmColor);

        PoseData pose = DrawingProcessor.processImage(img);

        assertNotNull(pose.getJoint(AnatomyPart.LEFT_ARM),
                "LEFT_ARM debe detectarse con 25 píxeles de su color explícito");
        assertNotNull(pose.getJoint(AnatomyPart.RIGHT_ARM),
                "RIGHT_ARM debe detectarse con 25 píxeles de su color explícito");
    }

    /**
     * SHOULDERS (combinado de paleta) sigue usando detección bilateral por posición.
     * Con píxeles solo en el semilado izquierdo → LEFT_SHOULDER pero NO RIGHT_SHOULDER.
     */
    @Test
    public void testBilateralDetection_OneSide_NoFalsePositive() {
        Color shouldersColor = Color.web(AnatomyPart.SHOULDERS.getHexColor());
        // Solo semilado izquierdo
        WritableImage img = makeCanvasImage(shouldersColor, 100, 400, 200, 215);

        PoseData pose = DrawingProcessor.processImage(img);

        assertNotNull(pose.getJoint(AnatomyPart.SHOULDERS),
                "El centroide combinado SHOULDERS debe estar presente");
        assertNotNull(pose.getJoint(AnatomyPart.LEFT_SHOULDER),
                "LEFT_SHOULDER debe detectarse con píxeles en el semilado izquierdo");
        assertNull(pose.getJoint(AnatomyPart.RIGHT_SHOULDER),
                "RIGHT_SHOULDER NO debe crearse si no hay píxeles en el semilado derecho");
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
