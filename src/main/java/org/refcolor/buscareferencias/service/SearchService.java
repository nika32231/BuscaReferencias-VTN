package org.refcolor.buscareferencias.service;

import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.service.MediaPipeService.ImageCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Servicio de búsqueda local de imágenes.
 */
public class SearchService {
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);
    private static final ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() / 2));

    private static final int MAX_RESULTS_PER_SEARCH = 100;
    private static final int ANALYSIS_LIMIT = 24;

    public static List<ImageResult> searchImages(List<String> terms, PoseData drawingPose) {
        List<String> normalizedTerms = normalizeTerms(terms);
        logger.info("Iniciando búsqueda local para: {}", normalizedTerms);
        return searchLocalImages(normalizedTerms, drawingPose, ANALYSIS_LIMIT);
    }

    /**
     * Mantiene la firma histórica, pero ahora devuelve únicamente fotos locales.
     */
    public static List<ImageResult> searchWebThumbnailsOnly(List<String> terms, int limit) {
        List<String> normalizedTerms = normalizeTerms(terms);
        return searchLocalImages(normalizedTerms, null, Math.max(1, Math.min(MAX_RESULTS_PER_SEARCH, limit)));
    }

    private static List<ImageResult> searchLocalImages(List<String> terms, PoseData drawingPose, int limit) {
        List<ImageResult> discovered = loadLocalCandidates(limit);
        if (discovered.isEmpty()) {
            logger.warn("No se encontraron imágenes locales en cache/thumbnails.");
            return List.of();
        }

        if (drawingPose == null || drawingPose.getAllJoints().isEmpty()) {
            return prepareForDisplay(discovered, terms, Math.min(limit, discovered.size()));
        }

        List<Future<ImageResult>> futures = new ArrayList<>();
        int maxToAnalyze = Math.min(discovered.size(), limit);
        for (int i = 0; i < maxToAnalyze; i++) {
            ImageResult candidate = discovered.get(i);
            futures.add(executor.submit(() -> {
                String analysisSource = firstNonBlank(candidate.getOriginalUrl(), candidate.getThumbnailUrl(), candidate.getSourcePageUrl());
                PoseData imagePose = null;
                double score = candidate.getScore();

                if (canAnalyzeImage(analysisSource)) {
                    imagePose = MediaPipeService.analyzeImage(analysisSource);
                    score = MediaPipeService.calculateSimilarity(drawingPose, imagePose);
                    logger.info("[SIMILARITY] Score calculated {} for {}", String.format("%.4f", score), analysisSource);
                }

                ImageResult scored = buildDisplayResult(candidate, score, imagePose, String.join(" ", terms));
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
                logger.error("Error procesando imagen local", e);
            }
        }

        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        logger.info("Búsqueda local completada. {} imágenes procesadas.", results.size());
        return results;
    }

    private static List<ImageResult> loadLocalCandidates(int limit) {
        List<ImageResult> results = new ArrayList<>();
        Path cacheDir = Paths.get("cache", "thumbnails");
        if (!Files.isDirectory(cacheDir)) {
            return results;
        }

        try (Stream<Path> stream = Files.list(cacheDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(SearchService::looksLikeImage)
                    .collect(Collectors.toList());

            files.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (Exception e) {
                    return 0;
                }
            });

            for (Path path : files.stream().limit(Math.max(1, limit)).toList()) {
                String uri = path.toUri().toString();
                results.add(new ImageResult(uri, uri, uri, prettyTitle(path), 0.0, uri, "local", ""));
            }
        } catch (Exception e) {
            logger.warn("No se pudo leer la carpeta de fotos locales", e);
        }

        return results;
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

    private static ImageResult buildDisplayResult(ImageResult candidate, double score, PoseData poseData, String queryOverride) {
        String thumbnailSource = firstNonBlank(candidate.getThumbnailUrl(), candidate.getDisplayThumbnailUrl(), candidate.getOriginalUrl());
        String displayThumbnail = ImageCacheService.getLocalThumbnailPath(thumbnailSource);
        String originalUrl = firstNonBlank(candidate.getOriginalUrl(), thumbnailSource, candidate.getSourcePageUrl());
        String sourcePageUrl = firstNonBlank(candidate.getSourcePageUrl(), originalUrl);
        String source = firstNonBlank(candidate.getSource(), "local");
        String title = firstNonBlank(candidate.getTitle(), prettyTitleFromSource(originalUrl), "Referencia local");
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
            normalized.add("referencia de pose");
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
     * Decide si podemos analizar la imagen con MediaPipe. Acepta rutas locales y URIs file:.
     */
    private static boolean canAnalyzeImage(String source) {
        if (source == null || source.isBlank()) return false;
        String s = source.trim();
        if (s.startsWith("file:")) return true;

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

    private static String prettyTitle(Path path) {
        return prettyTitle(path == null ? null : path.getFileName() == null ? null : path.getFileName().toString());
    }

    private static String prettyTitleFromSource(String source) {
        if (source == null || source.isBlank()) {
            return "Referencia local";
        }

        try {
            if (source.startsWith("file:")) {
                return prettyTitle(Paths.get(java.net.URI.create(source)).getFileName().toString());
            }
            Path path = Paths.get(source);
            Path fileName = path.getFileName();
            return prettyTitle(fileName == null ? source : fileName.toString());
        } catch (Exception e) {
            return prettyTitle(source);
        }
    }

    private static String prettyTitle(String rawName) {
        if (rawName == null) {
            return "Referencia local";
        }

        String name = rawName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replace('_', ' ').replace('-', ' ').trim();
        return name.isBlank() ? "Referencia local" : name;
    }
}
