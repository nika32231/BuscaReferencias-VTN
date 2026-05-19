package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalImagePathsTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesExistingFile() throws Exception {
        Path image = tempDir.resolve("pose.jpg");
        Files.writeString(image, "fake");

        String absolute = LocalImagePaths.toAbsolutePath(image.toString());
        assertNotNull(absolute);
        assertTrue(Files.exists(Path.of(absolute)));
    }

    @Test
    void rejectsHttpUrls() {
        assertNull(LocalImagePaths.toAbsolutePath("https://example.com/a.jpg"));
    }
}
