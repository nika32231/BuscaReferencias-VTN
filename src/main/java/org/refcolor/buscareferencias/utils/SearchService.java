package org.refcolor.buscareferencias.utils;

import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Servicio simulado para la búsqueda de imágenes.
 * En el futuro se conectará con una API real o un scrapper.
 */
public class SearchService {

    public static List<ImageResult> searchImages(List<String> terms, PoseData drawingPose) {
        // Simulación de retardo de red
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<ImageResult> results = new ArrayList<>();
        
        String query = String.join("+", terms);
        
        // Generamos resultados de ejemplo usando un servicio de placeholders realista
        for (int i = 1; i <= 10; i++) {
            // Usamos el índice y la query para que las imágenes sean deterministas pero varíen por búsqueda
            int id = Math.abs((query + i).hashCode()) % 1000;
            String thumb = "https://picsum.photos/id/" + id + "/200/300";
            String original = "https://picsum.photos/id/" + id + "/800/1200";
            
            // HITO 3: Introducción de MediaPipe para el análisis
            // En el Hito 4 esto usará la imagen real descargada
            PoseData imagePose = MediaPipeService.analyzeImage(original);
            double score = MediaPipeService.calculateSimilarity(drawingPose, imagePose);
            
            results.add(new ImageResult(thumb, original, "Referencia #" + id, score));
        }

        // Ordenamos por puntuación para simular el algoritmo de ranking
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        
        return results;
    }
}
