package org.refcolor.buscareferencias.client;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Gestor de feature flags para rollout progresivo de búsquedas backend.
 * 
 * Permite migrar gradualmente el tráfico del cliente local al backend remoto.
 * - rollout_percentage: 0-100, qué % de usuarios van a backend
 * - force_local: fuerza siempre local (emergencias)
 * - force_backend: fuerza siempre backend (testing)
 * - hash_seed: semilla para hash consistente por usuario
 */
public final class FlagManager {
    private static final Logger logger = LoggerFactory.getLogger(FlagManager.class);

    private int rolloutPercentage;
    private boolean forceLocal;
    private boolean forceBackend;
    private String hashSeed;
    private String userHash;

    public FlagManager() {
        this.rolloutPercentage = 0;
        this.forceLocal = false;
        this.forceBackend = false;
        this.hashSeed = "default";
        this.userHash = generateUserHash();
    }

    /**
     * Actualiza la configuración de flags desde respuesta del backend.
     */
    public void updateFromBackendConfig(String jsonResponse) {
        try {
            if (jsonResponse == null || jsonResponse.isBlank()) {
                logger.warn("Respuesta de /config vacía, manteniendo valores actuales");
                return;
            }
            JSONObject config = new JSONObject(jsonResponse);
            this.rolloutPercentage = config.optInt("rollout_percentage", this.rolloutPercentage);
            this.forceLocal = config.optBoolean("force_local", this.forceLocal);
            this.forceBackend = config.optBoolean("force_backend", this.forceBackend);
            this.hashSeed = config.optString("hash_seed", this.hashSeed);
            logger.info("Flags actualizados: rollout={}%, forceLocal={}, forceBackend={}, seed={}",
                    rolloutPercentage, forceLocal, forceBackend, hashSeed);
        } catch (Exception e) {
            logger.warn("Error parseando /config: {}", e.toString());
        }
    }

    /**
     * Decide si una búsqueda debe ir al backend o usar local.
     * 
     * @return true si debe ir al backend, false para local
     */
    public boolean shouldUseBackend() {
        // Overrides absolutos
        if (forceLocal) {
            logger.debug("Usando LOCAL (force_local=true)");
            return false;
        }
        if (forceBackend) {
            logger.debug("Usando BACKEND (force_backend=true)");
            return true;
        }

        // Hash consistente por usuario para determinar si está en rollout
        int hashValue = hashModulo(userHash + hashSeed, 100);
        boolean inRollout = hashValue < rolloutPercentage;
        logger.debug("Usuario hash={}, rollout={}%, in_rollout={}", hashValue, rolloutPercentage, inRollout);
        return inRollout;
    }

    /**
     * Calcula hash modulo para determinar membresía de rollout.
     */
    private static int hashModulo(String input, int modulo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            // Tomar primeros 4 bytes como int unsigned
            int value = 0;
            for (int i = 0; i < 4; i++) {
                value = (value << 8) | (hash[i] & 0xFF);
            }
            return Math.abs(value) % modulo;
        } catch (NoSuchAlgorithmException e) {
            // Fallback: usar hashCode de string
            return Math.abs(input.hashCode()) % modulo;
        }
    }

    /**
     * Genera identificador único del usuario (basado en propiedades del sistema).
     */
    private static String generateUserHash() {
        try {
            String userInfo = System.getProperty("user.name", "unknown")
                    + System.getProperty("os.name", "unknown")
                    + System.getProperty("os.arch", "unknown");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(userInfo.getBytes(StandardCharsets.UTF_8));
            // Convertir a hex
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16); // Primeros 16 caracteres
        } catch (Exception e) {
            logger.warn("Error generando user hash: {}", e.toString());
            return "default-user";
        }
    }

    // Getters para debugging/logging
    public int getRolloutPercentage() {
        return rolloutPercentage;
    }

    public boolean isForceLocal() {
        return forceLocal;
    }

    public boolean isForceBackend() {
        return forceBackend;
    }

    public String getHashSeed() {
        return hashSeed;
    }

    public String getUserHash() {
        return userHash;
    }

    @Override
    public String toString() {
        return String.format(
                "FlagManager{rollout=%d%%, forceLocal=%b, forceBackend=%b, userHash=%s}",
                rolloutPercentage, forceLocal, forceBackend, userHash
        );
    }
}

