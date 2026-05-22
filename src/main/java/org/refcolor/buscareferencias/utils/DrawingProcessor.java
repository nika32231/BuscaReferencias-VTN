package org.refcolor.buscareferencias.utils;

import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Analiza un snapshot del lienzo y extrae una {@link PoseData} usando los
 * colores de cada {@link AnatomyPart}.
 *
 * <h3>Detección bilateral</h3>
 * Para las partes corporales simétricas (brazos, antebrazos, manos, muslos,
 * pantorrillas y pies), si se detectan píxeles en AMBOS lados del canvas
 * (≥ {@code MIN_BILATERAL_PIXELS} por lado), se crean <em>dos joints</em>
 * separados: {@code LEFT_ARM} / {@code RIGHT_ARM}, etc. Esto permite a
 * {@link org.refcolor.buscareferencias.service.MediaPipeService} comparar cada
 * lado con su <em>landmark</em> específico de MediaPipe, en lugar de usar el
 * centroide combinado que siempre queda cerca del centro y no discrimina la
 * posición del brazo.
 *
 * <p>Siempre se añade también el centroide combinado (ej. {@code ARMS}) para
 * compatibilidad con {@link SearchTermGenerator} y el código de puntuación
 * heredado.</p>
 */
public class DrawingProcessor {

    private static final Logger logger = LoggerFactory.getLogger(DrawingProcessor.class);

    private static final double HUE_THRESHOLD  = 8.0;
    private static final double MIN_SATURATION = 0.2;
    private static final double MIN_BRIGHTNESS = 0.2;

    /**
     * Número mínimo de píxeles en un semilado para considerar que esa mitad
     * contiene un trazo real (y crear el joint bilateral correspondiente).
     */
    private static final int MIN_BILATERAL_PIXELS = 50;

    // Solo los ítems de paleta se usan para la detección por color
    private static final AnatomyPart[] PALETTE_PARTS;
    static {
        int count = 0;
        for (AnatomyPart p : AnatomyPart.values()) if (p.isPaletteItem()) count++;
        PALETTE_PARTS = new AnatomyPart[count];
        int i = 0;
        for (AnatomyPart p : AnatomyPart.values()) if (p.isPaletteItem()) PALETTE_PARTS[i++] = p;
    }

    public static PoseData processImage(WritableImage snapshot) {
        PoseData pose = new PoseData();
        int width  = (int) snapshot.getWidth();
        int height = (int) snapshot.getHeight();
        PixelReader reader = snapshot.getPixelReader();

        // ── Acumuladores de centroides ────────────────────────────────────────
        Map<AnatomyPart, Double>  sumX   = new HashMap<>();
        Map<AnatomyPart, Double>  sumY   = new HashMap<>();
        Map<AnatomyPart, Integer> counts = new HashMap<>();
        // ── Acumuladores bilaterales (semilado izquierdo / derecho) ───────────
        Map<AnatomyPart, Double>  lSumX  = new HashMap<>();
        Map<AnatomyPart, Double>  lSumY  = new HashMap<>();
        Map<AnatomyPart, Integer> lCnt   = new HashMap<>();
        Map<AnatomyPart, Double>  rSumX  = new HashMap<>();
        Map<AnatomyPart, Double>  rSumY  = new HashMap<>();
        Map<AnatomyPart, Integer> rCnt   = new HashMap<>();

        for (AnatomyPart part : PALETTE_PARTS) {
            sumX.put(part, 0.0);  sumY.put(part, 0.0);  counts.put(part, 0);
            lSumX.put(part, 0.0); lSumY.put(part, 0.0); lCnt.put(part, 0);
            rSumX.put(part, 0.0); rSumY.put(part, 0.0); rCnt.put(part, 0);
        }

        // ── Pasada única O(W×H) ────────────────────────────────────────────────
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Color pixel = reader.getColor(x, y);
                if (pixel.getOpacity() < 0.5) continue;

                for (AnatomyPart part : PALETTE_PARTS) {
                    if (!isSimilarHSB(pixel, Color.web(part.getHexColor()))) continue;

                    double nx = (double) x / width;
                    double ny = (double) y / height;

                    // Centroide combinado
                    sumX.merge(part, nx, Double::sum);
                    sumY.merge(part, ny, Double::sum);
                    counts.merge(part, 1, Integer::sum);

                    // Split bilateral si la parte tiene variantes L/R
                    if (part.leftVariant() != null) {
                        if (nx < 0.5) {
                            lSumX.merge(part, nx, Double::sum);
                            lSumY.merge(part, ny, Double::sum);
                            lCnt.merge(part, 1, Integer::sum);
                        } else {
                            rSumX.merge(part, nx, Double::sum);
                            rSumY.merge(part, ny, Double::sum);
                            rCnt.merge(part, 1, Integer::sum);
                        }
                    }
                    break; // Un píxel solo puede pertenecer a una parte
                }
            }
        }

        // ── Crear joints ───────────────────────────────────────────────────────
        int bilateralJointsAdded = 0;
        for (AnatomyPart part : PALETTE_PARTS) {
            int total = counts.get(part);
            if (total == 0) continue;

            // Centroide combinado (siempre, para compatibilidad con SearchTermGenerator)
            pose.addJoint(part, sumX.get(part) / total, sumY.get(part) / total);

            AnatomyPart leftVar  = part.leftVariant();
            if (leftVar == null) continue; // HEAD o TORSO: no bilateral

            AnatomyPart rightVar = part.rightVariant();
            int lc = lCnt.get(part);
            int rc = rCnt.get(part);

            if (lc >= MIN_BILATERAL_PIXELS) {
                pose.addJoint(leftVar, lSumX.get(part) / lc, lSumY.get(part) / lc);
                bilateralJointsAdded++;
            }
            if (rc >= MIN_BILATERAL_PIXELS) {
                pose.addJoint(rightVar, rSumX.get(part) / rc, rSumY.get(part) / rc);
                bilateralJointsAdded++;
            }
        }

        if (bilateralJointsAdded > 0) {
            logger.debug("[DRAW] {} joints bilaterales detectados (L/R)", bilateralJointsAdded);
        }
        return pose;
    }

    /**
     * Compara colores usando HSB (Hue, Saturation, Brightness).
     * El Hue es robusto ante antialiasing y variaciones de brillo.
     */
    public static boolean isSimilarHSB(Color c1, Color c2) {
        double diffHue = Math.abs(c1.getHue() - c2.getHue());
        if (diffHue > 180) diffHue = 360 - diffHue;
        return diffHue < HUE_THRESHOLD
            && c1.getSaturation() > MIN_SATURATION && c2.getSaturation() > MIN_SATURATION
            && c1.getBrightness()  > MIN_BRIGHTNESS  && c2.getBrightness()  > MIN_BRIGHTNESS;
    }
}
