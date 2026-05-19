package org.refcolor.buscareferencias.service;

import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.core.FeatureFlags;
import org.json.JSONArray;
import org.json.JSONObject;
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

/**
 * Servicio para consultar la API oficial de Pexels.
 *
 * Nota importante: la API key NO debe hardcodearse. Se lee desde:
 *  - FeatureFlags.getRaw("pexels.apiKey")
 *  - O env var PEXELS_API_KEY
 *  - O env var APP_PEXELS_API_KEY
 */
public final class PexelsService {
    private static final Logger logger = LoggerFactory.getLogger(PexelsService.class);
    private static final String ENDPOINT = "https://api.pexels.com/v1/search";
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private PexelsService() {}

    private static String resolveApiKey() {
        String fromFlags = FeatureFlags.getRaw("pexels.apiKey");
        if (fromFlags != null && !fromFlags.isBlank()) return fromFlags.trim();
        String env = System.getenv("PEXELS_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        String env2 = System.getenv("APP_PEXELS_API_KEY");
        if (env2 != null && !env2.isBlank()) return env2.trim();
        return null;
    }

    /**
     * Busca imágenes en Pexels.
     * @param terms lista de términos (se unirán con espacios)
     * @param perPage cantidad máxima por página (hasta 80)
     * @param orientation optional: "portrait"|"landscape"|"square"
     * @param size optional: "large"|"medium"|"small" (Pexels acepta size en algunos endpoints)
     * @return lista de ImageResult con thumbnailUrl, displayThumbnailUrl (local cache si se pudo descargar), originalUrl, etc.
     */
    public static List<ImageResult> searchImages(List<String> terms, int perPage, String orientation, String size) {
        List<ImageResult> out = new ArrayList<>();
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            logger.warn("[PEXELS] API key no encontrada. Configure PEXELS_API_KEY o app property 'pexels.apiKey'.");
            return out;
        }

        String query = "";
        if (terms != null && !terms.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String t : terms) {
                if (t == null) continue;
                String s = t.trim();
                if (s.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(s);
            }
            query = sb.toString();
        }
        if (query.isBlank()) query = "people pose"; // fallback razonable

        logger.info("[PEXELS] Searching... query='{}' perPage={} orientation={} size={}", query, perPage, orientation, size);

        try {
            StringBuilder uriBuilder = new StringBuilder(ENDPOINT).append("?query=").append(java.net.URLEncoder.encode(query, StandardCharsets.UTF_8));
            int p = Math.max(1, Math.min(perPage, 80));
            uriBuilder.append("&per_page=").append(p);
            if (orientation != null && !orientation.isBlank()) uriBuilder.append("&orientation=").append(java.net.URLEncoder.encode(orientation.trim(), StandardCharsets.UTF_8));
            if (size != null && !size.isBlank()) uriBuilder.append("&size=").append(java.net.URLEncoder.encode(size.trim(), StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(uriBuilder.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                logger.warn("[PEXELS] HTTP {} returned for query='{}'. Body: {}", resp.statusCode(), query, resp.body() == null ? "<no-body>" : resp.body());
                return out;
            }

            JSONObject json = new JSONObject(resp.body());
            JSONArray photos = json.optJSONArray("photos");
            int found = photos == null ? 0 : photos.length();
            logger.info("[PEXELS] Images found: {}", found);

            if (photos != null) {
                for (int i = 0; i < photos.length(); i++) {
                    JSONObject photo = photos.getJSONObject(i);
                    JSONObject src = photo.optJSONObject("src");
                    if (src == null) continue;
                    String thumb = firstNonBlank(src.optString("medium", ""), src.optString("small", ""), src.optString("tiny", ""));
                    String original = firstNonBlank(src.optString("original", ""), src.optString("large", ""), photo.optString("url", ""));
                    String photographer = photo.optString("photographer", "");
                    String pageUrl = photo.optString("url", "");
                    int width = photo.optInt("width", -1);
                    int height = photo.optInt("height", -1);
                    String title = photo.optString("alt", photographer != null ? photographer : "Pexels");

                    // Descargar miniatura a caché temporal (siempre que la URL sea remota)
                    String displayThumb = thumb;
                    try {
                        displayThumb = MediaPipeService.ImageCacheService.getLocalThumbnailPath(thumb);
                    } catch (Exception e) {
                        logger.debug("[PEXELS] No se pudo cachear thumbnail: {}", e.toString());
                    }

                    ImageResult ir = new ImageResult(
                            thumb,
                            displayThumb,
                            original,
                            title,
                            0.0,
                            pageUrl,
                            "pexels",
                            query
                    );
                    out.add(ir);
                }
            }

            logger.info("[PEXELS] Downloading thumbnails... (requested {})", p);
            logger.info("[PEXELS] Download complete: {} thumbnails prepared", out.size());
            return out;
        } catch (Exception e) {
            logger.error("[PEXELS] Error searching Pexels", e);
            return out;
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}

