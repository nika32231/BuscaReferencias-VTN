package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.PoseData;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseManagerTest {

    private static final String DB_FILE = "buscareferencias.db";

    @BeforeEach
    public void setUp() {
        // Asegurarse de que la base de datos esté limpia o inicializada
        DatabaseManager.initDatabase();
    }

    @Test
    public void testInitDatabase() throws Exception {
        File dbFile = new File(DB_FILE);
        assertTrue(dbFile.exists(), "El archivo de base de datos debería existir");

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Verificar que las tablas existen
            String[] tables = {"Usuarios", "Dibujos", "Busquedas", "Resultados"};
            for (String table : tables) {
                ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'");
                assertTrue(rs.next(), "La tabla " + table + " debería existir");
            }
        }
    }

    @Test
    public void testDefaultUserInsertion() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM Usuarios WHERE id_usuario = 1");
            assertTrue(rs.next(), "El usuario por defecto (ID 1) debería haber sido insertado");
            assertEquals("Usuario Local", rs.getString("nombre_usuario"));
        }
    }

    @Test
    public void testSaveDrawing() throws Exception {
        PoseData pose = new PoseData();
        pose.addJoint(AnatomyPart.HEAD, 100, 100);
        List<String> terms = Arrays.asList("test term 1", "test term 2");

        DatabaseManager.saveDrawing(pose, terms);

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Verificar que el dibujo se guardó
            ResultSet rsDibujo = stmt.executeQuery("SELECT * FROM Dibujos ORDER BY id_dibujo DESC LIMIT 1");
            assertTrue(rsDibujo.next(), "Debería haber al menos un dibujo");
            String poseData = rsDibujo.getString("datos_pose");
            assertTrue(poseData.contains("Cabeza"), "Los datos de la pose deberían guardarse");
            int idDibujo = rsDibujo.getInt("id_dibujo");

            // Verificar que los términos se guardaron
            ResultSet rsBusqueda = stmt.executeQuery("SELECT * FROM Busquedas WHERE id_dibujo = " + idDibujo);
            assertTrue(rsBusqueda.next(), "Deberían existir términos de búsqueda para el dibujo");
            String savedTerms = rsBusqueda.getString("terminos_busqueda");
            assertEquals("test term 1, test term 2", savedTerms);
        }
    }
}
