package org.refcolor.buscareferencias.utils;

import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DrawingProcessor {

    private static final Logger logger = LoggerFactory.getLogger(DrawingProcessor.class);
    
    private static final double HUE_THRESHOLD = 8.0;
    private static final double MIN_SATURATION = 0.2;
    private static final double MIN_BRIGHTNESS = 0.2;

    public static PoseData processImage(WritableImage snapshot) {
        PoseData pose = new PoseData();
        int width = (int) snapshot.getWidth();
        int height = (int) snapshot.getHeight();
        
        PixelReader reader = snapshot.getPixelReader();

        // Estructuras para acumular centros de masa en una sola pasada
        Map<AnatomyPart, Double> sumX = new HashMap<>();
        Map<AnatomyPart, Double> sumY = new HashMap<>();
        Map<AnatomyPart, Integer> counts = new HashMap<>();

        for (AnatomyPart part : AnatomyPart.values()) {
            sumX.put(part, 0.0);
            sumY.put(part, 0.0);
            counts.put(part, 0);
        }

        // Una sola pasada O(W*H)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Color pixelColor = reader.getColor(x, y);
                
                // Filtro de opacidad (ignorar fondo)
                if (pixelColor.getOpacity() < 0.5) continue;

                // Identificar a qué parte anatómica pertenece el píxel (si a alguna)
                for (AnatomyPart part : AnatomyPart.values()) {
                    if (isSimilarHSB(pixelColor, Color.web(part.getHexColor()))) {
                        sumX.put(part, sumX.get(part) + (double)x / width); // Normalizado
                        sumY.put(part, sumY.get(part) + (double)y / height); // Normalizado
                        counts.put(part, counts.get(part) + 1);
                        break; // Un píxel solo puede ser una parte
                    }
                }
            }
        }

        // Calcular centroides finales
        for (AnatomyPart part : AnatomyPart.values()) {
            int count = counts.get(part);
            if (count > 0) {
                pose.addJoint(part, sumX.get(part) / count, sumY.get(part) / count);
            }
        }

        return pose;
    }

    /**
     * Compara colores usando HSB (Hue, Saturation, Brightness).
     * El Hue (Matiz) es mucho más robusto ante antialiasing y variaciones de brillo.
     */
    public static boolean isSimilarHSB(Color c1, Color c2) {
        double hue1 = c1.getHue();
        double hue2 = c2.getHue();
        
        // Diferencia de matiz (hue) - el matiz es circular (0-360)
        double diffHue = Math.abs(hue1 - hue2);
        if (diffHue > 180) diffHue = 360 - diffHue;
 
        // Tolerancia de matiz reducida para evitar confusiones Rojo/Rosa
        // y comprobamos saturación y brillo de AMBOS para simetría
        // Nota: usamos HSB porque es robusto ante aliasing y variaciones de iluminación.
        // Esto evita depender únicamente del color exacto (RGB) que sería frágil.
        return diffHue < HUE_THRESHOLD 
            && c1.getSaturation() > MIN_SATURATION && c2.getSaturation() > MIN_SATURATION
            && c1.getBrightness() > MIN_BRIGHTNESS && c2.getBrightness() > MIN_BRIGHTNESS;
    }
}
