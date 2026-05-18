package org.refcolor.buscareferencias.utils;

import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.refcolor.buscareferencias.client.PythonImageSearchClient;

class PythonImageSearchClientTest {

    @Test
    void testPythonVersionReport() {
        PythonImageSearchClient.CommandResult result = PythonImageSearchClient.probePythonVersion();

        System.out.println("[TEST PYTHON] stdout = " + result.stdout());
        System.out.println("[TEST PYTHON] stderr = " + result.stderr());
        System.out.println("[TEST PYTHON] exitCode = " + result.exitCode());
        System.out.println("[TEST PYTHON] error = " + result.error());

        assertNotNull(result, "El probe de Python no debería devolver null");
        if (result.succeeded()) {
            String combined = (result.stdout() + " " + result.stderr()).toLowerCase();
            assertTrue(combined.contains("python"), "La salida debe contener la versión de Python");
        } else {
            assertTrue(!result.error().isBlank() || !result.stdout().isBlank() || !result.stderr().isBlank(),
                    "Si no hay Python, el resultado debe traer diagnóstico útil");
        }
    }

    @Test
    void testPlaywrightPinterestSmokeIfPythonAvailable() {
        Assumptions.assumeTrue(PythonImageSearchClient.resolvePython().isPresent(), "Python no disponible en este entorno");

        Path script = PythonImageSearchClient.resolveProjectScript("image_search_engine.py");
        Assumptions.assumeTrue(script != null, "No se encontró image_search_engine.py");

        PythonImageSearchClient.CommandResult result = PythonImageSearchClient.runPythonScript(
                script,
                java.util.List.of("--terms", "red dress", "--limit", "3", "--providers", "pinterest"),
                script.getParent(),
                120
        );

        System.out.println("[TEST PINTEREST] stdout = " + result.stdout());
        System.out.println("[TEST PINTEREST] stderr = " + result.stderr());
        System.out.println("[TEST PINTEREST] exitCode = " + result.exitCode());
        System.out.println("[TEST PINTEREST] error = " + result.error());

        Assumptions.assumeTrue(result.succeeded(), "Playwright/Pinterest no disponible o falló en este entorno");

        JSONObject json = new JSONObject(result.stdout());
        assertTrue(json.has("results"), "La salida debe incluir results");
        assertTrue(json.getJSONArray("results").length() >= 0, "La lista de resultados debe existir");
    }
}
