package com.dbadmin;

import com.dbadmin.service.AppLog;
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
        AppLog.init();
        AppLog.get(DbAdminApp.class).info("DBArbiter started (Java " + Runtime.version() + ")");
        mainWindow = new MainWindow(stage);
        Scene scene = new Scene(mainWindow.getRoot(), 1200, 760);
        MainWindow.applyTheme(scene);
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(getClass().getResource("app-icon/icon-" + size + ".png").toExternalForm()));
        }
        stage.setTitle("DBArbiter");
        stage.setScene(scene);
        // 1200x760 is the size used when the user un-maximizes the window
        stage.setMaximized(true);
        stage.show();
        mainWindow.checkForUpdates(getHostServices());
    }

    @Override
    public void stop() {
        if (mainWindow != null) {
            mainWindow.shutdown();
        }
    }
}
