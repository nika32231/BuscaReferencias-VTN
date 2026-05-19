package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.service.SearchService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test mínimo para verificar que la búsqueda local devuelve miniaturas
 * desde `cache/thumbnails`.
 */
public class LocalThumbnailsSmokeTest {

    @Test
    void busquedaLocal_devuelveResultados_o_cache() {
        List<ImageResult> results = SearchService.searchWebThumbnailsOnly(List.of("human pose reference"), 5);

        assertNotNull(results, "La búsqueda no devolvió lista");
        assertFalse(results.isEmpty(), "No se obtuvieron resultados ni siquiera desde la caché local");
        assertTrue(results.stream().anyMatch(r -> r.getDisplayThumbnailUrl() != null && !r.getDisplayThumbnailUrl().isBlank()),
                "Las miniaturas deben tener una URL de visualización válida");
    }
}

