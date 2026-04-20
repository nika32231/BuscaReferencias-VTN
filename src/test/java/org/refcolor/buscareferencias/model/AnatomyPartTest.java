package org.refcolor.buscareferencias.model;

import org.junit.jupiter.api.Test;
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
        assertEquals(8, AnatomyPart.values().length);
    }
}
