package org.refcolor.buscareferencias.utils;

import org.refcolor.buscareferencias.model.PoseData;
import java.util.logging.Logger;

/**
 * Estructura inicial para la integración de MediaPipe en el Hito 3.
 */
public class MediaPipeService {
    private static final Logger logger = Logger.getLogger(MediaPipeService.class.getName());

    /**
     * Analiza una imagen para extraer la pose. 
     * En el Hito 3 esta función está preparada pero devuelve una pose simulada.
     */
    public static PoseData analyzeImage(String imagePath) {
        logger.info("MediaPipe simulando análisis de: " + imagePath);
        // Mock de pose detectada
        return new PoseData();
    }

    /**
     * Calcula la similitud entre la pose del dibujo y la de la imagen.
     * Basado en el diseño técnico del Hito 3 (Sección 6.1: Similitud del Coseno).
     */
    public static double calculateSimilarity(PoseData drawingPose, PoseData imagePose) {
        logger.info("Calculando similitud entre poses (Simulación Hito 3)");
        // En el Hito 4 se implementará el cálculo real de vectores y ángulos
        return 0.75 + (Math.random() * 0.2); // Simula un match alto para pruebas
    }
}
