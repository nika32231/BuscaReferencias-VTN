package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectPathsTest {

    @Test
    void resolvesPoseAnalyzerScript() {
        Path script = ProjectPaths.resolveScript("pose_analyzer.py");
        assertNotNull(script);
        assertTrue(Files.isRegularFile(script));
        assertTrue(script.toString().replace('\\', '/').contains("Python/pose_analyzer.py"));
    }

    @Test
    void thumbnailsDirectoryExistsInProject() {
        Path dir = ProjectPaths.getThumbnailsDirectory();
        assertTrue(Files.isDirectory(dir), "Debe existir: " + dir);
    }
}
