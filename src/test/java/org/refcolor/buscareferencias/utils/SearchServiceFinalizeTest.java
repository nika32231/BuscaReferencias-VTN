package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.service.SearchService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchServiceFinalizeTest {

    @Test
    void finalizeReturnsUpToMaxRankedResults() {
        List<ImageResult> input = List.of(
                result("a", 0.9),
                result("b", 0.7),
                result("c", 0.5),
                result("d", 0.3),
                result("e", 0.2),
                result("f", 0.1),
                result("g", 0.05),
                result("h", 0.01),
                result("i", 0.005),
                result("j", 0.001),
                result("k", 0.0005)
        );

        List<ImageResult> out = SearchService.finalizeResults(input, true);
        assertTrue(out.size() >= PoseToleranceConfig.minResults());
        assertTrue(out.size() <= PoseToleranceConfig.maxResults());
        assertEquals(0.9, out.get(0).getScore(), 0.0001);
        assertTrue(out.get(0).getScore() >= out.get(out.size() - 1).getScore());
    }

    @Test
    void finalizeRelaxesWhenFewStrictMatches() {
        List<ImageResult> input = List.of(
                result("a", 0.8),
                result("b", 0.6),
                result("c", 0.15),
                result("d", 0.12),
                result("e", 0.10)
        );

        List<ImageResult> out = SearchService.finalizeResults(input, true);
        assertTrue(out.size() >= 5);
    }

    private static ImageResult result(String title, double score) {
        ImageResult r = new ImageResult("file:///tmp/" + title + ".jpg", "file:///tmp/" + title + ".jpg",
                "file:///tmp/" + title + ".jpg", title, score, "file:///tmp/" + title + ".jpg", "local", "");
        return r;
    }
}
