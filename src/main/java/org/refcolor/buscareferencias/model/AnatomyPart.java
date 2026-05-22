package org.refcolor.buscareferencias.model;

/**
 * Partes anatómicas del cuerpo humano usadas en la paleta de dibujo y en el
 * análisis de pose.
 *
 * <p>Las primeras 8 entradas son los <em>ítems de paleta</em> (visibles en la UI).
 * Las 12 restantes son <em>splits bilaterales internos</em>: el {@link
 * org.refcolor.buscareferencias.utils.DrawingProcessor} los crea automáticamente
 * cuando detecta trazos del mismo color a ambos lados del canvas, permitiendo
 * comparar el brazo izquierdo con el <em>landmark</em> izquierdo de MediaPipe y el
 * brazo derecho con el derecho.</p>
 */
public enum AnatomyPart {

    // ── Ítems de paleta (visibles en la UI) ──────────────────────────────────
    HEAD        ("Cabeza",          "#B71C1C"),
    TORSO       ("Torso",           "#0D47A1"),
    ARMS        ("Brazos",          "#FBC02D"),
    FOREARMS    ("Antebrazos",      "#E65100"),
    HANDS       ("Manos",           "#4A148C"),
    THIGHS      ("Muslos",          "#1B5E20"),
    CALVES      ("Pantorrillas",    "#006064"),
    FEET        ("Pies",            "#880E4F"),

    // ── Splits bilaterales internos (NO aparecen en la paleta) ───────────────
    LEFT_ARM    ("Brazo Izq.",          "#FBC02D"),
    RIGHT_ARM   ("Brazo Der.",          "#FBC02D"),
    LEFT_FOREARM("Antebrazo Izq.",      "#E65100"),
    RIGHT_FOREARM("Antebrazo Der.",     "#E65100"),
    LEFT_HAND   ("Mano Izq.",           "#4A148C"),
    RIGHT_HAND  ("Mano Der.",           "#4A148C"),
    LEFT_THIGH  ("Muslo Izq.",          "#1B5E20"),
    RIGHT_THIGH ("Muslo Der.",          "#1B5E20"),
    LEFT_CALF   ("Pantorrilla Izq.",    "#006064"),
    RIGHT_CALF  ("Pantorrilla Der.",    "#006064"),
    LEFT_FOOT   ("Pie Izq.",            "#880E4F"),
    RIGHT_FOOT  ("Pie Der.",            "#880E4F");

    private final String name;
    private final String hexColor;

    AnatomyPart(String name, String hexColor) {
        this.name = name;
        this.hexColor = hexColor;
    }

    public String getName()     { return name; }
    public String getHexColor() { return hexColor; }

    /**
     * {@code true} para las 8 partes que aparecen en la paleta de la UI.
     * Los splits bilaterales devuelven {@code false}.
     */
    public boolean isPaletteItem() {
        return this == HEAD || this == TORSO
            || this == ARMS      || this == FOREARMS || this == HANDS
            || this == THIGHS    || this == CALVES   || this == FEET;
    }

    /**
     * Devuelve la variante izquierda para partes bilaterales, o {@code null}
     * para HEAD y TORSO (que no tienen split lateral).
     */
    public AnatomyPart leftVariant() {
        return switch (this) {
            case ARMS     -> LEFT_ARM;
            case FOREARMS -> LEFT_FOREARM;
            case HANDS    -> LEFT_HAND;
            case THIGHS   -> LEFT_THIGH;
            case CALVES   -> LEFT_CALF;
            case FEET     -> LEFT_FOOT;
            default       -> null;
        };
    }

    /**
     * Devuelve la variante derecha para partes bilaterales, o {@code null}
     * para HEAD y TORSO.
     */
    public AnatomyPart rightVariant() {
        return switch (this) {
            case ARMS     -> RIGHT_ARM;
            case FOREARMS -> RIGHT_FOREARM;
            case HANDS    -> RIGHT_HAND;
            case THIGHS   -> RIGHT_THIGH;
            case CALVES   -> RIGHT_CALF;
            case FEET     -> RIGHT_FOOT;
            default       -> null;
        };
    }

    @Override
    public String toString() { return name; }
}
