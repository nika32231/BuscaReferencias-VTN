package org.refcolor.buscareferencias.utils;

import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.client.PythonImageSearchClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Puente hacia el engine de Python.
 *
 * Nota: el nombre se mantiene por compatibilidad, pero ahora el engine es:
 * - Playwright (Python)
 * - + APIs (Unsplash/Pexels/Pixabay/Bing/Flickr)
 */
public class PlaywrightScraper implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PlaywrightScraper.class);
    
    // Timeout de 60 segundos para búsquedas web (Playwright puede ser lento)
    private static final int SEARCH_TIMEOUT_SECONDS = 60;
    private static final int SEARCH_CACHE_TTL_SECONDS = 180;

    public PlaywrightScraper() {
        // No requiere inicialización pesada en Java
    }

    public List<String> searchPinterest(String query) {
        return extractUrls(searchVisualReferences(List.of(query), 12, List.of("pinterest")));
    }

    public List<String> searchGoogleImages(String query) {
        return extractUrls(searchVisualReferences(List.of(query), 12, List.of("pixabay", "pexels", "unsplash", "bing", "flickr", "playwright", "pinterest")));
    }

    public List<ImageResult> searchVisualReferences(List<String> terms, int limit, List<String> providers) {
        List<ImageResult> results = new ArrayList<>();
        try {
            Path scriptPath = PythonImageSearchClient.resolveProjectScript("image_search_engine.py");
            if (scriptPath == null) {
                logger.error("No se encontró image_search_engine.py");
                return results;
            }

            logger.info("[SEARCH] Llamando a image_search_engine.py: {} con providers: {}", terms, providers);

            List<String> baseArgs = new ArrayList<>();
            baseArgs.add("--terms");
            baseArgs.addAll(terms == null || terms.isEmpty() ? List.of("human pose reference") : terms);
            baseArgs.add("--limit");
            baseArgs.add(String.valueOf(limit));
            baseArgs.add("--providers");
            baseArgs.addAll(providers == null || providers.isEmpty()
                    ? List.of("pixabay", "pexels", "unsplash", "bing", "flickr", "playwright", "pinterest")
                    : providers);
            // Cache temporal muy corta por sesion para evitar resultados viejos persistentes.
            baseArgs.add("--ttl");
            baseArgs.add(String.valueOf(SEARCH_CACHE_TTL_SECONDS));
            baseArgs.add("--session-id");
            baseArgs.add(UUID.randomUUID().toString());
            baseArgs.add("--fresh");

            PythonImageSearchClient.CommandResult result = PythonImageSearchClient.runPythonScript(
                    scriptPath,
                    baseArgs,
                    scriptPath.getParent(),
                    SEARCH_TIMEOUT_SECONDS
            );

            if (result.succeeded()) {
                String outStr = result.stdout();
                logger.debug("[SEARCH] Salida JSON recibida: {} bytes", outStr.length());
                
                if (outStr.startsWith("{")) {
                    try {
                        JSONObject json = new JSONObject(outStr);
                        if (json.has("results")) {
                            JSONArray array = json.getJSONArray("results");
                            for (int i = 0; i < array.length(); i++) {
                                JSONObject r = array.getJSONObject(i);
                                results.add(parseImageResult(r, terms));
                            }
                            JSONObject meta = json.optJSONObject("meta");
                            if (meta != null && meta.has("errors")) {
                                JSONObject errors = meta.optJSONObject("errors");
                                if (errors != null && !errors.isEmpty()) {
                                    logger.info("[SEARCH] Errores parciales de proveedores: {}", errors);
                                }
                            }
                            logger.info("[SEARCH] Se extrajeron {} resultados estructurados.", results.size());
                        } else if (json.has("error")) {
                            logger.warn("[SEARCH] Error en engine Python: {}", json.optString("error", "unknown"));
                        }
                    } catch (Exception e) {
                        logger.error("[SEARCH] Error parseando JSON: {}", e.toString());
                    }
                } else {
                    logger.error("[SEARCH] Salida inesperada (no JSON). Primeros 200 caracteres: {}", 
                            outStr.substring(0, Math.min(200, outStr.length())));
                }
            } else {
                logger.warn("[SEARCH] Falló la ejecución Python. started={} exitCode={} error={}",
                        result.started(), result.exitCode(), result.error());
                if (!result.stderr().isBlank()) {
                    logger.warn("[SEARCH] stderr: {}", result.stderr().substring(0, Math.min(500, result.stderr().length())));
                }
                if (!result.stdout().isBlank()) {
                    logger.warn("[SEARCH] stdout: {}", result.stdout().substring(0, Math.min(500, result.stdout().length())));
                }
            }
        } catch (Exception e) {
            logger.error("[SEARCH] Error ejecutando engine Python", e);
        }
        return results;
    }

    private List<String> extractUrls(List<ImageResult> results) {
        List<String> urls = new ArrayList<>();
        for (ImageResult result : results) {
            String url = firstNonBlank(result.getOriginalUrl(), result.getThumbnailUrl(), result.getDisplayThumbnailUrl(), result.getSourcePageUrl());
            if (!url.isBlank()) {
                urls.add(url);
            }
        }
        return urls;
    }

    private ImageResult parseImageResult(JSONObject r, List<String> terms) {
        String query = terms == null ? "" : String.join(" ", terms);
        String thumbnailUrl = firstNonBlank(
                r.optString("thumbnail_url", null),
                r.optString("thumbnailUrl", null),
                r.optString("thumbnail", null),
                r.optString("imageUrl", null),
                r.optString("original_url", null)
        );
        String imageUrl = firstNonBlank(
                r.optString("original_url", null),
                r.optString("imageUrl", null),
                thumbnailUrl
        );
        String sourcePageUrl = firstNonBlank(
                r.optString("page_url", null),
                r.optString("sourcePageUrl", null),
                imageUrl
        );
        String source = firstNonBlank(
                r.optString("source", null),
                r.optString("provider", null)
        );
        String title = firstNonBlank(r.optString("title", null), query, sourcePageUrl);
        double similarity = r.has("similarity") ? r.optDouble("similarity", 0.0) : r.optDouble("score", 0.0);

        return new ImageResult(
                thumbnailUrl,
                thumbnailUrl,
                imageUrl,
                title,
                similarity,
                sourcePageUrl,
                source,
                query
        );
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @Override
    public void close() {
        // No hay recursos nativos que cerrar desde el lado Java
    }
}
