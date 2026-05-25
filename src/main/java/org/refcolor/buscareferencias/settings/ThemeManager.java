package org.refcolor.buscareferencias.settings;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Notifica a todos los componentes registrados cuando el tema cambia.
 * Equivalente ligero a un EventBus de un solo evento (theme-changed).
 */
public final class ThemeManager {

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ThemeManager() {}

    /** Registra un listener que se invoca en el hilo que llama a {@link #notifyListeners()}. */
    public static void addChangeListener(Runnable r) {
        if (r != null) listeners.add(r);
    }

    /** Elimina todos los listeners registrados (útil en tests). */
    public static void clearListeners() {
        listeners.clear();
    }

    /** Invoca todos los listeners registrados en el hilo actual. */
    public static void notifyListeners() {
        for (Runnable r : listeners) {
            try { r.run(); }
            catch (Exception ignored) {}
        }
    }
}
