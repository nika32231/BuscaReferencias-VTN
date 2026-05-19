package org.refcolor.buscareferencias.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Localiza la raíz del proyecto aunque el proceso no arranque desde ahí (p. ej. IntelliJ).
 */
public final class ProjectPaths {

    private static final Logger logger = LoggerFactory.getLogger(ProjectPaths.class);
    private static volatile Path cachedRoot;

    private ProjectPaths() {
    }

    public static Path getProjectRoot() {
        if (cachedRoot != null) {
            return cachedRoot;
        }
        synchronized (ProjectPaths.class) {
            if (cachedRoot != null) {
                return cachedRoot;
            }
            cachedRoot = detectProjectRoot();
            logger.info("[PATHS] Raíz del proyecto: {}", cachedRoot);
            return cachedRoot;
        }
    }

    public static Path getThumbnailsDirectory() {
        String configured = PoseToleranceConfig.localImageDir();
        Path configuredPath = Paths.get(configured);
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize();
        }
        return getProjectRoot().resolve(configured).normalize();
    }

    public static Path resolveScript(String scriptName) {
        List<Path> roots = candidateRoots();
        for (Path root : roots) {
            Path inPython = root.resolve("Python").resolve(scriptName).normalize();
            if (Files.isRegularFile(inPython)) {
                return inPython;
            }
            Path inRoot = root.resolve(scriptName).normalize();
            if (Files.isRegularFile(inRoot)) {
                return inRoot;
            }
        }
        return null;
    }

    private static Path detectProjectRoot() {
        for (Path root : candidateRoots()) {
            if (isProjectRoot(root)) {
                return root;
            }
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static List<Path> candidateRoots() {
        List<Path> roots = new ArrayList<>();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        roots.add(cwd);
        Path parent = cwd.getParent();
        if (parent != null) {
            roots.add(parent.normalize());
            Path grandParent = parent.getParent();
            if (grandParent != null) {
                roots.add(grandParent.normalize());
            }
        }
        return roots;
    }

    private static boolean isProjectRoot(Path root) {
        return Files.isRegularFile(root.resolve("pom.xml"))
                || Files.isRegularFile(root.resolve("Python").resolve("pose_analyzer.py"))
                || Files.isDirectory(root.resolve("cache").resolve("thumbnails"));
    }
}
