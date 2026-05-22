package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.database.DatabaseManager;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de integración para {@link DatabaseManager}.
 *
 * <p>Cada test usa una BD SQLite en un directorio temporal ({@link TempDir})
 * creado y destruido automáticamente por JUnit 5. Esto garantiza aislamiento
 * completo entre tests y evita contaminar el fichero de producción
 * {@code buscareferencias.db}.</p>
 */
public class DatabaseManagerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setUp() {
        // Apuntar a un fichero temporal único por test; @TempDir garantiza limpieza automática
        Path dbPath = tempDir.resolve("test_buscareferencias.db");
        DatabaseManager.setDatabaseUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initDatabase();
    }

    @AfterEach
    public void tearDown() {
        // Restablecer la URL de producción para no afectar a código que se ejecute después
        DatabaseManager.setDatabaseUrl("jdbc:sqlite:buscareferencias.db");
    }

    @Test
    public void testInitDatabase() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {

            // Verificar que las cuatro tablas principales existen
            String[] tables = {"Usuarios", "Dibujos", "Busquedas", "Resultados"};
            for (String table : tables) {
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                    assertTrue(rs.next(), "La tabla " + table + " debería existir");
                }
            }

            // Verificar columnas adicionales en Resultados (migradas por migrateResultadosTable)
            Set<String> columnNames = new HashSet<>();
            try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(Resultados)")) {
                while (columns.next()) {
                    columnNames.add(columns.getString("name"));
                }
            }
            assertTrue(columnNames.contains("landmarks"),      "Debería existir la columna 'landmarks'");
            assertTrue(columnNames.contains("embeddings"),     "Debería existir la columna 'embeddings'");
            assertTrue(columnNames.contains("similarity"),     "Debería existir la columna 'similarity'");
            assertTrue(columnNames.contains("thumbnailPath"),  "Debería existir la columna 'thumbnailPath'");
            assertTrue(columnNames.contains("sourceUrl"),      "Debería existir la columna 'sourceUrl'");
            assertTrue(columnNames.contains("provider"),       "Debería existir la columna 'provider'");
            assertTrue(columnNames.contains("poseAngles"),     "Debería existir la columna 'poseAngles'");
        }
    }

    @Test
    public void testDefaultUserInsertion() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM Usuarios WHERE id_usuario = 1")) {
            assertTrue(rs.next(), "El usuario por defecto (ID 1) debería haber sido insertado");
            assertEquals("Usuario Local", rs.getString("nombre_usuario"));
        }
    }

    @Test
    public void testSaveDrawing() throws Exception {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.HEAD, 100, 100);
        List<String> terms = Arrays.asList("test term 1", "test term 2");

        DatabaseManager.saveDrawing(pose, terms, null);

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {

            // Verificar que el dibujo se guardó con datos de pose en formato JSON
            try (ResultSet rsDibujo = stmt.executeQuery(
                    "SELECT * FROM Dibujos ORDER BY id_dibujo DESC LIMIT 1")) {
                assertTrue(rsDibujo.next(), "Debería haber al menos un dibujo");
                String poseData = rsDibujo.getString("datos_pose");
                // datos_pose se guarda como JSON: {"Cabeza":{"x":...,"y":...}}
                assertNotNull(poseData, "datos_pose no debería ser null");
                assertTrue(poseData.contains("Cabeza"),
                        "El JSON de la pose debería contener el nombre del joint 'Cabeza'");
                int idDibujo = rsDibujo.getInt("id_dibujo");

                // Verificar que los términos de búsqueda se guardaron correctamente
                try (ResultSet rsBusqueda = stmt.executeQuery(
                        "SELECT * FROM Busquedas WHERE id_dibujo = " + idDibujo)) {
                    assertTrue(rsBusqueda.next(),
                            "Deberían existir términos de búsqueda para el dibujo");
                    String savedTerms = rsBusqueda.getString("terminos_busqueda");
                    assertEquals("test term 1, test term 2", savedTerms);
                }
            }
        }
    }
}
