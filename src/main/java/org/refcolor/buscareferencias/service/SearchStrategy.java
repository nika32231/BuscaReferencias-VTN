package org.refcolor.buscareferencias.service;

import org.refcolor.buscareferencias.client.BackendSearchClient;
import org.refcolor.buscareferencias.client.FlagManager;
import org.refcolor.buscareferencias.core.FeatureFlags;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Estratega de búsqueda que integra feature flags para rollout progresivo.
 * 
 * Permite migrar gradualmente búsquedas del cliente local al backend remoto.
 * Decide automáticamente si usar:
 * - Backend remoto (basado en rollout_percentage)
 * - Local con fallback
 * 
 * Uso:
 *   SearchStrategy strategy = new SearchStrategy(baseBackendUrl);
 *   results = strategy.searchWithRollout(terms, poseData);
 */
public class SearchStrategy {
    private static final Logger logger = LoggerFactory.getLogger(SearchStrategy.class);

    private final BackendSearchClient backendClient;
    private final String backendUrl;

    public SearchStrategy(String backendUrl) {
        this.backendUrl = backendUrl;
        this.backendClient = (backendUrl != null && !backendUrl.isBlank())
                ? new BackendSearchClient(backendUrl, Duration.ofSeconds(FeatureFlags.backendRequestTimeoutSeconds()))
                : null;
    }

    /**
     * Ejecuta búsqueda inteligente con rollout progresivo.
     * 
     * Flujo:
     * 1. Consultar flags del backend (rollout_percentage, force_local, force_backend)
     * 2. Decidir si usar backend o local basándose en hash consistente del usuario
     * 3. Intentar encaminamiento elegido
     * 4. Fallback automático si falla
     * 
     * @param terms términos de búsqueda
     * @param poseData datos de pose del dibujo
     * @param providers lista de proveedores
     * @param limit límite de resultados
     * @return resultados de búsqueda (backend, ocalor fallback vacío)
     */
    public List<ImageResult> searchWithRollout(List<String> terms, PoseData poseData, List<String> providers, int limit) {
        if (backendClient == null || backendUrl == null || backendUrl.isBlank()) {
            logger.debug("Backend no configurado, usando local");
            return List.of();
        }

        FlagManager flags = backendClient.getFlagManager();
        String strategy = "UNKNOWN";

        try {
            boolean shouldUseBackend = flags.shouldUseBackend();
            strategy = shouldUseBackend ? "BACKEND" : "LOCAL";
            
            if (shouldUseBackend) {
                logger.info("Rollout BACKEND activado ({}) para usuario {}, intentando búsqueda remota...",
                        flags.getRolloutPercentage(), flags.getUserHash());
                return backendClient.searchReferences(terms, poseData, providers, limit, null);
            } else {
                logger.info("Rollout LOCAL ({}) para usuario {}, usando motor local",
                        flags.getRolloutPercentage(), flags.getUserHash());
                return List.of(); // Signal local search
            }

        } catch (Exception e) {
            logger.warn("Error en estrategia {} [{}%]: {}", strategy, flags.getRolloutPercentage(), e.toString());
            return List.of(); // Signal fallback
        }
    }

    /**
     * Intenta recargar flags del backend (para actualizar decisiones de rollout).
     */
    public void refreshFlags() {
        if (backendClient != null) {
            logger.debug("Recargando flags del backend...");
            backendClient.reloadFlags();
        }
    }

    /**
     * Acceso directo al FlagManager para debugging/logging.
     */
    public FlagManager getFlags() {
        return backendClient != null ? backendClient.getFlagManager() : null;
    }

    /**
     * Obtiene información de estado para logging.
     */
    public String getStrategyInfo() {
        if (backendClient == null) {
            return "Backend no configurado";
        }
        FlagManager flags = backendClient.getFlagManager();
        return String.format(
                "Strategy[backend=%s, rollout=%d%%, local=%b, backend=%b, user=%s]",
                backendUrl, flags.getRolloutPercentage(), flags.isForceLocal(), flags.isForceBackend(), flags.getUserHash()
        );
    }
}

