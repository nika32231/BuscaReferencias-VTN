package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.client.PythonImageSearchClient;
import org.refcolor.buscareferencias.service.SearchService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de integración mínimo para verificar que:
 * - Python está disponible o el test se omite de forma segura
 * - el puente Java -> Python responde
 * - se obtienen miniaturas o caché local sin depender de la API Java de Playwright
 */
public class PlaywrightGoogleImagesE2ETest {

    @Test
    void puentePython_devuelveResultados_o_caché() {
        Assumptions.assumeTrue(PythonImageSearchClient.probePythonVersion().succeeded(), "Python no disponible; se omite el test de integración.");

        List<ImageResult> results = SearchService.searchWebThumbnailsOnly(List.of("human pose reference"), 5);

        assertTrue(results != null, "La búsqueda no devolvió lista");
        assertTrue(!results.isEmpty(), "No se obtuvieron resultados ni siquiera desde la caché local");
        assertTrue(results.stream().anyMatch(r -> r.getDisplayThumbnailUrl() != null && !r.getDisplayThumbnailUrl().isBlank()),
                "Las miniaturas deben tener una URL de visualización válida");
    }
}
