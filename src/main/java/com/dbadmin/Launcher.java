package com.dbadmin;

/**
 * Plain entry point that does not extend Application, so the fat jar
 * can start JavaFX from the classpath.
 */
public class Launcher {
    public static void main(String[] args) {
        DbAdminApp.main(args);
    }
}
