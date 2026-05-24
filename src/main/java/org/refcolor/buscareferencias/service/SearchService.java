package org.refcolor.buscareferencias.service;

import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.model.SimilarityBreakdown;
import org.refcolor.buscareferencias.service.MediaPipeService.ImageCacheService;
import org.refcolor.buscareferencias.utils.LocalImagePaths;
import org.refcolor.buscareferencias.utils.PoseToleranceConfig;
import org.refcolor.buscareferencias.utils.ProjectPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.refcolor.buscareferencias.database.DatabaseManager;
import org.refcolor.buscareferencias.i18n.I18n;

/**
 * Búsqueda local de fotos de referencia por similitud de pose (MediaPipe).
 *
 * <h3>Decisión de diseño: biblioteca local únicamente</h3>
 * El sistema opera exclusivamente sobre una biblioteca de imágenes local
 * configurada en {@code search.paths} dentro de {@code config.properties},
 * sin realizar consultas a APIs externas ni motores de búsqueda en línea.
 * Esta decisión fue tomada de forma consciente por las siguientes razones:
 * <ul>
 *   <li><b>Privacidad:</b> los bocetos del usuario no salen del equipo local.</li>
 *   <li><b>Disponibilidad offline:</b> la herramienta funciona sin conexión a Internet.</li>
 *   <li><b>Control del dataset:</b> el artista gestiona su propia colección de referencias.</li>
 *   <li><b>Rendimiento:</b> evita latencia de red y límites de cuota de APIs externas.</li>
 *   <li><b>Reproducibilidad:</b> los resultados son estables y no dependen de cambios en
 *       servicios de terceros.</li>
 * </ul>
 * Si en el futuro se quisiera añadir búsqueda en línea, debería implementarse como
 * un {@code SearchProvider} alternativo intercambiable, sin modificar esta clase.
 */
public class SearchService {
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    /** Similitud mínima considerada una "buena" coincidencia; continúa buscando si no se alcanza. */
    private static final double HIGH_SIMILARITY_THRESHOLD = 0.60;

    public static List<ImageResult> searchImages(List<String> terms, PoseData drawingPose) {
        return searchImages(terms, drawingPose, null, null);
    }

    public static List<ImageResult> searchImages(List<String> terms, PoseData drawingPose, Consumer<String> onStatus) {
        return searchImages(terms, drawingPose, onStatus, null);
    }

    /**
     * Busca fotos locales por similitud de pose.
     * Si tras el primer lote (search.analysis.limit) no hay ningún resultado ≥60%,
     * continúa analizando en lotes y avisa vía {@code onStatus} entre vuelta y vuelta.
     * {@code onProgress} recibe un array double[4]:
     *   [0] batchProgress  (0.0–1.0, progreso real imagen a imagen dentro del lote)
     *   [1] totalProgress  (0.0–1.0, progreso acumulado sobre todas las fotos)
     *   [2] roundNum       (número de vuelta actual, base 1)
     *   [3] totalRounds    (estimación total de vueltas)
     */
    public static List<ImageResult> searchImages(List<String> terms, PoseData drawingPose,
                                                  Consumer<String> onStatus, Consumer<double[]> onProgress) {
        List<String> normalizedTerms = normalizeTerms(terms);
        logger.info("Iniciando búsqueda local para: {}", normalizedTerms);
        return searchLocalImages(normalizedTerms, drawingPose, onStatus, onProgress);
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

    private static List<ImageResult> searchLocalImages(List<String> terms, PoseData drawingPose,
                                                        Consumer<String> onStatus, Consumer<double[]> onProgress) {
        List<ImageResult> discovered = loadLocalCandidates();
        if (discovered.isEmpty()) {
            logger.warn("No se encontraron imágenes locales en {}.", PoseToleranceConfig.localImageDir());
            if (onProgress != null) onProgress.accept(new double[]{1.0, 1.0, 1, 1});
            return List.of();
        }

        logger.info("Biblioteca local: {} fotos en {}", discovered.size(), PoseToleranceConfig.localImageDir());

        if (drawingPose == null || drawingPose.getAllJoints().isEmpty()) {
            if (onProgress != null) onProgress.accept(new double[]{1.0, 1.0, 1, 1});
            return finalizeResults(
                    prepareForDisplay(discovered, terms, PoseToleranceConfig.maxResults()),
                    false
            );
        }

        // ── Bulk-load ALL known poses (one DB query + session cache merge) ─────────
        Map<String, PoseData> bulkCache;
        try {
            bulkCache = new HashMap<>(DatabaseManager.loadAllCachedPoses());
        } catch (Exception ex) {
            logger.warn("[SEARCH] Error cargando bulk cache: {}. Se usa caché de sesión.", ex.getMessage());
            bulkCache = new HashMap<>();
        }
        bulkCache.putAll(MediaPipeService.getSessionCache());
        logger.info("[SEARCH] Bulk cache: {} poses precargadas.", bulkCache.size());

        // ── Clasificar: cacheadas (puntuación instantánea) vs necesitan Python ────
        record CachedCandidate(ImageResult candidate, PoseData pose) {}
        List<CachedCandidate> toScore = new ArrayList<>();
        List<ImageResult> needsAnalysis = new ArrayList<>();
        int total = discovered.size();
        final Map<String, PoseData> bulkCacheFinal = bulkCache;

        for (ImageResult candidate : discovered) {
            String path = LocalImagePaths.toAbsolutePath(
                    firstNonBlank(candidate.getOriginalUrl(), candidate.getThumbnailUrl(), candidate.getSourcePageUrl())
            );
            if (path == null) continue;
            PoseData cachedPose = bulkCacheFinal.get(path);
            if (cachedPose != null && !cachedPose.getAllLandmarks().isEmpty()) {
                toScore.add(new CachedCandidate(candidate, cachedPose));
            } else {
                needsAnalysis.add(candidate);
            }
        }

        // ── Puntuar cacheadas en paralelo (calculateSimilarityWithBreakdown es pura / thread-safe) ─
        final String queryStr = String.join(" ", terms);
        List<ImageResult> cachedHits = toScore.parallelStream()
                .map(cp -> {
                    SimilarityBreakdown bd = MediaPipeService.calculateSimilarityWithBreakdown(drawingPose, cp.pose());
                    ImageResult r = buildDisplayResult(cp.candidate(), bd.finalScore(), cp.pose(), queryStr);
                    r.setScoreBreakdown(bd);
                    return r;
                })
                .collect(Collectors.toList());

        // Report progress after instant cached scoring
        if (onProgress != null && total > 0) {
            onProgress.accept(new double[]{1.0, (double) cachedHits.size() / total, 0, 1});
        }

        // Conservamos el orden más-reciente-primero de loadLocalCandidates() —
        // el shuffle aleatorio destruía ese orden y hacía que fotos recién añadidas
        // (las más probablemente relevantes) no se analizasen en las primeras rondas.
        int batchSize     = PoseToleranceConfig.analysisLimitPerSearch();
        int totalUncached = needsAnalysis.size();
        int totalRounds   = totalUncached == 0 ? 1 : (int) Math.ceil((double) totalUncached / batchSize);
        // Mínimo de rondas antes de poder salir anticipadamente.
        // Garantiza que en búsquedas sucesivas (con dibujos diferentes) se analicen
        // suficientes imágenes nuevas antes de considerar que ya tenemos buen resultado.
        final int minRoundsBeforeEarlyExit = Math.min(3, totalRounds);
        final int cachedCount = cachedHits.size();

        logger.info("Cacheadas (instantáneas): {}. Sin caché (por lotes de {}): {}.",
                cachedCount, batchSize, totalUncached);

        List<ImageResult> allScored = new ArrayList<>(cachedHits);
        int analyzedSoFar = 0;
        int round = 0;

        // --- Bucle progresivo: analiza en lotes hasta encontrar ≥60% o agotar imágenes ---
        while (analyzedSoFar < totalUncached) {
            round++;
            int from = analyzedSoFar;
            int to   = Math.min(from + batchSize, totalUncached);

            if (round > 1 && onStatus != null && !hasHighSimilarityResult(allScored)) {
                onStatus.accept(I18n.fmt("status.noMatch", analyzedSoFar + cachedCount, discovered.size()));
            }

            List<ImageResult> batch = needsAnalysis.subList(from, to);
            List<String> pathsToAnalyze = new ArrayList<>();
            for (ImageResult c : batch) {
                String p = LocalImagePaths.toAbsolutePath(
                        firstNonBlank(c.getOriginalUrl(), c.getThumbnailUrl(), c.getSourcePageUrl()));
                if (p != null && !p.isBlank()) pathsToAnalyze.add(p);
            }

            // Per-image progress callback: fires after each JSON line returned by Python
            final int thisBatchSize = pathsToAnalyze.size();
            final int baseAnalyzed  = analyzedSoFar;
            final int currentRound  = round;

            java.util.Map<String, PoseData> batchPoses = MediaPipeService.analyzeImageBatch(
                pathsToAnalyze,
                imagesDone -> {
                    if (onProgress != null && total > 0) {
                        double batchPct = (double) imagesDone / Math.max(1, thisBatchSize);
                        double totalPct = Math.min(1.0, (double)(cachedCount + baseAnalyzed + imagesDone) / total);
                        onProgress.accept(new double[]{batchPct, totalPct, currentRound, totalRounds});
                    }
                }
            );

            for (ImageResult candidate : batch) {
                String path = LocalImagePaths.toAbsolutePath(
                        firstNonBlank(candidate.getOriginalUrl(), candidate.getThumbnailUrl(), candidate.getSourcePageUrl()));
                PoseData imagePose = path != null ? batchPoses.get(path) : null;
                double score = -1.0;
                SimilarityBreakdown bd = null;
                if (imagePose != null && !imagePose.getAllLandmarks().isEmpty()) {
                    bd = MediaPipeService.calculateSimilarityWithBreakdown(drawingPose, imagePose);
                    score = bd.finalScore();
                    logger.info("[SIMILARITY] {} -> {}", String.format("%.4f", score), path);
                } else {
                    logger.debug("[SEARCH] Sin pose para: {}", path);
                }
                ImageResult scored = buildDisplayResult(candidate, score, imagePose, String.join(" ", terms));
                if (imagePose != null) scored.setPoseData(imagePose);
                if (bd != null) scored.setScoreBreakdown(bd);
                allScored.add(scored);
            }

            analyzedSoFar = to;

            // Solo salir anticipadamente tras completar el mínimo de rondas requeridas.
            // Esto evita que resultados cacheados de búsquedas anteriores disparen
            // la salida antes de que se hayan analizado fotos con las partes nuevas dibujadas.
            if (round >= minRoundsBeforeEarlyExit && hasHighSimilarityResult(allScored)) {
                logger.info("[SEARCH] Resultado ≥{}% encontrado en vuelta {} (min={}). Finalizando.",
                        (int) (HIGH_SIMILARITY_THRESHOLD * 100), round, minRoundsBeforeEarlyExit);
                if (onStatus != null) onStatus.accept(I18n.t("status.matchFound"));
                break;
            }
        }

        List<ImageResult> finalized = finalizeResults(allScored, true);
        if (finalized.isEmpty() && !discovered.isEmpty()) {
            logger.warn("Ninguna foto pudo puntuarse por pose; mostrando referencias locales sin ordenar.");
            finalized = prepareForDisplay(discovered, terms, PoseToleranceConfig.maxResults());
        }

        double bestScore = allScored.stream().mapToDouble(ImageResult::getScore).filter(s -> s >= 0).max().orElse(-1);
        logger.info("Búsqueda completada. {} mostradas. Analizadas: {} de {}. Mejor: {}%.",
                finalized.size(), analyzedSoFar + cachedHits.size(), discovered.size(),
                String.format("%.0f", Math.max(0, bestScore) * 100));
        return finalized;
    }

    private static boolean hasHighSimilarityResult(List<ImageResult> results) {
        return results.stream().anyMatch(r -> r.getScore() >= HIGH_SIMILARITY_THRESHOLD);
    }

    /**
     * Ordena por similitud y devuelve entre {@link PoseToleranceConfig#minResults()} y max.
     * Si hay pocas coincidencias estrictas, relaja el umbral para llegar al mínimo.
     */
    public static List<ImageResult> finalizeResults(List<ImageResult> results, boolean rankedBySimilarity) {
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
                if (r.getScore() >= minScore) picked.add(r);
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

        if (picked.size() > max) return new ArrayList<>(picked.subList(0, max));
        return picked;
    }

    private static List<ImageResult> loadLocalCandidates() {
        List<ImageResult> results = new ArrayList<>();
        Path cacheDir = ProjectPaths.getThumbnailsDirectory();
        logger.info("Leyendo fotos desde: {}", cacheDir);
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
                try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                catch (Exception e) { return 0; }
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
        String thumbnailSource   = firstNonBlank(candidate.getThumbnailUrl(), candidate.getDisplayThumbnailUrl(), candidate.getOriginalUrl());
        String displayThumbnail  = ImageCacheService.resolveLocalPath(thumbnailSource);
        String originalUrl       = firstNonBlank(candidate.getOriginalUrl(), thumbnailSource, candidate.getSourcePageUrl());
        String sourcePageUrl     = firstNonBlank(candidate.getSourcePageUrl(), originalUrl);
        String source            = firstNonBlank(candidate.getSource(), "local");
        String title             = firstNonBlank(candidate.getTitle(), prettyTitleFromSource(originalUrl), "Referencia local");
        String query             = firstNonBlank(queryOverride, candidate.getSearchQuery());

        ImageResult result = new ImageResult(thumbnailSource, displayThumbnail, originalUrl, title,
                score, sourcePageUrl, source, query);
        result.setPoseData(poseData);
        return result;
    }

    private static List<String> normalizeTerms(List<String> terms) {
        Set<String> normalized = new LinkedHashSet<>();
        if (terms != null) {
            for (String term : terms) {
                if (term == null) continue;
                String cleaned = term.trim().replaceAll("\\s+", " ");
                if (!cleaned.isEmpty()) normalized.add(cleaned);
            }
        }
        if (normalized.isEmpty()) normalized.add("referencia de pose");
        return new ArrayList<>(normalized);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static boolean looksLikeImage(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".webp") || name.endsWith(".gif");
    }

    private static String prettyTitle(Path path) {
        return prettyTitle(path == null ? null : path.getFileName() == null ? null : path.getFileName().toString());
    }

    private static String prettyTitleFromSource(String source) {
        if (source == null || source.isBlank()) return "Referencia local";
        try {
            if (source.startsWith("file:")) return prettyTitle(Paths.get(URI.create(source)).getFileName().toString());
            Path path = Paths.get(source);
            Path fileName = path.getFileName();
            return prettyTitle(fileName == null ? source : fileName.toString());
        } catch (Exception e) {
            return prettyTitle(source);
        }
    }

    private static String prettyTitle(String rawName) {
        if (rawName == null) return "Referencia local";
        String name = rawName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replace('_', ' ').replace('-', ' ').trim();
        return name.isBlank() ? "Referencia local" : name;
    }
}
