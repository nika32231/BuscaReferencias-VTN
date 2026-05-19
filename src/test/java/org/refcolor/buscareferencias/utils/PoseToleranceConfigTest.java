package org.refcolor.buscareferencias.utils;

import org.junit.jupiter.api.Test;
import org.refcolor.buscareferencias.model.AnatomyPart;

import static org.junit.jupiter.api.Assertions.*;

class PoseToleranceConfigTest {

    @Test
    void skeletonToleranceUsesDefaultsWhenNoOverride() {
        double head = PoseToleranceConfig.skeletonTolerance(AnatomyPart.HEAD);
        double hands = PoseToleranceConfig.skeletonTolerance(AnatomyPart.HANDS);
        assertTrue(head > 0);
        assertTrue(hands >= head, "Manos suelen tener más margen que la cabeza");
    }

    @Test
    void localImageDirDefaultsToCacheThumbnails() {
        assertEquals("cache/thumbnails", PoseToleranceConfig.localImageDir());
    }

    @Test
    void resultWindowIsFiveToTen() {
        assertEquals(5, PoseToleranceConfig.minResults());
        assertEquals(10, PoseToleranceConfig.maxResults());
        assertTrue(PoseToleranceConfig.maxResults() >= PoseToleranceConfig.minResults());
    }
}
