package org.refcolor.buscareferencias.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import org.refcolor.buscareferencias.model.PoseData;
import java.time.Duration;
import java.time.Instant;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static volatile String dbUrl = "jdbc:sqlite:buscareferencias.db";
    private static final int DEFAULT_USER_ID = 1;
    // SQL extraído como constante para evitar duplicación (S1)
    private static final String SQL_INSERT_RESULTADO =
        "INSERT INTO Resultados (id_busqueda, url_imagen, thumbnailPath, url_origen, sourceUrl, provider, " +
        "puntuacion_similitud, similarity, landmarks, embedding, embeddings, poseAngles) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static volatile boolean schemaReady = false;

    /**
     * Reemplaza la URL de conexión a la BD y fuerza re-inicialización del esquema.
     * <p><b>Solo para uso en tests.</b> Permite apuntar a una BD temporal aislada
     * (p.ej. un fichero {@code @TempDir}) en lugar del fichero de producción,
     * garantizando que los tests no contaminan ni dependen del estado persistente.</p>
     *
     * @param url URL JDBC de SQLite, p.ej. {@code "jdbc:sqlite:/tmp/test.db"}
     */
    public static void setDatabaseUrl(String url) {
        dbUrl = url;
        schemaReady = false;
    }

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
            // Relanzar como SQLException para que el caller sepa que la conexión no es posible (B2)
            throw new SQLException("SQLite JDBC Driver not found", e);
        }
        return DriverManager.getConnection(dbUrl);
    }

    public static int saveDrawing(PoseData pose, List<String> terms, List<org.refcolor.buscareferencias.model.ImageResult> results) {
        String sqlDibujo = "INSERT INTO Dibujos (id_usuario, datos_pose) VALUES (?, ?)";
        String sqlBusqueda = "INSERT INTO Busquedas (id_dibujo, terminos_busqueda) VALUES (?, ?)";

        int idBusqueda = -1;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmtDibujo = conn.prepareStatement(sqlDibujo, Statement.RETURN_GENERATED_KEYS)) {
                pstmtDibujo.setInt(1, DEFAULT_USER_ID);
                // Persistir como JSON estructurado (no toString()) para que sea consultable (B6)
                pstmtDibujo.setString(2, pose.getJointsJson());
                pstmtDibujo.executeUpdate();

                // try-with-resources para cerrar el ResultSet y evitar resource leak (B3)
                try (ResultSet rs = pstmtDibujo.getGeneratedKeys()) {
                    if (rs.next()) {
                        int idDibujo = rs.getInt(1);
                        try (PreparedStatement pstmtBusqueda = conn.prepareStatement(sqlBusqueda, Statement.RETURN_GENERATED_KEYS)) {
                            pstmtBusqueda.setInt(1, idDibujo);
                            pstmtBusqueda.setString(2, String.join(", ", terms));
                            pstmtBusqueda.executeUpdate();

                            try (ResultSet rsBusq = pstmtBusqueda.getGeneratedKeys()) {
                                if (rsBusq.next()) {
                                    idBusqueda = rsBusq.getInt(1);
                                }
                            }
                        }

                        if (idBusqueda != -1 && results != null) {
                            try (PreparedStatement pstmtRes = conn.prepareStatement(SQL_INSERT_RESULTADO)) {
                                for (var res : results) {
                                    bindResultRow(pstmtRes, idBusqueda, res);
                                    pstmtRes.addBatch();
                                }
                                pstmtRes.executeBatch();
                            }
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
        try (Connection conn = getConnection()) {
            try (PreparedStatement pstmtRes = conn.prepareStatement(SQL_INSERT_RESULTADO)) {
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
            // ── Usuarios: ahora con password_hash y avatar ────────────────────
            "CREATE TABLE IF NOT EXISTS Usuarios (" +
            "id_usuario INTEGER PRIMARY KEY AUTOINCREMENT," +
            "nombre_usuario TEXT NOT NULL UNIQUE," +
            "password_hash TEXT," +
            "avatar_color TEXT DEFAULT '#845460'," +
            "ultimo_acceso DATETIME," +
            "total_busquedas INTEGER DEFAULT 0," +
            "total_dibujos INTEGER DEFAULT 0," +
            "id_github TEXT UNIQUE," +
            "fecha_creacion DATETIME DEFAULT CURRENT_TIMESTAMP" +
            ");",

            "CREATE TABLE IF NOT EXISTS Dibujos (" +
            "id_dibujo INTEGER PRIMARY KEY AUTOINCREMENT," +
            "id_usuario INTEGER," +
            "datos_pose TEXT NOT NULL," +
            "titulo TEXT," +
            "snapshot_path TEXT," +
            "fecha_creacion DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "FOREIGN KEY(id_usuario) REFERENCES Usuarios(id_usuario)" +
            ");",

            "CREATE TABLE IF NOT EXISTS Busquedas (" +
            "id_busqueda INTEGER PRIMARY KEY AUTOINCREMENT," +
            "id_dibujo INTEGER," +
            "terminos_busqueda TEXT NOT NULL," +
            "num_resultados INTEGER DEFAULT 0," +
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
            ");",

            // ── Caché persistente de poses por imagen ─────────────────────────
            "CREATE TABLE IF NOT EXISTS ImagePoseCache (" +
            "file_path TEXT PRIMARY KEY," +
            "landmarks TEXT NOT NULL," +
            "embedding TEXT," +
            "pose_angles TEXT," +
            "analyzed_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
            ");",

            // ── Ajustes de la aplicación ──────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS AppSettings (" +
            "clave TEXT PRIMARY KEY," +
            "valor TEXT NOT NULL" +
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
            migrateUsuariosTable(conn, stmt);
            migrateDibujosTable(conn, stmt);
            logger.info("[DB] Tablas OK");

            stmt.execute("INSERT OR IGNORE INTO Usuarios (id_usuario, nombre_usuario) VALUES (1, 'Invitado')");
            schemaReady = true;

        } catch (SQLException e) {
            logger.error("[DB] Error inicializando la base de datos", e);
            schemaReady = false;
        } finally {
            logger.info("[DB] initDatabase() end en {} ms", Duration.between(t0, Instant.now()).toMillis());
        }
    }

    /**
     * Carga todas las poses cacheadas de la tabla ImagePoseCache en un solo query.
     * Devuelve un mapa [rutaAbsoluta → PoseData]; si la tabla no existe aún, devuelve mapa vacío.
     */
    public static Map<String, PoseData> loadAllCachedPoses() {
        Map<String, PoseData> result = new HashMap<>();
        ensureInitialized();
        String sql = "SELECT file_path, landmarks, embedding, pose_angles FROM ImagePoseCache WHERE landmarks IS NOT NULL";
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String path = rs.getString("file_path");
                if (path == null || path.isBlank()) continue;
                PoseData pose = new PoseData();
                pose.setLandmarksFromJson(rs.getString("landmarks"));
                String emb = rs.getString("embedding");
                if (emb != null && !emb.isBlank()) pose.setEmbeddingFromJson(emb);
                String angles = rs.getString("pose_angles");
                if (angles != null && !angles.isBlank()) pose.setPoseAnglesFromJson(angles);
                if (!pose.getAllLandmarks().isEmpty()) {
                    result.put(path, pose);
                }
            }
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("no such table")) {
                logger.debug("[DB] ImagePoseCache aún no creada; se inicializará en el próximo acceso.");
            } else {
                logger.warn("[DB] Error cargando ImagePoseCache: {}", e.getMessage());
            }
        }
        logger.info("[DB] loadAllCachedPoses: {} poses cargadas.", result.size());
        return result;
    }

    /**
     * Guarda (o actualiza) un lote de poses en ImagePoseCache de forma transaccional.
     * Llamar de forma asíncrona para no bloquear el hilo de búsqueda.
     */
    public static void cacheImagePoses(Map<String, PoseData> poses) {
        if (poses == null || poses.isEmpty()) return;
        ensureInitialized();
        String sql = "INSERT OR REPLACE INTO ImagePoseCache (file_path, landmarks, embedding, pose_angles) VALUES (?, ?, ?, ?)";
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int added = 0;
                for (Map.Entry<String, PoseData> entry : poses.entrySet()) {
                    String path = entry.getKey();
                    PoseData pose = entry.getValue();
                    if (pose == null || pose.getAllLandmarks().isEmpty()) continue;
                    String lm = pose.getLandmarksJson();
                    if (lm == null || lm.isBlank()) continue;
                    pstmt.setString(1, path);
                    pstmt.setString(2, lm);
                    String emb = pose.getEmbeddingJson();
                    pstmt.setString(3, (emb != null && !emb.isBlank()) ? emb : null);
                    String angles = pose.getPoseAnglesJson();
                    pstmt.setString(4, (angles != null && !angles.isBlank()) ? angles : null);
                    pstmt.addBatch();
                    added++;
                }
                pstmt.executeBatch();
                conn.commit();
                logger.debug("[DB] cacheImagePoses: {} poses guardadas.", added);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            logger.warn("[DB] Error guardando ImagePoseCache: {}", e.getMessage());
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
        ensureColumnInTable(stmt, columns, "Resultados", "landmarks", "TEXT");
        ensureColumnInTable(stmt, columns, "Resultados", "embedding", "TEXT");
        ensureColumnInTable(stmt, columns, "Resultados", "embeddings", "TEXT");
        ensureColumnInTable(stmt, columns, "Resultados", "similarity", "REAL");
        ensureColumnInTable(stmt, columns, "Resultados", "thumbnailPath", "TEXT");
        ensureColumnInTable(stmt, columns, "Resultados", "sourceUrl", "TEXT");
        ensureColumnInTable(stmt, columns, "Resultados", "provider", "TEXT");
        ensureColumnInTable(stmt, columns, "Resultados", "poseAngles", "TEXT");
        ensureColumnInTable(stmt, columns, "Resultados", "puntuacion_similitud", "REAL");
        ensureColumnInTable(stmt, columns, "Resultados", "url_imagen", "TEXT");
        ensureColumnInTable(stmt, columns, "Resultados", "url_origen", "TEXT");
    }

    private static void migrateUsuariosTable(Connection conn, Statement stmt) throws SQLException {
        Set<String> columns = getColumns(conn, "Usuarios");
        ensureColumnInTable(stmt, columns, "Usuarios", "password_hash", "TEXT");
        ensureColumnInTable(stmt, columns, "Usuarios", "avatar_color", "TEXT");
        ensureColumnInTable(stmt, columns, "Usuarios", "ultimo_acceso", "TEXT");
        ensureColumnInTable(stmt, columns, "Usuarios", "total_busquedas", "INTEGER");
        ensureColumnInTable(stmt, columns, "Usuarios", "total_dibujos", "INTEGER");
    }

    private static void migrateDibujosTable(Connection conn, Statement stmt) throws SQLException {
        Set<String> columns = getColumns(conn, "Dibujos");
        ensureColumnInTable(stmt, columns, "Dibujos", "titulo", "TEXT");
        ensureColumnInTable(stmt, columns, "Dibujos", "snapshot_path", "TEXT");
    }

    // ── CRUD de usuarios ──────────────────────────────────────────────────────

    public static java.util.Optional<int[]> findUserByCredentials(String username, String passwordHash) {
        ensureInitialized();
        String sql = "SELECT id_usuario FROM Usuarios WHERE nombre_usuario = ? AND password_hash = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username); ps.setString(2, passwordHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return java.util.Optional.of(new int[]{rs.getInt(1)});
            }
        } catch (SQLException e) { logger.warn("[DB] findUserByCredentials: {}", e.getMessage()); }
        return java.util.Optional.empty();
    }

    public static boolean userExists(String username) {
        ensureInitialized();
        String sql = "SELECT 1 FROM Usuarios WHERE nombre_usuario = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { logger.warn("[DB] userExists: {}", e.getMessage()); }
        return false;
    }

    public static int createUser(String username, String passwordHash, String avatarColor) {
        ensureInitialized();
        String sql = "INSERT INTO Usuarios (nombre_usuario, password_hash, avatar_color) VALUES (?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username); ps.setString(2, passwordHash); ps.setString(3, avatarColor);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) return rs.getInt(1); }
        } catch (SQLException e) { logger.error("[DB] createUser: {}", e.getMessage()); }
        return -1;
    }

    public static void updateLastAccess(int userId) {
        String sql = "UPDATE Usuarios SET ultimo_acceso = CURRENT_TIMESTAMP WHERE id_usuario = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.executeUpdate();
        } catch (SQLException e) { logger.warn("[DB] updateLastAccess: {}", e.getMessage()); }
    }

    public static void incrementUserStats(int userId, boolean isBusqueda) {
        String col = isBusqueda ? "total_busquedas" : "total_dibujos";
        String sql = "UPDATE Usuarios SET " + col + " = COALESCE(" + col + ", 0) + 1 WHERE id_usuario = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.executeUpdate();
        } catch (SQLException e) { logger.warn("[DB] incrementUserStats: {}", e.getMessage()); }
    }

    public static int[] getUserStats(int userId) {
        String sql = "SELECT total_busquedas, total_dibujos FROM Usuarios WHERE id_usuario = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new int[]{
                    rs.getInt("total_busquedas"), rs.getInt("total_dibujos")};
            }
        } catch (SQLException e) { logger.warn("[DB] getUserStats: {}", e.getMessage()); }
        return new int[]{0, 0};
    }

    // ── Ajustes de la aplicación ──────────────────────────────────────────────

    public static String getSetting(String key, String defaultValue) {
        ensureInitialized();
        String sql = "SELECT valor FROM AppSettings WHERE clave = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("valor");
            }
        } catch (SQLException e) { logger.warn("[DB] getSetting({}): {}", key, e.getMessage()); }
        return defaultValue;
    }

    public static void setSetting(String key, String value) {
        ensureInitialized();
        String sql = "INSERT OR REPLACE INTO AppSettings (clave, valor) VALUES (?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
        } catch (SQLException e) { logger.warn("[DB] setSetting({}): {}", key, e.getMessage()); }
    }

    // ── Historial de dibujos ──────────────────────────────────────────────────

    public static void saveSnapshotPath(int idDibujo, String snapshotPath) {
        String sql = "UPDATE Dibujos SET snapshot_path = ? WHERE id_dibujo = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshotPath); ps.setInt(2, idDibujo); ps.executeUpdate();
        } catch (SQLException e) { logger.warn("[DB] saveSnapshotPath: {}", e.getMessage()); }
    }

    public static List<DrawingRecord> getDrawingHistory(int userId, int limit) {
        ensureInitialized();
        String sql = "SELECT d.id_dibujo, d.titulo, d.snapshot_path, d.fecha_creacion, " +
                     "COUNT(b.id_busqueda) AS num_busquedas " +
                     "FROM Dibujos d LEFT JOIN Busquedas b ON d.id_dibujo = b.id_dibujo " +
                     "WHERE d.id_usuario = ? " +
                     "GROUP BY d.id_dibujo ORDER BY d.fecha_creacion DESC LIMIT ?";
        List<DrawingRecord> records = new java.util.ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId); ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new DrawingRecord(
                        rs.getInt("id_dibujo"),
                        rs.getString("titulo"),
                        rs.getString("snapshot_path"),
                        rs.getString("fecha_creacion"),
                        rs.getInt("num_busquedas")
                    ));
                }
            }
        } catch (SQLException e) { logger.warn("[DB] getDrawingHistory: {}", e.getMessage()); }
        return records;
    }

    public record DrawingRecord(int id, String titulo, String snapshotPath,
                                String fechaCreacion, int numBusquedas) {}

    /** Elimina un dibujo y sus búsquedas asociadas del historial. */
    public static void deleteDrawing(int idDibujo) {
        String sqlBusq = "DELETE FROM Busquedas WHERE id_dibujo = ?";
        String sqlDib  = "DELETE FROM Dibujos  WHERE id_dibujo = ?";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps1 = conn.prepareStatement(sqlBusq);
                 PreparedStatement ps2 = conn.prepareStatement(sqlDib)) {
                ps1.setInt(1, idDibujo); ps1.executeUpdate();
                ps2.setInt(1, idDibujo); ps2.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) { logger.warn("[DB] deleteDrawing({}): {}", idDibujo, e.getMessage()); }
    }

    /** Actualiza el título de un dibujo en el historial. */
    public static void renameDrawing(int idDibujo, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) return;
        String sql = "UPDATE Dibujos SET titulo = ? WHERE id_dibujo = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newTitle.trim());
            ps.setInt(2, idDibujo);
            ps.executeUpdate();
        } catch (SQLException e) { logger.warn("[DB] renameDrawing({}): {}", idDibujo, e.getMessage()); }
    }

    // ── Helpers de migración ──────────────────────────────────────────────────

    private static Set<String> getColumns(Connection conn, String tableName) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new SQLException("Nombre de tabla inválido: " + tableName);
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) columns.add(rs.getString("name"));
        }
        return columns;
    }

    private static void ensureColumnInTable(Statement stmt, Set<String> columns,
                                            String table, String columnName, String ddlType) throws SQLException {
        if (columns.contains(columnName)) return;
        if (!columnName.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new SQLException("Nombre de columna inválido: " + columnName);
        if (!ddlType.matches("TEXT|REAL|INTEGER|BLOB|NUMERIC"))
            throw new SQLException("Tipo DDL inválido: " + ddlType);
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new SQLException("Nombre de tabla inválido: " + table);
        stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + columnName + " " + ddlType);
        columns.add(columnName);
        logger.info("[DB] Columna migrada en {}: {} {}", table, columnName, ddlType);
    }

    // Backward compat: the old single-table ensureColumn still used by older code paths
    private static void ensureColumn(Statement stmt, Set<String> columns,
                                     String columnName, String ddlType) throws SQLException {
        ensureColumnInTable(stmt, columns, "Resultados", columnName, ddlType);
    }
}
