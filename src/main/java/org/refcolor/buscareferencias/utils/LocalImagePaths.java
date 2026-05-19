package org.refcolor.buscareferencias.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Normalización de rutas de imágenes locales para JavaFX y MediaPipe.
 */
public final class LocalImagePaths {

    private static final Logger logger = LoggerFactory.getLogger(LocalImagePaths.class);

    private LocalImagePaths() {
    }

    public static String toAbsolutePath(String source) {
        if (source == null || source.isBlank()) return null;
        String s = source.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return null;
        }
        try {
            Path path = s.startsWith("file:") ? Paths.get(URI.create(s)) : Paths.get(s);
            path = path.toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path.toString() : null;
        } catch (Exception e) {
            logger.debug("Ruta local inválida: {}", s);
            return null;
        }
    }

    public static String toFileUri(String source) {
        String absolute = toAbsolutePath(source);
        if (absolute == null) return "";
        return Paths.get(absolute).toUri().toString();
    }
}
