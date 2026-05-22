package org.refcolor.buscareferencias.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class AnatomyPartTest {

    @Test
    public void testEnumValues() {
        assertEquals("Cabeza", AnatomyPart.HEAD.getName());
        assertEquals("#B71C1C", AnatomyPart.HEAD.getHexColor());
        assertEquals("Torso", AnatomyPart.TORSO.getName());
        assertEquals("#0D47A1", AnatomyPart.TORSO.getHexColor());
    }

    @Test
    public void testToString() {
        assertEquals("Brazos", AnatomyPart.ARMS.toString());
    }

    @Test
    public void testAllPartsPresent() {
        // 8 ítems de paleta + 12 splits bilaterales internos = 20 total
        assertEquals(20, AnatomyPart.values().length);
        long paletteCount = Arrays.stream(AnatomyPart.values())
                .filter(AnatomyPart::isPaletteItem).count();
        assertEquals(8, paletteCount, "Deben existir exactamente 8 ítems de paleta");
        long bilateralCount = Arrays.stream(AnatomyPart.values())
                .filter(p -> !p.isPaletteItem()).count();
        assertEquals(12, bilateralCount, "Deben existir 12 splits bilaterales internos");
    }

    @Test
    public void testBilateralVariants() {
        assertEquals(AnatomyPart.LEFT_ARM,  AnatomyPart.ARMS.leftVariant());
        assertEquals(AnatomyPart.RIGHT_ARM, AnatomyPart.ARMS.rightVariant());
        assertNull(AnatomyPart.HEAD.leftVariant(),  "HEAD no tiene split bilateral");
        assertNull(AnatomyPart.TORSO.rightVariant(), "TORSO no tiene split bilateral");
        assertFalse(AnatomyPart.LEFT_ARM.isPaletteItem(), "LEFT_ARM no es ítem de paleta");
        assertTrue(AnatomyPart.ARMS.isPaletteItem(), "ARMS sí es ítem de paleta");
    }
}
