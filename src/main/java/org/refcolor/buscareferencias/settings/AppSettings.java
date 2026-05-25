package org.refcolor.buscareferencias.settings;

import org.refcolor.buscareferencias.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ajustes persistentes de la aplicación almacenados en la tabla AppSettings (SQLite).
 * Uso: AppSettings.getPhotosPerRound(), AppSettings.setDarkTheme(true), etc.
 */
public class AppSettings {

    private static final Logger logger = LoggerFactory.getLogger(AppSettings.class);

    // ── Claves ────────────────────────────────────────────────────────────────
    private static final String KEY_PHOTOS_PER_ROUND = "photos_per_round";
    private static final String KEY_DARK_THEME       = "dark_theme";
    private static final String KEY_SOUND_ENABLED    = "sound_enabled";

    // ── Cache en memoria (se carga al primer acceso) ──────────────────────────
    private static volatile int     photosPerRound = 100;
    private static volatile boolean darkTheme      = true;
    private static volatile boolean soundEnabled   = false;
    private static volatile boolean loaded         = false;

    // ── Carga ─────────────────────────────────────────────────────────────────

    public static synchronized void load() {
        if (loaded) return;
        try {
            photosPerRound = Integer.parseInt(
                DatabaseManager.getSetting(KEY_PHOTOS_PER_ROUND, "100"));
            darkTheme    = Boolean.parseBoolean(
                DatabaseManager.getSetting(KEY_DARK_THEME, "true"));
            soundEnabled = Boolean.parseBoolean(
                DatabaseManager.getSetting(KEY_SOUND_ENABLED, "false"));
            loaded = true;
            logger.info("[SETTINGS] Cargados: photosPerRound={} darkTheme={} sound={}",
                photosPerRound, darkTheme, soundEnabled);
        } catch (Exception e) {
            logger.warn("[SETTINGS] Error cargando (usando defaults): {}", e.getMessage());
            loaded = true;
        }
    }

    /** Fuerza recarga desde la BD (útil tras cambios externos). */
    public static synchronized void reload() {
        loaded = false;
        load();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public static int getPhotosPerRound() {
        if (!loaded) load();
        return photosPerRound;
    }

    public static boolean isDarkTheme() {
        if (!loaded) load();
        return darkTheme;
    }

    public static boolean isSoundEnabled() {
        if (!loaded) load();
        return soundEnabled;
    }

    // ── Setters (persisten en BD) ─────────────────────────────────────────────

    public static void setPhotosPerRound(int n) {
        photosPerRound = Math.max(10, Math.min(500, n));
        DatabaseManager.setSetting(KEY_PHOTOS_PER_ROUND, String.valueOf(photosPerRound));
        logger.info("[SETTINGS] photosPerRound={}", photosPerRound);
    }

    public static void setDarkTheme(boolean dark) {
        darkTheme = dark;
        DatabaseManager.setSetting(KEY_DARK_THEME, String.valueOf(dark));
        logger.info("[SETTINGS] darkTheme={}", dark);
    }

    public static void setSoundEnabled(boolean enabled) {
        soundEnabled = enabled;
        DatabaseManager.setSetting(KEY_SOUND_ENABLED, String.valueOf(enabled));
        logger.info("[SETTINGS] soundEnabled={}", enabled);
    }
}
