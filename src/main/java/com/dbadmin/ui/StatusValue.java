package com.dbadmin.ui;

/**
 * A data-compare status in a grid cell ("MATCH", "CHANGED", ...). The cell gets the status color
 * from the theme settings.
 */
record StatusValue(String status, String label) {

    @Override
    public String toString() {
        return label;
    }

    /** Readable text on the given background: dark on light colors, light on dark ones. */
    static String textColorOn(String background) {
        javafx.scene.paint.Color c = javafx.scene.paint.Color.web(background);
        double luminance = 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
        return luminance > 0.55 ? "#1b1b1b" : "#f2f2f2";
    }
}
