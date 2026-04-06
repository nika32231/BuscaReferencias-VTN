package org.refcolor.buscareferencias.model;

import javafx.scene.paint.Color;

public class AnatomyColors {
    public static final Color HEAD = Color.web("#FF0000");
    public static final Color TORSO = Color.web("#0000FF");
    public static final Color ARMS = Color.web("#FFFF00");
    public static final Color FOREARMS = Color.web("#FFA500");
    public static final Color HANDS = Color.web("#800080");
    public static final Color THIGHS = Color.web("#008000");
    public static final Color CALVES = Color.web("#00FFFF");
    public static final Color FEET = Color.web("#FFC0CB");

    public static Color[] getAll() {
        return new Color[]{HEAD, TORSO, ARMS, FOREARMS, HANDS, THIGHS, CALVES, FEET};
    }

    public static String getName(Color color) {
        if (color.equals(HEAD)) return "Cabeza";
        if (color.equals(TORSO)) return "Torso";
        if (color.equals(ARMS)) return "Brazos";
        if (color.equals(FOREARMS)) return "Antebrazos";
        if (color.equals(HANDS)) return "Manos";
        if (color.equals(THIGHS)) return "Muslos";
        if (color.equals(CALVES)) return "Pantorrillas";
        if (color.equals(FEET)) return "Pies";
        return "Desconocido";
    }
}
