package org.refcolor.buscareferencias.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import org.refcolor.buscareferencias.model.PoseData;
import java.time.Duration;
import java.time.Instant;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String URL = "jdbc:sqlite:buscareferencias.db";
    private static final int DEFAULT_USER_ID = 1;
    private static volatile boolean schemaReady = false;

    public static void ensureInitialized() {
        if (schemaReady) {
            return;
        }
        synchronized (DatabaseManager.class) {
            if (!schemaReady) {
                initDatabase();
            }
        }
    }

    public static Connection getConnection() throws SQLException {
        ensureInitialized();
        return openConnection();
    }

    private static Connection openConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.error("SQLite JDBC Driver not found", e);
        }
        return DriverManager.getConnection(URL);
    }

    public static int saveDrawing(PoseData pose, List<String> terms, List<org.refcolor.buscareferencias.model.ImageResult> results) {
        String sqlDibujo = "INSERT INTO Dibujos (id_usuario, datos_pose) VALUES (?, ?)";
        String sqlBusqueda = "INSERT INTO Busquedas (id_dibujo, terminos_busqueda) VALUES (?, ?)";
        String sqlResultado = "INSERT INTO Resultados (id_busqueda, url_imagen, thumbnailPath, url_origen, sourceUrl, provider, puntuacion_similitud, similarity, landmarks, embedding, embeddings, poseAngles) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        int idBusqueda = -1;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmtDibujo = conn.prepareStatement(sqlDibujo, Statement.RETURN_GENERATED_KEYS)) {
                pstmtDibujo.setInt(1, DEFAULT_USER_ID);
                pstmtDibujo.setString(2, pose.toString());
                pstmtDibujo.executeUpdate();

                ResultSet rs = pstmtDibujo.getGeneratedKeys();
                if (rs.next()) {
                    int idDibujo = rs.getInt(1);
                    try (PreparedStatement pstmtBusqueda = conn.prepareStatement(sqlBusqueda, Statement.RETURN_GENERATED_KEYS)) {
                        pstmtBusqueda.setInt(1, idDibujo);
                        pstmtBusqueda.setString(2, String.join(", ", terms));
                        pstmtBusqueda.executeUpdate();
                        
                        ResultSet rsBusq = pstmtBusqueda.getGeneratedKeys();
                        if (rsBusq.next()) {
                            idBusqueda = rsBusq.getInt(1);
                        }
                    }
                    
                    if (idBusqueda != -1 && results != null) {
                        try (PreparedStatement pstmtRes = conn.prepareStatement(sqlResultado)) {
                            for (var res : results) {
                                bindResultRow(pstmtRes, idBusqueda, res);
                                pstmtRes.addBatch();
                            }
                            pstmtRes.executeBatch();
                        }
                    }
                }
                conn.commit();
                logger.info("Dibujo, términos y resultados guardados en la base de datos.");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            logger.error("Error guardando dibujo y resultados", e);
        }
        return idBusqueda;
    }

    public static void saveResults(int idBusqueda, List<org.refcolor.buscareferencias.model.ImageResult> results) {
        if (idBusqueda == -1 || results == null || results.isEmpty()) return;
        String sqlResultado = "INSERT INTO Resultados (id_busqueda, url_imagen, thumbnailPath, url_origen, sourceUrl, provider, puntuacion_similitud, similarity, landmarks, embedding, embeddings, poseAngles) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection()) {
            try (PreparedStatement pstmtRes = conn.prepareStatement(sqlResultado)) {
                for (var res : results) {
                    bindResultRow(pstmtRes, idBusqueda, res);
                    pstmtRes.addBatch();
                }
                pstmtRes.executeBatch();
            }
        } catch (SQLException e) {
            logger.error("Error guardando resultados adicionales", e);
        }
    }

    public static org.refcolor.buscareferencias.model.PoseData getCachedPose(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String sql = "SELECT landmarks, COALESCE(embeddings, embedding) AS embedding_data FROM Resultados "
                + "WHERE COALESCE(url_imagen, sourceUrl, url_origen, thumbnailPath) = ? AND landmarks IS NOT NULL LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, url);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    org.refcolor.buscareferencias.model.PoseData pose = new org.refcolor.buscareferencias.model.PoseData();
                    pose.setLandmarksFromJson(rs.getString("landmarks"));
                    pose.setEmbeddingFromJson(rs.getString("embedding_data"));
                    return pose;
                }
            }
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("no such column")) {
                logger.debug("Caché de pose no disponible en el esquema actual: {}", e.getMessage());
            } else if (msg.contains("no such table")) {
                logger.debug("Tabla Resultados aún no creada; se inicializará en el próximo acceso.");
            } else {
                logger.warn("No se pudo leer la pose cacheada: {}", e.getMessage());
            }
        }
        return null;
    }

    public static void initDatabase() {
        Instant t0 = Instant.now();
        logger.info("[DB] initDatabase() begin");

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
            "landmarks TEXT," +
            "embedding TEXT," +
            "FOREIGN KEY(id_busqueda) REFERENCES Busquedas(id_busqueda)" +
            ");"
        };

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            // Evitar locks largos en sqlite
            stmt.execute("PRAGMA busy_timeout = 2000");
            stmt.execute("PRAGMA journal_mode = WAL");

            for (String sql : tables) {
                stmt.execute(sql);
            }
            migrateResultadosTable(conn, stmt);
            logger.info("[DB] Tablas OK");

            stmt.execute("INSERT OR IGNORE INTO Usuarios (id_usuario, nombre_usuario) VALUES (1, 'Usuario Local')");
            schemaReady = true;

        } catch (SQLException e) {
            logger.error("[DB] Error inicializando la base de datos", e);
            schemaReady = false;
        } finally {
            logger.info("[DB] initDatabase() end en {} ms", Duration.between(t0, Instant.now()).toMillis());
        }
    }

    private static void bindResultRow(PreparedStatement pstmtRes, int idBusqueda, org.refcolor.buscareferencias.model.ImageResult res) throws SQLException {
        String displayThumbnail = res.getDisplayThumbnailUrl() != null && !res.getDisplayThumbnailUrl().isBlank()
                ? res.getDisplayThumbnailUrl()
                : res.getThumbnailUrl();
        String imageUrl = res.getOriginalUrl() != null && !res.getOriginalUrl().isBlank()
                ? res.getOriginalUrl()
                : res.getThumbnailUrl();
        String sourcePageUrl = res.getSourcePageUrl() != null && !res.getSourcePageUrl().isBlank()
                ? res.getSourcePageUrl()
                : imageUrl;
        String provider = res.getSource() == null ? "" : res.getSource();
        double score = res.getScore();
        String landmarksJson = null;
        String embeddingJson = null;
        String poseAnglesJson = null;
        if (res.getPoseData() != null) {
            landmarksJson = res.getPoseData().getLandmarksJson();
            embeddingJson = res.getPoseData().getEmbeddingJson();
            poseAnglesJson = res.getPoseData().getPoseAnglesJson();
        }

        pstmtRes.setInt(1, idBusqueda);
        pstmtRes.setString(2, imageUrl);
        pstmtRes.setString(3, displayThumbnail);
        pstmtRes.setString(4, sourcePageUrl);
        pstmtRes.setString(5, sourcePageUrl);
        pstmtRes.setString(6, provider);
        pstmtRes.setDouble(7, score);
        pstmtRes.setDouble(8, score);
        if (landmarksJson != null && !landmarksJson.isBlank()) {
            pstmtRes.setString(9, landmarksJson);
        } else {
            pstmtRes.setNull(9, java.sql.Types.VARCHAR);
        }
        if (embeddingJson != null && !embeddingJson.isBlank()) {
            pstmtRes.setString(10, embeddingJson);
            pstmtRes.setString(11, embeddingJson);
        } else {
            pstmtRes.setNull(10, java.sql.Types.VARCHAR);
            pstmtRes.setNull(11, java.sql.Types.VARCHAR);
        }
        if (poseAnglesJson != null && !poseAnglesJson.isBlank()) {
            pstmtRes.setString(12, poseAnglesJson);
        } else {
            pstmtRes.setNull(12, java.sql.Types.VARCHAR);
        }
    }

    private static void migrateResultadosTable(Connection conn, Statement stmt) throws SQLException {
        Set<String> columns = getColumns(conn, "Resultados");
        ensureColumn(stmt, columns, "landmarks", "TEXT");
        ensureColumn(stmt, columns, "embedding", "TEXT");
        ensureColumn(stmt, columns, "embeddings", "TEXT");
        ensureColumn(stmt, columns, "similarity", "REAL");
        ensureColumn(stmt, columns, "thumbnailPath", "TEXT");
        ensureColumn(stmt, columns, "sourceUrl", "TEXT");
        ensureColumn(stmt, columns, "provider", "TEXT");
        ensureColumn(stmt, columns, "poseAngles", "TEXT");
        ensureColumn(stmt, columns, "puntuacion_similitud", "REAL");
        ensureColumn(stmt, columns, "url_imagen", "TEXT");
        ensureColumn(stmt, columns, "url_origen", "TEXT");
    }

    private static Set<String> getColumns(Connection conn, String tableName) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement pstmt = conn.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }

    private static void ensureColumn(Statement stmt, Set<String> columns, String columnName, String ddlType) throws SQLException {
        if (columns.contains(columnName)) {
            return;
        }
        stmt.execute("ALTER TABLE Resultados ADD COLUMN " + columnName + " " + ddlType);
        columns.add(columnName);
        logger.info("[DB] Columna migrada en Resultados: {} {}", columnName, ddlType);
    }
}
