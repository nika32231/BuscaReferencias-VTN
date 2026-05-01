package org.refcolor.buscareferencias.model;

public class ImageResult {
    private final String thumbnailUrl;
    private final String originalUrl;
    private final String title;
    private double score;

    public ImageResult(String thumbnailUrl, String originalUrl, String title, double score) {
        this.thumbnailUrl = thumbnailUrl;
        this.originalUrl = originalUrl;
        this.title = title;
        this.score = score;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getTitle() {
        return title;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}
