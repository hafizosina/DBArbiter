package com.dbadmin.ui;

import com.dbadmin.model.AppSettings;
import com.dbadmin.model.AppSettings.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings: categories on the left, the chosen category's options on the right. A theme picked here
 * is previewed at once; OK saves it, Cancel puts the previous one back. The result is the new settings.
 */
class SettingsDialog extends Dialog<AppSettings> {

    private final AppSettings edited;

    SettingsDialog(Window owner) {
        initOwner(owner);
        setTitle("Settings");
        setHeaderText(null);
        edited = Themes.settings();

        Map<String, Node> pages = new LinkedHashMap<>();
        pages.put("Theme", themePage());
        pages.put("Exit & startup", exitPage());
        pages.put("Updates", updatesPage());

        BorderPane content = new BorderPane();
        ListView<String> categories = new ListView<>();
        categories.getItems().addAll(pages.keySet());
        categories.getStyleClass().add("section-nav");
        categories.getSelectionModel().selectedItemProperty().addListener((o, a, name) -> {
            if (name != null) content.setCenter(pages.get(name));
        });
        categories.getSelectionModel().selectFirst();

        SplitPane split = new SplitPane(categories, content);
        split.setDividerPositions(0.28);
        split.setPrefSize(680, 470);
        VBox root = new VBox(split);
        root.setPadding(new Insets(10));
        VBox.setVgrow(split, Priority.ALWAYS);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(button -> {
            if (button == ButtonType.OK) return edited;
            Themes.revertPreview(); // cancelled: undo the preview
            return null;
        });
    }

    private Node themePage() {
        Label title = new Label("Theme");
        title.getStyleClass().add("form-title");
        Label hint = new Label("Applies to the whole window. Picking one previews it; OK keeps it.");
        hint.getStyleClass().add("hint-small");

        ToggleGroup group = new ToggleGroup();
        HBox choices = new HBox(16);
        for (Theme theme : Theme.values()) {
            RadioButton radio = new RadioButton(theme.toString());
            radio.setToggleGroup(group);
            radio.setUserData(theme);
            radio.setSelected(theme == edited.getTheme());
            VBox choice = new VBox(8, swatch(theme), radio);
            choice.setAlignment(Pos.CENTER);
            choice.setOnMouseClicked(e -> radio.setSelected(true));
            choices.getChildren().add(choice);
        }
        // data-compare status colors, for the theme picked above
        Label colorsTitle = new Label("Data compare status colors");
        colorsTitle.setStyle("-fx-font-weight: bold;");
        Label colorsHint = new Label("Background of the Status cell in a Data Compare report, for the theme picked above.");
        colorsHint.getStyleClass().add("hint-small");
        GridPane colors = new GridPane();
        colors.setHgap(10);
        colors.setVgap(6);
        Map<String, ColorPicker> pickers = new LinkedHashMap<>();
        int row = 0;
        for (String status : AppSettings.STATUSES) {
            ColorPicker picker = new ColorPicker();
            picker.setOnAction(e -> {
                edited.setStatusColor(edited.getTheme(), status, toHex(picker.getValue()));
                Themes.preview(edited);
            });
            pickers.put(status, picker);
            Label name = new Label(label(status));
            name.setMnemonicParsing(false);
            colors.addRow(row++, name, picker);
        }
        Runnable loadPickers = () -> pickers.forEach((status, picker) ->
                picker.setValue(Color.web(edited.statusColor(edited.getTheme(), status))));
        loadPickers.run();
        Button reset = new Button("Reset to defaults");
        reset.setOnAction(e -> {
            for (String status : AppSettings.STATUSES) {
                edited.setStatusColor(edited.getTheme(), status, AppSettings.defaultStatusColor(edited.getTheme(), status));
            }
            loadPickers.run();
            Themes.preview(edited);
        });

        group.selectedToggleProperty().addListener((o, a, toggle) -> {
            if (toggle == null) return;
            edited.setTheme((Theme) toggle.getUserData());
            loadPickers.run(); // each theme has its own colors
            Themes.preview(edited);
        });

        VBox page = new VBox(10, title, hint, choices, new javafx.scene.control.Separator(), colorsTitle, colorsHint,
                colors, reset);
        page.setPadding(new Insets(12, 16, 12, 16));
        return page;
    }

    private Node exitPage() {
        Label title = new Label("Exit & startup");
        title.getStyleClass().add("form-title");
        javafx.scene.control.CheckBox confirm = new javafx.scene.control.CheckBox("Ask \"Close DBArbiter?\" before the window closes");
        confirm.setSelected(edited.isConfirmExit());
        confirm.selectedProperty().addListener((o, a, on) -> edited.setConfirmExit(on));
        Label confirmHint = new Label("Off: the window closes at once. Unsaved scripts, cell edits and compares are asked about either way.");
        confirmHint.getStyleClass().add("hint-small");
        confirmHint.setWrapText(true);
        javafx.scene.control.CheckBox reopen = new javafx.scene.control.CheckBox("Open the tabs that were open last time");
        reopen.setSelected(edited.isReopenTabs());
        reopen.selectedProperty().addListener((o, a, on) -> edited.setReopenTabs(on));
        Label reopenHint = new Label("Saved scripts, tables (their connection connects again) and saved compares. A script never"
                + " saved is not kept.");
        reopenHint.getStyleClass().add("hint-small");
        reopenHint.setWrapText(true);
        VBox page = new VBox(10, title, confirm, confirmHint, reopen, reopenHint);
        page.setPadding(new Insets(12, 16, 12, 16));
        return page;
    }

    private Node updatesPage() {
        Label title = new Label("Updates");
        title.getStyleClass().add("form-title");
        javafx.scene.control.CheckBox check = new javafx.scene.control.CheckBox("Check for a new version when the app starts");
        check.setSelected(edited.isCheckForUpdates());
        check.selectedProperty().addListener((o, a, on) -> edited.setCheckForUpdates(on));
        Label hint = new Label("Asks GitHub for the newest release (nothing else is sent). When there is one, the bar at the "
                + "bottom shows a link to download it. Turn this off where the app must not go online.");
        hint.getStyleClass().add("hint-small");
        hint.setWrapText(true);
        VBox page = new VBox(10, title, check, hint);
        page.setPadding(new Insets(12, 16, 12, 16));
        return page;
    }

    private static String label(String status) {
        return switch (status) {
            case "ONLY_SOURCE" -> "ONLY_IN_SOURCE";
            case "ONLY_TARGET" -> "ONLY_IN_TARGET";
            default -> status;
        };
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
    }

    /** A small picture of the theme: title bar, navigator and editor with a few colored "words". */
    private static Region swatch(Theme theme) {
        // background, title bar, navigator, editor, keyword, text, keyword, string, comment, table, table, view
        String[] c = switch (theme) {
            case LIGHT -> new String[]{"#f4f4f4", "#e4e8ee", "#f5f5f5", "#ffffff", "#0033b3", "#333333", "#0033b3", "#067d17",
                    "#8c8c8c", "#1565c0", "#1565c0", "#6a1b9a"};
            case DARK -> new String[]{"#2b2d30", "#3c3f41", "#2b2d30", "#1e1f22", "#cf8e6d", "#bcbec4", "#cf8e6d", "#6aab73",
                    "#7a7e85", "#6aa9ff", "#6aa9ff", "#c792ea"};
            case CATPPUCCIN -> new String[]{"#181825", "#11111b", "#181825", "#1e1e2e", "#cba6f7", "#cdd6f4", "#cba6f7", "#a6e3a1",
                    "#6c7086", "#89b4fa", "#89b4fa", "#f5c2e7"};
        };
        Pane p = new Pane(
                rect(0, 0, 150, 92, c[0]),
                rect(0, 0, 150, 14, c[1]),
                rect(0, 14, 40, 78, c[2]),
                rect(42, 18, 104, 70, c[3]),
                rect(48, 26, 26, 6, c[4]),
                rect(78, 26, 40, 6, c[5]),
                rect(48, 38, 30, 6, c[6]),
                rect(82, 38, 34, 6, c[7]),
                rect(48, 50, 50, 6, c[8]),
                rect(6, 22, 26, 5, c[9]),
                rect(6, 32, 22, 5, c[10]),
                rect(6, 42, 26, 5, c[11]));
        p.setPrefSize(150, 92);
        p.setMinSize(150, 92);
        p.setMaxSize(150, 92);
        p.setStyle("-fx-border-color: #8a8f96; -fx-border-radius: 3;");
        return p;
    }

    private static Rectangle rect(double x, double y, double w, double h, String color) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setFill(Color.web(color));
        return r;
    }
}
