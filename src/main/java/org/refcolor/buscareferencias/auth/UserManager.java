package org.refcolor.buscareferencias.auth;

import org.refcolor.buscareferencias.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Gestión de usuarios y sesiones locales.
 * No requiere servidor: credenciales almacenadas en SQLite con hash SHA-256.
 */
public class UserManager {

    private static final Logger logger = LoggerFactory.getLogger(UserManager.class);

    // ── Sesión activa en memoria ──────────────────────────────────────────────
    private static volatile UserSession currentSession = null;

    // ── Modelos ───────────────────────────────────────────────────────────────

    public record UserSession(int id, String username, String avatarColor) {
        public boolean isGuest() { return id == 1; }
    }

    public enum AuthResult { SUCCESS, WRONG_PASSWORD, USER_NOT_FOUND, USER_EXISTS,
                             PASSWORD_TOO_SHORT, USERNAME_EMPTY, ERROR }

    // ── API pública ───────────────────────────────────────────────────────────

    public static UserSession getCurrentSession() { return currentSession; }

    public static boolean isLoggedIn() { return currentSession != null; }

    /** Login como invitado (sin contraseña). */
    public static void loginAsGuest() {
        currentSession = new UserSession(1, "Invitado", "#6B8FA3");
        DatabaseManager.updateLastAccess(1);
        logger.info("[AUTH] Sesión invitado iniciada");
    }

    /** Login con usuario y contraseña. */
    public static AuthResult login(String username, String password) {
        if (username == null || username.isBlank()) return AuthResult.USERNAME_EMPTY;
        String hash = hashPassword(password == null ? "" : password);
        Optional<int[]> result = DatabaseManager.findUserByCredentials(username.trim(), hash);
        if (result.isPresent()) {
            int id = result.get()[0];
            String color = DatabaseManager.getSetting("avatar_" + id, "#845460");
            currentSession = new UserSession(id, username.trim(), color);
            DatabaseManager.updateLastAccess(id);
            logger.info("[AUTH] Login OK: {}", username);
            return AuthResult.SUCCESS;
        }
        // Comprobar si el usuario existe (para distinguir "no existe" de "contraseña errónea")
        if (DatabaseManager.userExists(username.trim())) {
            return AuthResult.WRONG_PASSWORD;
        }
        return AuthResult.USER_NOT_FOUND;
    }

    /** Registrar un nuevo usuario. */
    public static AuthResult register(String username, String password) {
        if (username == null || username.isBlank()) return AuthResult.USERNAME_EMPTY;
        if (password == null || password.length() < 4)  return AuthResult.PASSWORD_TOO_SHORT;
        if (DatabaseManager.userExists(username.trim())) return AuthResult.USER_EXISTS;

        String hash = hashPassword(password);
        String[] colors = {"#845460","#5A8A7C","#2B4F60","#C17B5E","#D4A064"};
        String avatarColor = colors[(int)(Math.random() * colors.length)];
        int id = DatabaseManager.createUser(username.trim(), hash, avatarColor);
        if (id < 0) return AuthResult.ERROR;

        DatabaseManager.setSetting("avatar_" + id, avatarColor);
        currentSession = new UserSession(id, username.trim(), avatarColor);
        DatabaseManager.updateLastAccess(id);
        logger.info("[AUTH] Registro OK: {} (id={})", username, id);
        return AuthResult.SUCCESS;
    }

    /** Cerrar sesión. */
    public static void logout() {
        logger.info("[AUTH] Logout: {}", currentSession != null ? currentSession.username() : "?");
        currentSession = null;
    }

    /** Estadísticas del usuario activo. @return [total_busquedas, total_dibujos] */
    public static int[] getCurrentUserStats() {
        if (currentSession == null) return new int[]{0, 0};
        return DatabaseManager.getUserStats(currentSession.id());
    }

    public static int getCurrentUserId() {
        return currentSession != null ? currentSession.id() : 1;
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error generando hash de contraseña", e);
        }
    }
}
