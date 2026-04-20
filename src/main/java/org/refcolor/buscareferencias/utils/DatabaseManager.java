package org.refcolor.buscareferencias.utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.refcolor.buscareferencias.model.PoseData;

public class DatabaseManager {
    private static final Logger logger = Logger.getLogger(DatabaseManager.class.getName());
    private static final String URL = "jdbc:sqlite:buscareferencias.db";

    public static Connection getConnection() throws SQLException {
        try {
            // Forzar carga del driver en entornos modulares (Java 9+)
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "SQLite JDBC Driver not found", e);
        }
        return DriverManager.getConnection(URL);
    }

    public static void saveDrawing(PoseData pose, List<String> terms) {
        String sqlDibujo = "INSERT INTO Dibujos (id_usuario, datos_pose) VALUES (1, ?)";
        String sqlBusqueda = "INSERT INTO Busquedas (id_dibujo, terminos_busqueda) VALUES (?, ?)";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmtDibujo = conn.prepareStatement(sqlDibujo, Statement.RETURN_GENERATED_KEYS)) {
                pstmtDibujo.setString(1, pose.toString());
                pstmtDibujo.executeUpdate();

                ResultSet rs = pstmtDibujo.getGeneratedKeys();
                if (rs.next()) {
                    int idDibujo = rs.getInt(1);
                    try (PreparedStatement pstmtBusqueda = conn.prepareStatement(sqlBusqueda)) {
                        pstmtBusqueda.setInt(1, idDibujo);
                        pstmtBusqueda.setString(2, String.join(", ", terms));
                        pstmtBusqueda.executeUpdate();
                    }
                }
                conn.commit();
                logger.info("Dibujo y términos guardados en la base de datos.");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error guardando dibujo", e);
        }
    }

    public static void initDatabase() {
        String[] tables = {
            "CREATE TABLE IF NOT EXISTS Usuarios (" +
            "id_usuario INTEGER PRIMARY KEY AUTOINCREMENT," +
            "nombre_usuario TEXT NOT NULL," +
            "id_github TEXT UNIQUE," +
            "fecha_creacion DATETIME DEFAULT CURRENT_TIMESTAMP" +
            ");",

            "CREATE TABLE IF NOT EXISTS Dibujos (" +
            "id_dibujo INTEGER PRIMARY KEY AUTOINCREMENT," +
            "id_usuario INTEGER," +
            "datos_pose TEXT NOT NULL," +
            "fecha_creacion DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "FOREIGN KEY(id_usuario) REFERENCES Usuarios(id_usuario)" +
            ");",

            "CREATE TABLE IF NOT EXISTS Busquedas (" +
            "id_busqueda INTEGER PRIMARY KEY AUTOINCREMENT," +
            "id_dibujo INTEGER," +
            "terminos_busqueda TEXT NOT NULL," +
            "fecha_busqueda DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "FOREIGN KEY(id_dibujo) REFERENCES Dibujos(id_dibujo)" +
            ");",

            "CREATE TABLE IF NOT EXISTS Resultados (" +
            "id_resultado INTEGER PRIMARY KEY AUTOINCREMENT," +
            "id_busqueda INTEGER," +
            "url_imagen TEXT NOT NULL," +
            "url_origen TEXT," +
            "puntuacion_similitud REAL," +
            "FOREIGN KEY(id_busqueda) REFERENCES Busquedas(id_busqueda)" +
            ");"
        };

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            for (String sql : tables) {
                stmt.execute(sql);
            }
            logger.info("Base de datos inicializada correctamente.");
            
            // Insertar usuario por defecto si no hay
            stmt.execute("INSERT OR IGNORE INTO Usuarios (id_usuario, nombre_usuario) VALUES (1, 'Usuario Local')");
            
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error inicializando la base de datos", e);
        }
    }
}
