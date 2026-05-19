package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.service.SearchService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalThumbnailCacheTest {

    @Test
    void testPythonVersionReport() {
        List<ImageResult> results = SearchService.searchWebThumbnailsOnly(List.of("pose reference"), 10);

        assertNotNull(results, "La búsqueda no debería devolver null");
        assertFalse(results.isEmpty(), "La caché local debería contener al menos una miniatura");
        assertTrue(results.stream().allMatch(result -> result.getThumbnailUrl() != null && !result.getThumbnailUrl().isBlank()),
                "Todas las miniaturas deben tener URL local válida");
    }
}

