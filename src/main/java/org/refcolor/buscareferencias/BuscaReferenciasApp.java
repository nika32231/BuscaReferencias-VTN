package org.refcolor.buscareferencias;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.refcolor.buscareferencias.utils.DatabaseManager;
import java.io.IOException;

public class BuscaReferenciasApp extends Application {
    private static BuscaReferenciasApp instance;

    public BuscaReferenciasApp() {
        instance = this;
    }

    public static BuscaReferenciasApp getInstance() {
        return instance;
    }

    @Override
    public void start(Stage stage) throws IOException {
        // Inicializar base de datos
        DatabaseManager.initDatabase();

        FXMLLoader fxmlLoader = new FXMLLoader(BuscaReferenciasApp.class.getResource("main-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1000, 700);
        stage.setTitle("Buscador de Referencias por Colores");
        stage.setScene(scene);
        stage.show();
    }
}
