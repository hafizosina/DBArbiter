package com.dbadmin;

import com.dbadmin.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class DbAdminApp extends Application {

    private MainWindow mainWindow;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        mainWindow = new MainWindow(stage);
        Scene scene = new Scene(mainWindow.getRoot(), 1200, 760);
        scene.getStylesheets().add(getClass().getResource("app.css").toExternalForm());
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(getClass().getResource("app-icon/icon-" + size + ".png").toExternalForm()));
        }
        stage.setTitle("DBAdmin");
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        if (mainWindow != null) {
            mainWindow.shutdown();
        }
    }
}
