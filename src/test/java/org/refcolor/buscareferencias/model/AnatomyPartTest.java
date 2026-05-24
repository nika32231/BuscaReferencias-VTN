package org.refcolor.buscareferencias.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class AnatomyPartTest {

    @Test
    public void testEnumValues() {
        assertEquals("Cabeza", AnatomyPart.HEAD.getName());
        assertEquals("#F44336", AnatomyPart.HEAD.getHexColor());   // rojo vivo (actualizado)
        assertEquals("Torso", AnatomyPart.TORSO.getName());
        assertEquals("#1E88E5", AnatomyPart.TORSO.getHexColor());  // azul vivo (actualizado)
    }

    @Test
    public void testToString() {
        assertEquals("Brazos", AnatomyPart.ARMS.toString());
    }

    @Test
    public void testAllPartsPresent() {
        // 16 ítems de paleta + 10 internos = 26 total
        // Paleta: HEAD, TORSO, SHOULDERS, HIPS (4) + 6 pares bilaterales explícitos (12) = 16
        // Internos: ARMS, FOREARMS, HANDS, THIGHS, CALVES, FEET (6 combinados)
        //         + LEFT/RIGHT SHOULDER/HIP (4 splits de articulación) = 10
        assertEquals(26, AnatomyPart.values().length);
        long paletteCount = Arrays.stream(AnatomyPart.values())
                .filter(AnatomyPart::isPaletteItem).count();
        assertEquals(16, paletteCount, "Deben existir exactamente 16 ítems de paleta");
        long internalCount = Arrays.stream(AnatomyPart.values())
                .filter(p -> !p.isPaletteItem()).count();
        assertEquals(10, internalCount, "Deben existir 10 partes internas");
    }

    @Test
    public void testBilateralVariants() {
        assertEquals(AnatomyPart.LEFT_ARM,      AnatomyPart.ARMS.leftVariant());
        assertEquals(AnatomyPart.RIGHT_ARM,     AnatomyPart.ARMS.rightVariant());
        assertEquals(AnatomyPart.LEFT_SHOULDER, AnatomyPart.SHOULDERS.leftVariant());
        assertEquals(AnatomyPart.RIGHT_SHOULDER,AnatomyPart.SHOULDERS.rightVariant());
        assertEquals(AnatomyPart.LEFT_HIP,      AnatomyPart.HIPS.leftVariant());
        assertEquals(AnatomyPart.RIGHT_HIP,     AnatomyPart.HIPS.rightVariant());
        assertNull(AnatomyPart.HEAD.leftVariant(),   "HEAD no tiene split bilateral");
        assertNull(AnatomyPart.TORSO.rightVariant(), "TORSO no tiene split bilateral");
        // LEFT_ARM ahora SÍ es ítem de paleta (color explícito por lado)
        assertTrue(AnatomyPart.LEFT_ARM.isPaletteItem(),       "LEFT_ARM es ítem de paleta explícito");
        assertTrue(AnatomyPart.RIGHT_ARM.isPaletteItem(),      "RIGHT_ARM es ítem de paleta explícito");
        assertTrue(AnatomyPart.LEFT_FOREARM.isPaletteItem(),   "LEFT_FOREARM es ítem de paleta explícito");
        assertTrue(AnatomyPart.LEFT_HAND.isPaletteItem(),      "LEFT_HAND es ítem de paleta explícito");
        assertTrue(AnatomyPart.LEFT_THIGH.isPaletteItem(),     "LEFT_THIGH es ítem de paleta explícito");
        assertTrue(AnatomyPart.LEFT_CALF.isPaletteItem(),      "LEFT_CALF es ítem de paleta explícito");
        assertTrue(AnatomyPart.LEFT_FOOT.isPaletteItem(),      "LEFT_FOOT es ítem de paleta explícito");
        // SHOULDER y HIP (y combinados ARMS/FOREARMS/etc.) son internos
        assertFalse(AnatomyPart.LEFT_SHOULDER.isPaletteItem(), "LEFT_SHOULDER no es ítem de paleta");
        assertFalse(AnatomyPart.RIGHT_SHOULDER.isPaletteItem(),"RIGHT_SHOULDER no es ítem de paleta");
        assertFalse(AnatomyPart.LEFT_HIP.isPaletteItem(),      "LEFT_HIP no es ítem de paleta");
        assertFalse(AnatomyPart.RIGHT_HIP.isPaletteItem(),     "RIGHT_HIP no es ítem de paleta");
        assertFalse(AnatomyPart.ARMS.isPaletteItem(),      "ARMS es interno (combinado)");
        assertFalse(AnatomyPart.FOREARMS.isPaletteItem(),  "FOREARMS es interno (combinado)");
        assertFalse(AnatomyPart.THIGHS.isPaletteItem(),    "THIGHS es interno (combinado)");
        assertTrue(AnatomyPart.SHOULDERS.isPaletteItem(), "SHOULDERS sí es ítem de paleta");
        assertTrue(AnatomyPart.HIPS.isPaletteItem(),      "HIPS es ítem de paleta");
    }
}
