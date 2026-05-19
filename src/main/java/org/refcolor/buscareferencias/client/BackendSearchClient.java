package org.refcolor.buscareferencias.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class BackendSearchClient {
    private static final Logger logger = LoggerFactory.getLogger(BackendSearchClient.class);
    private static final List<String> SEARCH_PATH_CANDIDATES = List.of(
            "/search",
            "/api/v1/search/references"
    );

    private final HttpClient httpClient;
    private final URI baseUri;
    private final Duration timeout;
    private final FlagManager flagManager;

    public BackendSearchClient(String baseUrl, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String normalized = baseUrl.trim();
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        this.baseUri = URI.create(normalized);
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.flagManager = new FlagManager();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // Intentar cargar flags del backend en paralelo (no-bloqueo)
        loadFlagsAsync();
    }

    /**
     * Intenta cargar la configuración de flags del backend de forma asíncrona.
     */
    private void loadFlagsAsync() {
        new Thread(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/config"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    flagManager.updateFromBackendConfig(response.body());
                }
            } catch (Exception e) {
                logger.debug("No se pudo cargar flags del backend: {}", e.toString());
            }
        }, "BackendFlagLoader").start();
    }

    public List<ImageResult> searchReferences(List<String> terms, PoseData poseData, List<String> providers, int limit, String sessionId) {
        JSONObject payload = new JSONObject();
        payload.put("terms", new JSONArray(terms == null ? List.of() : terms));
        if (poseData != null) {
            JSONObject pose = new JSONObject();
            pose.put("landmarks", new JSONObject(poseData.getLandmarksJson()));
            pose.put("embedding", new JSONArray(poseData.getEmbeddingJson()));
            pose.put("poseAngles", new JSONObject(poseData.getPoseAnglesJson()));
            payload.put("poseData", pose);
        }
        if (providers != null && !providers.isEmpty()) {
            payload.put("providers", new JSONArray(providers));
        }
        payload.put("limit", limit);
        if (sessionId != null && !sessionId.isBlank()) {
            payload.put("sessionId", sessionId.trim());
        }

        try {
            IllegalStateException lastHttpError = null;
            for (String path : SEARCH_PATH_CANDIDATES) {
                HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parseResults(response.body());
                }
                if (response.statusCode() == 404) {
                    logger.info("Ruta backend no encontrada en {}{}, probando siguiente fallback", baseUri, path);
                    continue;
                }
                lastHttpError = new IllegalStateException("Backend returned HTTP " + response.statusCode() + " on " + path + ": " + truncate(response.body()));
                break;
            }
            if (lastHttpError != null) {
                throw lastHttpError;
            }
            throw new IllegalStateException("No se encontró un endpoint de búsqueda compatible en el backend.");
        } catch (Exception e) {
            logger.warn("Error llamando al backend {}: {}", baseUri, e.toString());
            throw new BackendRequestException("Backend search request failed", e);
        }
    }

    public boolean isReachable() {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/health"))
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Acceso a FlagManager para consultar decisiones de rollout.
     */
    public FlagManager getFlagManager() {
        return flagManager;
    }

    /**
     * Recarga manualmente los flags del backend.
     */
    public void reloadFlags() {
        loadFlagsAsync();
    }

    private List<ImageResult> parseResults(String body) {
        JSONArray array = new JSONArray(body == null ? "[]" : body);
        List<ImageResult> results = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            double similarity = item.optDouble("similarity", 0.0);
            String thumbnailUrl = item.optString("thumbnailUrl", "");
            String sourceUrl = item.optString("sourceUrl", thumbnailUrl);
            String provider = item.optString("provider", "backend");
            String title = item.optString("title", provider);
            String cachedPath = item.optString("cachedPath", "");
            String displayThumbnail = cachedPath == null || cachedPath.isBlank() ? thumbnailUrl : cachedPath;
            results.add(new ImageResult(
                    thumbnailUrl,
                    displayThumbnail,
                    sourceUrl,
                    title,
                    normalizeSimilarity(similarity),
                    sourceUrl,
                    provider,
                    "backend"
            ));
        }
        return results;
    }

    private static double normalizeSimilarity(double value) {
        if (value <= 1.0) {
            return Math.max(0.0, Math.min(1.0, value));
        }
        return Math.max(0.0, Math.min(100.0, value)) / 100.0;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 240 ? value.substring(0, 240) + "..." : value;
    }

    public static final class BackendRequestException extends RuntimeException {
        public BackendRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

