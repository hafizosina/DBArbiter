package com.dbadmin.ui;

import com.dbadmin.model.DbType;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.EnumMap;
import java.util.Map;

/**
 * Full-color database logos from resources/com/dbadmin/icons.
 * Each has a 16px PNG and a 32px "@2x" variant that JavaFX picks on HiDPI screens.
 */
final class DbIcons {

    private static final double SIZE = 16;
    private static final Map<DbType, Image> IMAGES = new EnumMap<>(DbType.class);

    private DbIcons() {
    }

    /** The logo for a connection, with a green badge when it is connected. */
    static Node connection(DbType type, boolean connected) {
        StackPane icon = new StackPane(new ImageView(image(type)));
        icon.setMinSize(SIZE + 2, SIZE + 2);
        icon.setMaxSize(SIZE + 2, SIZE + 2);
        if (connected) {
            Circle badge = new Circle(3.5, Color.web("#2e7d32"));
            badge.setStroke(Color.WHITE);
            badge.setStrokeWidth(1.2);
            StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
            icon.getChildren().add(badge);
        }
        return icon;
    }

    private static Image image(DbType type) {
        return IMAGES.computeIfAbsent(type, t -> {
            String file = "/com/dbadmin/icons/" + t.name().toLowerCase() + ".png";
            var url = DbIcons.class.getResource(file);
            if (url == null) throw new IllegalStateException("Missing resource " + file);
            return new Image(url.toExternalForm());
        });
    }
}
