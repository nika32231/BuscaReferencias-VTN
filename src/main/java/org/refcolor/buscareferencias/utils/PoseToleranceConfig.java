package org.refcolor.buscareferencias.utils;

import org.refcolor.buscareferencias.core.FeatureFlags;
import org.refcolor.buscareferencias.model.AnatomyPart;

/**
 * Rangos de tolerancia para búsqueda local de poses similares (no exactas).
 * Valores mayores = más permisivo alrededor de cada parte del cuerpo.
 */
public final class PoseToleranceConfig {

    private static final double DEFAULT_SKELETON  = 1.5;   // era 2.5 — más estricto para distinguir poses
    private static final double DEFAULT_ARM_ANGLE = 60.0;   // era 360.0 — >60° de diferencia = 0 score
    private static final double DEFAULT_LEG_ANGLE = 90.0;   // era 360.0 — >90° de diferencia = 0 score
    private static final int DEFAULT_MIN_RESULTS = 5;
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final int DEFAULT_ANALYSIS_LIMIT = 400;

    private PoseToleranceConfig() {
    }

    /**
     * Distancia máxima normalizada aceptada alrededor de la posición de una parte.
     */
    public static double skeletonTolerance(AnatomyPart part) {
        if (part != null) {
            Double partValue = parseDouble(FeatureFlags.getRaw("tolerance.skeleton." + part.name().toLowerCase()));
            if (partValue != null && partValue > 0) {
                return partValue;
            }
        }
        Double defaultValue = parseDouble(FeatureFlags.getRaw("tolerance.skeleton.default"));
        return defaultValue != null && defaultValue > 0 ? defaultValue : DEFAULT_SKELETON;
    }

    public static double armAngleTolerance() {
        Double value = parseDouble(FeatureFlags.getRaw("tolerance.angle.arm"));
        return value != null && value > 0 ? value : DEFAULT_ARM_ANGLE;
    }

    public static double legAngleTolerance() {
        Double value = parseDouble(FeatureFlags.getRaw("tolerance.angle.leg"));
        return value != null && value > 0 ? value : DEFAULT_LEG_ANGLE;
    }

    public static double minSimilarityScore() {
        Double value = parseDouble(FeatureFlags.getRaw("search.min.score"));
        if (value == null) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static String localImageDir() {
        String dir = FeatureFlags.getRaw("search.local.dir");
        return dir != null && !dir.isBlank() ? dir.trim() : "cache/thumbnails";
    }

    public static int minResults() {
        return parseInt(FeatureFlags.getRaw("search.min.results"), DEFAULT_MIN_RESULTS, 1, 50);
    }

    public static int maxResults() {
        int max = parseInt(FeatureFlags.getRaw("search.max.results"), DEFAULT_MAX_RESULTS, 1, 100);
        return Math.max(max, minResults());
    }

    /** Cuántas fotos nuevas analizar con MediaPipe por búsqueda (las ya cacheadas no cuentan). */
    public static int analysisLimitPerSearch() {
        // El ajuste del usuario (AppSettings) tiene prioridad sobre el feature flag
        int userSetting = org.refcolor.buscareferencias.settings.AppSettings.getPhotosPerRound();
        if (userSetting > 0) return Math.max(50, Math.min(5000, userSetting));
        return parseInt(FeatureFlags.getRaw("search.analysis.limit"), DEFAULT_ANALYSIS_LIMIT, 50, 5000);
    }

    private static int parseInt(String raw, int defaultValue, int min, int max) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
