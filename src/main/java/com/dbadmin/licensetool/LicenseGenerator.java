package com.dbadmin.licensetool;

import com.dbadmin.license.License;
import com.dbadmin.ui.MainWindow;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.beans.property.SimpleStringProperty;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * Issues DBAdmin license keys. Runs only on the signer's machine and is not part of the app jar.
 * {@code --init} creates the signing key pair (once).
 */
public class LicenseGenerator extends Application {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    /** Plain entry point (does not extend Application), so the fat jar can start JavaFX from the classpath. */
    public static final class Main {
        public static void main(String[] args) throws Exception {
            if (args.length > 0 && args[0].equals("--init")) {
                System.out.println(SigningKeys.create());
                System.err.println("Created the signing key in " + SigningKeys.DIR + ". Back this folder up.");
                return;
            }
            launch(LicenseGenerator.class, args);
        }
    }

    private final TextField name = new TextField();
    private final ToggleGroup type = new ToggleGroup();
    private final DatePicker start = new DatePicker(LocalDate.now());
    private final DatePicker end = new DatePicker();
    private final TextArea key = new TextArea();
    private final TableView<SigningKeys.Issued> issued = new TableView<>();

    @Override
    public void start(Stage stage) {
        name.setPromptText("PT Antam – DBA Team");
        HBox types = new HBox(12);
        for (License.Type t : License.Type.values()) {
            RadioButton b = new RadioButton(t.label());
            b.setUserData(t);
            b.setToggleGroup(type);
            types.getChildren().add(b);
        }
        type.selectToggle(type.getToggles().get(0));
        type.selectedToggleProperty().addListener((o, a, b) -> updateEnd());
        start.valueProperty().addListener((o, a, b) -> updateEnd());
        updateEnd();

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("Licensed to:"), name);
        form.addRow(1, new Label("Type:"), types);
        form.addRow(2, new Label("Starts:"), new HBox(10, start, new Label("Ends:"), end));
        GridPane.setHgrow(name, Priority.ALWAYS);
        for (var n : form.getChildren()) {
            if (n instanceof HBox h) h.setAlignment(Pos.CENTER_LEFT);
        }

        Button generate = new Button("Generate key");
        generate.setDefaultButton(true);
        generate.setOnAction(e -> generate());
        key.setEditable(false);
        key.setWrapText(true);
        key.setPrefRowCount(3);
        key.setPromptText("The key appears here");
        Button copy = new Button("Copy");
        copy.disableProperty().bind(key.textProperty().isEmpty());
        copy.setOnAction(e -> copy(key.getText()));
        HBox actions = new HBox(10, generate, copy);

        issued.getColumns().add(column("Licensed to", i -> i.license().name(), 220));
        issued.getColumns().add(column("Type", i -> i.license().type().label(), 80));
        issued.getColumns().add(column("From", i -> DATE.format(i.license().issued()), 100));
        issued.getColumns().add(column("Until", i -> i.license().expires() == null ? "never" : DATE.format(i.license().expires()), 100));
        issued.getColumns().add(column("Issued on", i -> DATE.format(i.on()), 100));
        issued.setPlaceholder(new Label("No keys issued yet"));
        MenuItem copyKey = new MenuItem("Copy key");
        copyKey.setOnAction(e -> {
            SigningKeys.Issued i = issued.getSelectionModel().getSelectedItem();
            if (i != null) copy(i.key());
        });
        issued.setContextMenu(new ContextMenu(copyKey));
        issued.setOnMouseClicked(e -> {
            SigningKeys.Issued i = issued.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && i != null) key.setText(i.key());
        });
        issued.getItems().setAll(SigningKeys.issued());
        VBox.setVgrow(issued, Priority.ALWAYS);

        Label history = new Label("Issued keys (double-click to show a key again, right-click to copy)");
        history.getStyleClass().add("hint-small");
        VBox root = new VBox(12, form, actions, key, history, issued);
        root.setPadding(new Insets(16));

        Scene scene = new Scene(root, 760, 560);
        MainWindow.applyTheme(scene);
        stage.setTitle("DBAdmin License Generator");
        stage.setScene(scene);
        stage.show();

        if (!SigningKeys.exist()) {
            error("No signing key", "There is no signing key in " + SigningKeys.DIR
                    + ".\nCreate it once with: LicenseGenerator --init");
            generate.setDisable(true);
        }
    }

    private void updateEnd() {
        License.Type t = selectedType();
        LocalDate s = start.getValue() == null ? LocalDate.now() : start.getValue();
        end.setValue(t.lastDay(s));
        end.setDisable(t == License.Type.LIFETIME);
    }

    private License.Type selectedType() {
        return (License.Type) type.getSelectedToggle().getUserData();
    }

    private void generate() {
        String who = name.getText().strip();
        if (who.isEmpty()) {
            error("Name missing", "Enter who the license is for.");
            return;
        }
        License.Type t = selectedType();
        LocalDate from = start.getValue() == null ? LocalDate.now() : start.getValue();
        LocalDate until = t == License.Type.LIFETIME ? null : end.getValue();
        if (until != null && until.isBefore(from)) {
            error("Dates", "The end date is before the start date.");
            return;
        }
        try {
            key.setText(SigningKeys.issue(new License(t, who, from, until)));
            issued.getItems().setAll(SigningKeys.issued());
        } catch (Exception e) {
            error("Could not generate the key", String.valueOf(e.getMessage()));
        }
    }

    private static void copy(String text) {
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
    }

    private static TableColumn<SigningKeys.Issued, String> column(String title, Function<SigningKeys.Issued, String> value, double width) {
        TableColumn<SigningKeys.Issued, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    private static void error(String header, String text) {
        Alert a = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
        a.setHeaderText(header);
        a.showAndWait();
    }
}
