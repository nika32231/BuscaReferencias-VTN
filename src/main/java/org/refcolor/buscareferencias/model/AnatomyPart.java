package org.refcolor.buscareferencias.model;

/**
 * Partes anatómicas del cuerpo humano usadas en la paleta de dibujo y en el
 * análisis de pose.
 *
 * <p><b>Ítems de paleta (22 en total, visibles en la UI):</b></p>
 * <ul>
 *   <li>No bilaterales (4): HEAD, TORSO, SHOULDERS, HIPS</li>
 *   <li>Bilaterales combinados (6): ARMS, FOREARMS, HANDS, THIGHS, CALVES, FEET
 *       — el {@link org.refcolor.buscareferencias.utils.DrawingProcessor} los divide
 *       automáticamente en L/R por posición del pixel.</li>
 *   <li>Bilaterales explícitos (12): LEFT_ARM / RIGHT_ARM, LEFT_FOREARM /
 *       RIGHT_FOREARM, LEFT_HAND / RIGHT_HAND, LEFT_THIGH / RIGHT_THIGH,
 *       LEFT_CALF / RIGHT_CALF, LEFT_FOOT / RIGHT_FOOT — cada uno con su propio
 *       color único para que el usuario pueda dibujar cada lado de forma
 *       independiente y sin ambigüedad.</li>
 * </ul>
 *
 * <p><b>Splits internos (4, NO aparecen en la paleta):</b>
 * LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP — los genera
 * DrawingProcessor a partir de los dots de SHOULDERS / HIPS.</p>
 */
public enum AnatomyPart {

    // ── Ítems de paleta — no bilaterales ─────────────────────────────────────
    HEAD        ("Cabeza",          "#F44336"),   // H≈4°   — rojo vivo (antes #B71C1C casi negro)
    TORSO       ("Torso",           "#1E88E5"),   // H≈208° — azul vivo (antes #0D47A1 casi negro)
    SHOULDERS   ("Hombros",         "#7CB342"),   // H≈89°  — puntos de articulación exactos
    HIPS        ("Caderas",         "#9C27B0"),   // H≈291° — puntos de articulación exactos

    // ── Ítems de paleta — bilaterales combinados (split automático por posición)
    ARMS        ("Brazos",          "#FBC02D"),   // H≈43°
    FOREARMS    ("Antebrazos",      "#E65100"),   // H≈21°
    HANDS       ("Manos",           "#4A148C"),   // H≈267°
    THIGHS      ("Muslos",          "#1B5E20"),   // H≈125°
    CALVES      ("Pantorrillas",    "#006064"),   // H≈182°
    FEET        ("Pies",            "#880E4F"),   // H≈328°

    // ── Ítems de paleta — bilaterales explícitos (color único por lado) ───────
    LEFT_ARM    ("Brazo Izq.",       "#4CD11F"),  // H≈105°
    RIGHT_ARM   ("Brazo Der.",       "#D9CF20"),  // H≈57°
    LEFT_FOREARM("Antebrazo Izq.",   "#1FCC64"),  // H≈144°
    RIGHT_FOREARM("Antebrazo Der.",  "#B7D920"),  // H≈71°
    LEFT_HAND   ("Mano Izq.",        "#18CC96"),  // H≈162°
    RIGHT_HAND  ("Mano Der.",        "#00BCD4"),  // H≈187° — cian (antes #1694D9 H≈201°; gap TORSO 6°→21°)
    LEFT_THIGH  ("Muslo Izq.",       "#3D5AFE"),  // H≈231° — índigo vivo (antes #222EE0; gap TORSO 22°→23°)
    RIGHT_THIGH ("Muslo Der.",       "#7C4DFF"),  // H≈256° — violeta (antes #4B2BD9; gap Izq. 15°→25° ✓)
    LEFT_CALF   ("Pantorrilla Izq.", "#931FD1"),  // H≈279°
    RIGHT_CALF  ("Pantorrilla Der.", "#D916BF"),  // H≈308°
    LEFT_FOOT   ("Pie Izq.",         "#D11F99"),  // H≈319°
    RIGHT_FOOT  ("Pie Der.",         "#D9164A"),  // H≈344°

    // ── Splits bilaterales INTERNOS — NO aparecen en la paleta ───────────────
    LEFT_SHOULDER ("Hombro Izq.",   "#7CB342"),  // generado desde SHOULDERS
    RIGHT_SHOULDER("Hombro Der.",   "#7CB342"),
    LEFT_HIP    ("Cadera Izq.",     "#9C27B0"),  // generado desde HIPS
    RIGHT_HIP   ("Cadera Der.",     "#9C27B0");

    private final String name;
    private final String hexColor;

    AnatomyPart(String name, String hexColor) {
        this.name = name;
        this.hexColor = hexColor;
    }

    public String getName()     { return name; }
    public String getHexColor() { return hexColor; }

    /**
     * {@code true} para las 16 partes que aparecen en la paleta de la UI:
     * <ul>
     *   <li>No bilaterales: HEAD, TORSO, SHOULDERS, HIPS (4)</li>
     *   <li>Bilaterales explícitos por lado: LEFT/RIGHT ARM, FOREARM, HAND, THIGH, CALF, FOOT (12)</li>
     * </ul>
     *
     * <p>Los combinados (ARMS, FOREARMS, HANDS, THIGHS, CALVES, FEET) y los splits
     * internos de hombro/cadera (LEFT/RIGHT SHOULDER/HIP) son solo internos.</p>
     */
    public boolean isPaletteItem() {
        return this == HEAD      || this == TORSO
            || this == SHOULDERS || this == HIPS
            || this == LEFT_ARM      || this == RIGHT_ARM
            || this == LEFT_FOREARM  || this == RIGHT_FOREARM
            || this == LEFT_HAND     || this == RIGHT_HAND
            || this == LEFT_THIGH    || this == RIGHT_THIGH
            || this == LEFT_CALF     || this == RIGHT_CALF
            || this == LEFT_FOOT     || this == RIGHT_FOOT;
    }

    /**
     * Devuelve la variante izquierda para partes bilaterales, o {@code null}
     * para HEAD y TORSO (que no tienen split lateral).
     */
    public AnatomyPart leftVariant() {
        return switch (this) {
            case SHOULDERS -> LEFT_SHOULDER;
            case ARMS      -> LEFT_ARM;
            case FOREARMS  -> LEFT_FOREARM;
            case HANDS     -> LEFT_HAND;
            case HIPS      -> LEFT_HIP;
            case THIGHS    -> LEFT_THIGH;
            case CALVES    -> LEFT_CALF;
            case FEET      -> LEFT_FOOT;
            default        -> null;
        };
    }

    /**
     * Devuelve la variante derecha para partes bilaterales, o {@code null}
     * para HEAD y TORSO.
     */
    public AnatomyPart rightVariant() {
        return switch (this) {
            case SHOULDERS -> RIGHT_SHOULDER;
            case ARMS      -> RIGHT_ARM;
            case FOREARMS  -> RIGHT_FOREARM;
            case HANDS     -> RIGHT_HAND;
            case HIPS      -> RIGHT_HIP;
            case THIGHS    -> RIGHT_THIGH;
            case CALVES    -> RIGHT_CALF;
            case FEET      -> RIGHT_FOOT;
            default        -> null;
        };
    }

    @Override
    public String toString() { return name; }
}
