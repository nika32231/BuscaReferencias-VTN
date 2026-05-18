package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.service.SearchService;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class SearchServiceTest {

    @Test
    public void testSearchImages() {
        List<String> terms = Arrays.asList("full body", "standing");
        PoseData dummyPose = new PoseData();
        List<ImageResult> results = SearchService.searchImages(terms, dummyPose);
        
        assertNotNull(results);
        // Limite moderado: entre 0 y 100 resultados por busqueda.
        assertTrue(results.size() <= 100);
        
        if (!results.isEmpty()) {
            // Verificar que están ordenados por score descendente
            for (int i = 0; i < results.size() - 1; i++) {
                assertTrue(results.get(i).getScore() >= results.get(i+1).getScore());
            }
            
            // Verificar que tienen URLs
            assertNotNull(results.get(0).getThumbnailUrl());
            assertNotNull(results.get(0).getOriginalUrl());
        }
    }
}
