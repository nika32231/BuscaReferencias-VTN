package org.refcolor.buscareferencias.service;

import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.service.MediaPipeService.ImageCacheService;
import org.refcolor.buscareferencias.utils.LocalImagePaths;
import org.refcolor.buscareferencias.utils.PoseToleranceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Búsqueda local de fotos de referencia por similitud de pose (MediaPipe).
 */
public class SearchService {
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);
    private static final ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() / 2));

    public static List<ImageResult> searchImages(List<String> terms, PoseData drawingPose) {
        List<String> normalizedTerms = normalizeTerms(terms);
        logger.info("Iniciando búsqueda local para: {}", normalizedTerms);
        return searchLocalImages(normalizedTerms, drawingPose);
    }

    /**
     * Devuelve fotos locales sin comparar pose (orden por fecha de archivo).
     */
    public static List<ImageResult> searchLocalPhotos(List<String> terms, int limit) {
        List<String> normalizedTerms = normalizeTerms(terms);
        int capped = Math.max(PoseToleranceConfig.minResults(), Math.min(limit, PoseToleranceConfig.maxResults()));
        return finalizeResults(
                prepareForDisplay(loadLocalCandidates(), normalizedTerms, capped),
                false
        );
    }

    private static List<ImageResult> searchLocalImages(List<String> terms, PoseData drawingPose) {
        List<ImageResult> discovered = loadLocalCandidates();
        if (discovered.isEmpty()) {
            logger.warn("No se encontraron imágenes locales en {}.", PoseToleranceConfig.localImageDir());
            return List.of();
        }

        logger.info("Biblioteca local: {} fotos en {}", discovered.size(), PoseToleranceConfig.localImageDir());

        if (drawingPose == null || drawingPose.getAllJoints().isEmpty()) {
            return finalizeResults(
                    prepareForDisplay(discovered, terms, PoseToleranceConfig.maxResults()),
                    false
            );
        }

        List<ImageResult> cachedHits = new ArrayList<>();
        List<ImageResult> needsAnalysis = new ArrayList<>();

        for (ImageResult candidate : discovered) {
            String path = LocalImagePaths.toAbsolutePath(
                    firstNonBlank(candidate.getOriginalUrl(), candidate.getThumbnailUrl(), candidate.getSourcePageUrl())
            );
            if (path == null) {
                continue;
            }
            PoseData cachedPose = MediaPipeService.peekCachedPose(path);
            if (cachedPose != null && !cachedPose.getAllLandmarks().isEmpty()) {
                double score = MediaPipeService.calculateSimilarity(drawingPose, cachedPose);
                ImageResult scored = buildDisplayResult(candidate, score, cachedPose, String.join(" ", terms));
                cachedHits.add(scored);
            } else {
                needsAnalysis.add(candidate);
            }
        }

        Collections.shuffle(needsAnalysis);
        int analysisBudget = PoseToleranceConfig.analysisLimitPerSearch();
        int toAnalyze = Math.min(needsAnalysis.size(), analysisBudget);

        logger.info("Puntuadas al instante (caché): {}. Por analizar ahora: {}/{}.",
                cachedHits.size(), toAnalyze, needsAnalysis.size());

        List<Future<ImageResult>> futures = new ArrayList<>();
        for (int i = 0; i < toAnalyze; i++) {
            ImageResult candidate = needsAnalysis.get(i);
            futures.add(executor.submit(() -> scoreCandidate(candidate, drawingPose, terms)));
        }

        List<ImageResult> results = new ArrayList<>(cachedHits);
        for (Future<ImageResult> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                logger.error("Error procesando imagen local", e);
            }
        }

        List<ImageResult> finalized = finalizeResults(results, true);
        logger.info("Búsqueda local completada. {} imágenes mostradas (de {} en carpeta).",
                finalized.size(), discovered.size());
        return finalized;
    }

    private static ImageResult scoreCandidate(ImageResult candidate, PoseData drawingPose, List<String> terms) {
        try {
            String analysisSource = LocalImagePaths.toAbsolutePath(
                    firstNonBlank(candidate.getOriginalUrl(), candidate.getThumbnailUrl(), candidate.getSourcePageUrl())
            );
            if (analysisSource == null) {
                return buildDisplayResult(candidate, -1.0, null, String.join(" ", terms));
            }

            PoseData imagePose;
            try {
                imagePose = MediaPipeService.analyzeImage(analysisSource);
            } catch (Exception e) {
                logger.warn("[SEARCH] Error en análisis: {}", analysisSource);
                return buildDisplayResult(candidate, -1.0, null, String.join(" ", terms));
            }

            double score = -1.0;
            if (imagePose != null && !imagePose.getAllLandmarks().isEmpty()) {
                score = MediaPipeService.calculateSimilarity(drawingPose, imagePose);
                logger.info("[SIMILARITY] {} -> {}", String.format("%.4f", score), analysisSource);
            } else {
                logger.warn("[SEARCH] Pose vacía para: {}", analysisSource);
            }

            ImageResult scored = buildDisplayResult(candidate, score, imagePose, String.join(" ", terms));
            if (imagePose != null) {
                scored.setPoseData(imagePose);
            }
            return scored;
        } catch (Exception e) {
            logger.error("[SEARCH] Error inesperado: {}", e.toString());
            return buildDisplayResult(candidate, -1.0, null, String.join(" ", terms));
        }
    }

    /**
     * Ordena por similitud y devuelve entre {@link PoseToleranceConfig#minResults()} y max.
     * Si hay pocas coincidencias estrictas, relaja el umbral para llegar al mínimo.
     */
    static List<ImageResult> finalizeResults(List<ImageResult> results, boolean rankedBySimilarity) {
        List<ImageResult> valid = results.stream()
                .filter(r -> r.getScore() >= 0)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toCollection(ArrayList::new));

        int min = PoseToleranceConfig.minResults();
        int max = PoseToleranceConfig.maxResults();
        double minScore = PoseToleranceConfig.minSimilarityScore();

        List<ImageResult> picked = new ArrayList<>();
        if (rankedBySimilarity) {
            for (ImageResult r : valid) {
                if (r.getScore() >= minScore) {
                    picked.add(r);
                }
                if (picked.size() >= max) break;
            }
            if (picked.size() < min) {
                picked.clear();
                for (ImageResult r : valid) {
                    picked.add(r);
                    if (picked.size() >= min) break;
                }
            }
        } else {
            for (ImageResult r : valid) {
                picked.add(r);
                if (picked.size() >= max) break;
            }
        }

        if (picked.size() > max) {
            return new ArrayList<>(picked.subList(0, max));
        }
        return picked;
    }

    private static List<ImageResult> loadLocalCandidates() {
        List<ImageResult> results = new ArrayList<>();
        Path cacheDir = Paths.get(PoseToleranceConfig.localImageDir());
        if (!Files.isDirectory(cacheDir)) {
            try {
                Files.createDirectories(cacheDir);
                logger.info("Carpeta de referencias creada: {}", cacheDir.toAbsolutePath());
            } catch (Exception e) {
                logger.warn("No se pudo crear la carpeta de fotos locales", e);
            }
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

            for (Path path : files) {
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
            out.add(buildDisplayResult(candidate, Math.max(0, candidate.getScore()), candidate.getPoseData(), query));
        }

        return out;
    }

    private static ImageResult buildDisplayResult(ImageResult candidate, double score, PoseData poseData, String queryOverride) {
        String thumbnailSource = firstNonBlank(candidate.getThumbnailUrl(), candidate.getDisplayThumbnailUrl(), candidate.getOriginalUrl());
        String displayThumbnail = ImageCacheService.resolveLocalPath(thumbnailSource);
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
                return prettyTitle(Paths.get(URI.create(source)).getFileName().toString());
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
