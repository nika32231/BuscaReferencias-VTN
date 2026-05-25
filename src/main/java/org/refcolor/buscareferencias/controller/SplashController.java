package org.refcolor.buscareferencias.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.refcolor.buscareferencias.BuscaReferenciasApp;
import org.refcolor.buscareferencias.auth.UserManager;
import org.refcolor.buscareferencias.settings.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controlador de la pantalla inicial.
 * Muestra logo + botones de Login, Registrar, Invitado y Ajustes.
 */
public class SplashController {

    private static final Logger logger = LoggerFactory.getLogger(SplashController.class);

    @FXML private Label lblVersion;

    private Stage stage;

    @FXML
    public void initialize() {
        lblVersion.setText("v1.0");
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @FXML
    private void handleLogin() {
        openLogin(false);
    }

    @FXML
    private void handleRegister() {
        openLogin(true);
    }

    @FXML
    private void handleGuest() {
        UserManager.loginAsGuest();
        openMainApp();
    }

    @FXML
    private void handleSettings() {
        openSettings(false); // desde splash no hay "back to app", vuelve al splash
    }

    // ── Navegación ────────────────────────────────────────────────────────────

    private void openLogin(boolean registerMode) {
        try {
            FXMLLoader loader = new FXMLLoader(
                BuscaReferenciasApp.class.getResource("login-view.fxml"));
            Parent root = loader.load();
            LoginController ctrl = loader.getController();
            ctrl.setStage(stage);
            ctrl.setRegisterMode(registerMode);
            ctrl.setOnSuccess(this::openMainApp);
            ctrl.setOnBack(() -> showSplash());

            Scene scene = stage.getScene();
            applyTheme(scene);
            scene.setRoot(root);
        } catch (Exception e) {
            logger.error("[SPLASH] Error abriendo login", e);
        }
    }

    private void openMainApp() {
        try {
            FXMLLoader loader = new FXMLLoader(
                BuscaReferenciasApp.class.getResource("main-view.fxml"));
            Parent root = loader.load();
            DrawingController ctrl = loader.getController();
            if (ctrl != null) {
                ctrl.setHostServices(null);
                ctrl.setOnLogout(this::showSplash);
            }
            Scene scene = stage.getScene();
            applyTheme(scene);
            scene.setRoot(root);

            // Expandir ventana al tamaño completo de la app
            javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
            if (vb.getWidth() >= 1280) {
                stage.setMaximized(true);
            } else {
                stage.setWidth(Math.min(1100, vb.getWidth()));
                stage.setHeight(Math.min(850, vb.getHeight()));
                stage.centerOnScreen();
            }
        } catch (Exception e) {
            logger.error("[SPLASH] Error abriendo app principal", e);
        }
    }

    private void openSettings(boolean fromApp) {
        try {
            FXMLLoader loader = new FXMLLoader(
                BuscaReferenciasApp.class.getResource("settings-view.fxml"));
            Parent root = loader.load();
            SettingsController ctrl = loader.getController();
            ctrl.setStage(stage);
            ctrl.setOnBack(fromApp ? null : this::showSplash);

            Scene scene = stage.getScene();
            applyTheme(scene);
            scene.setRoot(root);
        } catch (Exception e) {
            logger.error("[SPLASH] Error abriendo ajustes", e);
        }
    }

    private void showSplash() {
        try {
            FXMLLoader loader = new FXMLLoader(
                BuscaReferenciasApp.class.getResource("splash-view.fxml"));
            Parent root = loader.load();
            SplashController ctrl = loader.getController();
            ctrl.setStage(stage);
            stage.getScene().setRoot(root);
        } catch (Exception e) {
            logger.error("[SPLASH] Error recargando splash", e);
        }
    }

    private static void applyTheme(Scene scene) {
        String base = BuscaReferenciasApp.class
            .getResource("style.css").toExternalForm();
        if (!scene.getStylesheets().contains(base))
            scene.getStylesheets().add(base);

        String lightUrl = BuscaReferenciasApp.class
            .getResource("style-light.css") != null
            ? BuscaReferenciasApp.class.getResource("style-light.css").toExternalForm()
            : null;

        if (!AppSettings.isDarkTheme() && lightUrl != null) {
            if (!scene.getStylesheets().contains(lightUrl))
                scene.getStylesheets().add(lightUrl);
        } else if (lightUrl != null) {
            scene.getStylesheets().remove(lightUrl);
        }
    }
}
