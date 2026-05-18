package org.refcolor.buscareferencias.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Feature flags centralizadas.
 *
 * Precedencia (de mayor a menor):
 * 1) System property: -Dfeatures.playwright.enabled=false
 * 2) Env var: APP_FEATURES_PLAYWRIGHT_ENABLED=false
 * 3) classpath: /app.properties
 * 4) default
 */
public final class FeatureFlags {
    private static final Logger logger = LoggerFactory.getLogger(FeatureFlags.class);

    private static final Properties CLASSPATH_PROPS = loadClasspathProps();

    private FeatureFlags() {
    }

    public static boolean isSafeMode() {
        return getBoolean("app.safeMode", true);
    }

    public static boolean forceFallbackUi() {
        return getBoolean("ui.forceFallback", false);
    }

    public static boolean enableDbInitOnStartup() {
        return getBoolean("features.dbInitOnStartup", false);
    }

    public static boolean enableDepsCheckOnStartup() {
        return getBoolean("features.depsCheckOnStartup", false);
    }

    public static boolean enablePlaywright() {
        return getBoolean("features.playwright.enabled", false);
    }

    public static boolean enableOnlineSearch() {
        return getBoolean("features.onlineSearch.enabled", false);
    }

    public static boolean enableBackendHybrid() {
        return getBoolean("backend.enabled", false);
    }

    public static String backendBaseUrl() {
        String raw = getRaw("backend.baseUrl");
        return raw == null ? "" : raw.trim();
    }

    public static long backendRequestTimeoutSeconds() {
        String raw = getRaw("backend.requestTimeoutSeconds");
        if (raw == null || raw.isBlank()) return 120L;
        try {
            return Math.max(5L, Long.parseLong(raw.trim()));
        } catch (Exception e) {
            logger.warn("[FLAGS] backend.requestTimeoutSeconds inválido='{}' (usando 120)", raw);
            return 120L;
        }
    }

    public static boolean enableMediapipeDebug() {
        return getBoolean("features.mediapipe.debug", false);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String raw = getRaw(key);
        if (raw == null) return defaultValue;

        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return defaultValue;
        if (Objects.equals(v, "true") || Objects.equals(v, "1") || Objects.equals(v, "yes") || Objects.equals(v, "y")) return true;
        if (Objects.equals(v, "false") || Objects.equals(v, "0") || Objects.equals(v, "no") || Objects.equals(v, "n")) return false;

        logger.warn("[FLAGS] Valor inválido para {}='{}' (usando default={})", key, raw, defaultValue);
        return defaultValue;
    }

    public static String getRaw(String key) {
        // 1) JVM properties
        String sys = System.getProperty(key);
        if (sys != null) return sys;

        // 2) Env vars (APP_ + key en upper snake)
        String envKey = toEnvKey(key);
        String env = System.getenv(envKey);
        if (env != null) return env;

        // 3) app.properties en classpath
        String p = CLASSPATH_PROPS.getProperty(key);
        if (p != null) return p;

        return null;
    }

    private static String toEnvKey(String key) {
        String upper = key
                .replace('.', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        return "APP_" + upper;
    }

    private static Properties loadClasspathProps() {
        Properties props = new Properties();
        try (InputStream is = FeatureFlags.class.getResourceAsStream("/app.properties")) {
            if (is != null) {
                props.load(is);
                logger.info("[FLAGS] Cargado /app.properties ({} keys)", props.size());
            } else {
                logger.info("[FLAGS] /app.properties no encontrado (ok)");
            }
        } catch (Exception e) {
            logger.warn("[FLAGS] No se pudo cargar /app.properties (se ignora): {}", e.toString());
        }
        return props;
    }
}

