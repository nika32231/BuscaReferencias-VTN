package org.refcolor.buscareferencias.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

/**
 * Internacionalización estática (i18n).
 *
 * Soporta español (es) e inglés (en).
 * Los archivos de mensajes deben estar en:
 *   src/main/resources/org/refcolor/buscareferencias/messages_es.properties
 *   src/main/resources/org/refcolor/buscareferencias/messages_en.properties
 *
 * La preferencia de idioma persiste entre sesiones via java.util.prefs.
 * Los oyentes registrados con {@link #addChangeListener} se invocan en el
 * hilo que llama a {@link #setLocale} — normalmente wrappéalo en Platform.runLater().
 */
public class I18n {

    private static final String PREF_NODE = "refcolor";
    private static final String PREF_KEY  = "app_locale";
    private static final String RES_BASE  = "org/refcolor/buscareferencias/messages_";

    private static volatile Locale         currentLocale;
    private static volatile ResourceBundle bundle;

    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    static {
        String saved = null;
        try {
            saved = Preferences.userRoot().node(PREF_NODE).get(PREF_KEY, null);
        } catch (Exception ignored) {}
        currentLocale = (saved != null) ? Locale.forLanguageTag(saved) : Locale.forLanguageTag("es");
        bundle = loadBundle(currentLocale);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Devuelve la traducción de la clave en el idioma activo.
     * Si la clave no existe, devuelve la propia clave.
     */
    public static String t(String key) {
        if (bundle == null || key == null) return (key != null) ? key : "";
        try {
            return bundle.getString(key);
        } catch (Exception e) {
            return key;
        }
    }

    /**
     * Devuelve la traducción formateada con {@link MessageFormat}.
     * Ejemplo: {@code I18n.fmt("status.canvasSize", 900, 600)} → "Lienzo: 900x600"
     * Si la clave no existe, devuelve la clave sin formatear.
     */
    public static String fmt(String key, Object... args) {
        String pattern = t(key);
        if (pattern.equals(key)) return key; // clave no encontrada
        try {
            return MessageFormat.format(pattern, args);
        } catch (Exception e) {
            return pattern;
        }
    }

    /** Cambia el idioma activo y notifica a todos los oyentes registrados. */
    public static void setLocale(Locale locale) {
        currentLocale = locale;
        bundle = loadBundle(locale);
        try {
            Preferences.userRoot().node(PREF_NODE).put(PREF_KEY, locale.toLanguageTag());
        } catch (Exception ignored) {}
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    /** Alterna entre español e inglés. */
    public static void toggleLocale() {
        setLocale(isEnglish() ? Locale.forLanguageTag("es") : Locale.ENGLISH);
    }

    public static Locale  getLocale()  { return currentLocale; }
    public static boolean isEnglish()  { return "en".equals(currentLocale.getLanguage()); }

    /** Registra un oyente que se invoca cuando cambia el idioma. */
    public static void addChangeListener(Runnable r)    { listeners.add(r); }
    public static void removeChangeListener(Runnable r) { listeners.remove(r); }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Carga el bundle para el idioma dado.
     * Intenta el idioma solicitado; si falla, cae al español.
     * Usa Module.getResourceAsStream para compatibilidad con JPMS.
     */
    private static ResourceBundle loadBundle(Locale locale) {
        for (String lang : new String[]{ locale.getLanguage(), "es" }) {
            String path = RES_BASE + lang + ".properties";
            try (InputStream is = I18n.class.getModule().getResourceAsStream(path)) {
                if (is != null) {
                    return new PropertyResourceBundle(
                            new InputStreamReader(is, StandardCharsets.UTF_8));
                }
            } catch (IOException | SecurityException ignored) {}
        }
        return null;
    }
}
