package org.refcolor.buscareferencias.service;

import org.refcolor.buscareferencias.client.BackendSearchClient;
import org.refcolor.buscareferencias.core.FeatureFlags;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.service.MediaPipeService.ImageCacheService;
import org.refcolor.buscareferencias.utils.PlaywrightScraper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servicio para la búsqueda de imágenes.
 */
public class SearchService {
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);
    private static final ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() / 2));

    private static final int MAX_RESULTS_PER_SEARCH = 100;
    private static final int FETCH_LIMIT = 60;
    private static final int ANALYSIS_LIMIT = 24;
    private static final List<String> DEFAULT_PROVIDERS = List.of("pixabay", "pexels", "unsplash", "bing", "flickr", "playwright", "pinterest");

    public static List<ImageResult> searchImages(List<String> terms, PoseData drawingPose) {
        List<String> normalizedTerms = normalizeTerms(terms);
        logger.info("Iniciando pipeline de búsqueda robusta para: {}", normalizedTerms);

        List<ImageResult> backendResults = searchViaBackend(normalizedTerms, drawingPose, ANALYSIS_LIMIT);
        if (!backendResults.isEmpty()) {
            logger.info("Backend híbrido respondió con {} resultados.", backendResults.size());
            return backendResults;
        }

        if (!FeatureFlags.enableOnlineSearch()) {
            logger.warn("Búsqueda online desactivada por feature flag; se usará caché local si existe.");
            return fallbackFromLocalCache(normalizedTerms, ANALYSIS_LIMIT);
        }

        // Inicio de sesión de caché temporal: limpiar cualquier búsqueda anterior
        try {
            MediaPipeService.ImageCacheService.startSessionCache();
        } catch (Exception e) {
            logger.debug("No se pudo iniciar session cache: {}", e.toString());
        }

        List<ImageResult> discovered = fetchStructuredResults(normalizedTerms, Math.min(FETCH_LIMIT, MAX_RESULTS_PER_SEARCH));
        if (discovered.isEmpty()) {
            logger.warn("No se encontraron resultados online; usando caché local como fallback.");
            return fallbackFromLocalCache(normalizedTerms, ANALYSIS_LIMIT);
        }

        if (drawingPose == null || drawingPose.getAllJoints().isEmpty()) {
            return prepareForDisplay(discovered, normalizedTerms, Math.min(ANALYSIS_LIMIT, discovered.size()));
        }

        List<Future<ImageResult>> futures = new ArrayList<>();
        int limit = Math.min(discovered.size(), ANALYSIS_LIMIT);
        for (int i = 0; i < limit; i++) {
            ImageResult candidate = discovered.get(i);
            futures.add(executor.submit(() -> {
                String analysisSource = firstNonBlank(candidate.getOriginalUrl(), candidate.getThumbnailUrl(), candidate.getSourcePageUrl());
                logger.info("[ANALYSIS] Analizando imagen: {}", analysisSource);

                PoseData imagePose = null;
                double score = candidate.getScore();
                // Aceptamos URLs remotas (http/https), file: URIs o rutas locales ya descargadas.
                if (canAnalyzeImage(analysisSource)) {
                    imagePose = MediaPipeService.analyzeImage(analysisSource);
                    score = MediaPipeService.calculateSimilarity(drawingPose, imagePose);
                }

                ImageResult scored = buildDisplayResult(candidate, score, imagePose);
                if (imagePose != null) {
                    scored.setPoseData(imagePose);
                }
                return scored;
            }));
        }

        List<ImageResult> results = new ArrayList<>();
        for (Future<ImageResult> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                logger.error("Error procesando imagen", e);
            }
        }

        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        logger.info("Pipeline completado. {} imágenes procesadas.", results.size());
        return results;
    }

    /**
     * Modo de prueba mínimo: devuelve imágenes para la galería SIN análisis MediaPipe.
     * Objetivo: validar acceso web real (JS + lazy loading) mediante Playwright (Python).
     */
    public static List<ImageResult> searchWebThumbnailsOnly(List<String> terms, int limit) {
        List<String> normalizedTerms = normalizeTerms(terms);
        int safeLimit = Math.max(1, Math.min(MAX_RESULTS_PER_SEARCH, limit));

        List<ImageResult> backendResults = searchViaBackend(normalizedTerms, null, safeLimit);
        if (!backendResults.isEmpty()) {
            logger.info("Backend híbrido respondió con {} thumbnails.", backendResults.size());
            return backendResults;
        }

        // Aseguramos sesión de caché temporal también para el modo thumbnails
        try { MediaPipeService.ImageCacheService.startSessionCache(); } catch (Exception ignored) {}
        List<ImageResult> discovered = fetchStructuredResults(normalizedTerms, Math.max(safeLimit, FETCH_LIMIT / 2));

        if (discovered.isEmpty()) {
            logger.warn("Modo thumbnails: sin resultados online, usando caché local.");
            return fallbackFromLocalCache(normalizedTerms, safeLimit);
        }

        return prepareForDisplay(discovered, normalizedTerms, Math.min(safeLimit, discovered.size()));
    }

    private static List<ImageResult> searchViaBackend(List<String> terms, PoseData drawingPose, int limit) {
        String baseUrl = FeatureFlags.backendBaseUrl();
        boolean backendEnabled = FeatureFlags.enableBackendHybrid() && baseUrl != null && !baseUrl.isBlank();
        if (!backendEnabled) {
            return List.of();
        }

        try {
            BackendSearchClient client = new BackendSearchClient(baseUrl, Duration.ofSeconds(FeatureFlags.backendRequestTimeoutSeconds()));
            List<ImageResult> results = client.searchReferences(terms, drawingPose, DEFAULT_PROVIDERS, limit, null);
            if (results == null || results.isEmpty()) {
                return List.of();
            }
            return results;
        } catch (BackendSearchClient.BackendRequestException e) {
            logger.warn("Backend híbrido no disponible; se usará el modo local. Detalle: {}", e.toString());
            return List.of();
        } catch (Exception e) {
            logger.warn("Error inesperado consultando el backend híbrido; se usará el modo local. Detalle: {}", e.toString());
            return List.of();
        }
    }

    private static List<ImageResult> fetchStructuredResults(List<String> terms, int limit) {
        try (PlaywrightScraper scraper = new PlaywrightScraper()) {
            return scraper.searchVisualReferences(terms, limit, DEFAULT_PROVIDERS);
        } catch (Exception e) {
            logger.error("Error en PlaywrightScraper estructurado", e);
            return List.of();
        }
    }

    private static List<ImageResult> prepareForDisplay(List<ImageResult> candidates, List<String> terms, int limit) {
        List<ImageResult> out = new ArrayList<>();
        int max = Math.min(Math.max(1, limit), candidates.size());
        String query = String.join(" ", terms);

        for (int i = 0; i < max; i++) {
            ImageResult candidate = candidates.get(i);
            out.add(buildDisplayResult(candidate, candidate.getScore(), candidate.getPoseData(), query));
        }

        return out;
    }

    private static ImageResult buildDisplayResult(ImageResult candidate, double score, PoseData poseData) {
        return buildDisplayResult(candidate, score, poseData, candidate.getSearchQuery());
    }

    private static ImageResult buildDisplayResult(ImageResult candidate, double score, PoseData poseData, String queryOverride) {
        String thumbnailSource = firstNonBlank(candidate.getThumbnailUrl(), candidate.getDisplayThumbnailUrl(), candidate.getOriginalUrl());
        String displayThumbnail = ImageCacheService.getLocalThumbnailPath(thumbnailSource);
        String originalUrl = firstNonBlank(candidate.getOriginalUrl(), thumbnailSource, candidate.getSourcePageUrl());
        String sourcePageUrl = firstNonBlank(candidate.getSourcePageUrl(), originalUrl);
        String source = firstNonBlank(candidate.getSource(), "online");
        String title = firstNonBlank(candidate.getTitle(), source, "Referencia");
        String query = firstNonBlank(queryOverride, candidate.getSearchQuery());

        ImageResult result = new ImageResult(
                thumbnailSource,
                displayThumbnail,
                originalUrl,
                title,
                score,
                sourcePageUrl,
                source,
                query
        );
        result.setPoseData(poseData);
        return result;
    }

    private static List<ImageResult> fallbackFromLocalCache(List<String> terms, int limit) {
        List<ImageResult> results = new ArrayList<>();
        Path cacheDir = Paths.get("cache", "thumbnails");
        if (!Files.isDirectory(cacheDir)) {
            return results;
        }

        String query = String.join(" ", terms);
        try (Stream<Path> stream = Files.list(cacheDir)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(SearchService::looksLikeImage)
                    .limit(Math.max(1, limit))
                    .forEach(path -> {
                        String uri = path.toUri().toString();
                        results.add(new ImageResult(uri, uri, uri, "Cache local", 0.0, uri, "local-cache", query));
                    });
        } catch (Exception e) {
            logger.warn("No se pudo leer la caché local de miniaturas", e);
        }

        if (results.isEmpty()) {
            logger.warn("La caché local está vacía o no contiene imágenes válidas.");
        }
        return results;
    }

    private static List<String> normalizeTerms(List<String> terms) {
        Set<String> normalized = new LinkedHashSet<>();
        if (terms != null) {
            for (String term : terms) {
                if (term == null) continue;
                String cleaned = term.trim().replaceAll("\\s+", " ");
                if (!cleaned.isEmpty()) {
                    normalized.add(cleaned);
                }
            }
        }
        if (normalized.isEmpty()) {
            normalized.add("human pose reference");
        }
        return new ArrayList<>(normalized);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }


    /**
     * Decide si podemos analizar la imagen con MediaPipe. Acepta:
     * - URLs remotas http/https
     * - URIs file: (file:///...)
     * - Rutas locales existentes
     */
    private static boolean canAnalyzeImage(String source) {
        if (source == null || source.isBlank()) return false;
        String s = source.trim();
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file:")) return true;
        try {
            Path p = Paths.get(s);
            return Files.exists(p);
        } catch (Exception e) {
            try {
                java.net.URI u = new java.net.URI(s);
                if ("file".equalsIgnoreCase(u.getScheme())) {
                    Path p = Paths.get(u);
                    return Files.exists(p);
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static boolean looksLikeImage(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".gif");
    }
}
