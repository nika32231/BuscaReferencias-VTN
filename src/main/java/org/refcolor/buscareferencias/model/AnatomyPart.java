package org.refcolor.buscareferencias.model;

public enum AnatomyPart {
    HEAD("Cabeza", "#B71C1C"),
    TORSO("Torso", "#0D47A1"),
    ARMS("Brazos", "#FBC02D"),
    FOREARMS("Antebrazos", "#E65100"),
    HANDS("Manos", "#4A148C"),
    THIGHS("Muslos", "#1B5E20"),
    CALVES("Pantorrillas", "#006064"),
    FEET("Pies", "#880E4F");

    private final String name;
    private final String hexColor;

    AnatomyPart(String name, String hexColor) {
        this.name = name;
        this.hexColor = hexColor;
    }

    public String getName() {
        return name;
    }

    public String getHexColor() {
        return hexColor;
    }

    @Override
    public String toString() {
        return name;
    }
}
