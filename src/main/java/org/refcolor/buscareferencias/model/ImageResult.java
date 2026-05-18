package org.refcolor.buscareferencias.model;

public class ImageResult {
    private final String thumbnailUrl;
    private final String displayThumbnailUrl;
    private final String originalUrl;
    private final String title;
    private final String sourcePageUrl;
    private final String source;
    private final String searchQuery;
    private double score;
    private PoseData poseData;

    public ImageResult(String thumbnailUrl, String originalUrl, String title, double score) {
        this(thumbnailUrl, thumbnailUrl, originalUrl, title, score, originalUrl, "", "");
    }

    public ImageResult(String thumbnailUrl, String displayThumbnailUrl, String originalUrl, String title, double score, String sourcePageUrl, String source, String searchQuery) {
        this.thumbnailUrl = thumbnailUrl;
        this.displayThumbnailUrl = displayThumbnailUrl == null || displayThumbnailUrl.isBlank() ? thumbnailUrl : displayThumbnailUrl;
        this.originalUrl = originalUrl;
        this.title = title;
        this.sourcePageUrl = sourcePageUrl == null || sourcePageUrl.isBlank() ? originalUrl : sourcePageUrl;
        this.source = source == null ? "" : source;
        this.searchQuery = searchQuery == null ? "" : searchQuery;
        this.score = score;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getDisplayThumbnailUrl() {
        return displayThumbnailUrl;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getSourcePageUrl() {
        return sourcePageUrl;
    }

    public String getSource() {
        return source;
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public PoseData getPoseData() {
        return poseData;
    }

    public void setPoseData(PoseData poseData) {
        this.poseData = poseData;
    }
}
