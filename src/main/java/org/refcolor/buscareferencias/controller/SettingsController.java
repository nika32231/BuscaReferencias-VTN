package org.refcolor.buscareferencias.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.refcolor.buscareferencias.BuscaReferenciasApp;
import org.refcolor.buscareferencias.i18n.I18n;
import org.refcolor.buscareferencias.settings.AppSettings;
import org.refcolor.buscareferencias.utils.ProjectPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Controlador de la pantalla de Ajustes.
 * Gestiona: fotos por ronda, biblioteca, tema oscuro/claro, sonido.
 */
public class SettingsController {

    private static final Logger logger = LoggerFactory.getLogger(SettingsController.class);

    @FXML private Label            lblTitle;
    @FXML private Label            lblSectionSearch;
    @FXML private Label            lblSectionLibrary;
    @FXML private Label            lblSectionAppearance;
    @FXML private Label            lblSectionNotif;
    @FXML private Label            lblPhotosPerRound;
    @FXML private Label            lblMinScore;
    @FXML private Label            lblDarkTheme;
    @FXML private Label            lblSound;
    @FXML private Button           btnAddPhotos;
    @FXML private Button           btnSave;
    @FXML private Spinner<Integer> spnPhotosPerRound;
    @FXML private Spinner<Integer> spnMinScore;
    @FXML private Label            lblPhotoCount;
    @FXML private Label            lblLibraryPath;
    @FXML private ToggleButton     tglDarkTheme;
    @FXML private ToggleButton     tglSound;
    @FXML private Label            lblSaved;

    private Stage    stage;
    private Runnable onBack;

    @FXML
    public void initialize() {
        // Cargar ajustes actuales en los controles
        AppSettings.load();

        spnPhotosPerRound.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 500, AppSettings.getPhotosPerRound()));
        spnMinScore.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 90, 40));

        updateThemeToggle(AppSettings.isDarkTheme());
        updateSoundToggle(AppSettings.isSoundEnabled());

        applyI18n();
        refreshPhotoCount();
    }

    public void setStage(Stage stage)   { this.stage = stage; }
    public void setOnBack(Runnable r)   { this.onBack = r; }

    // ── Handlers ─────────────────────────────────────────────────────────────

    @FXML
    private void handleBack() {
        if (onBack != null) Platform.runLater(onBack);
        else if (stage != null) stage.getScene().setRoot(loadMain());
    }

    @FXML
    private void handleAddPhotos() {
        if (stage == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Añadir fotos a la biblioteca");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Imágenes", "*.jpg","*.jpeg","*.png","*.webp","*.bmp"));
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;

        new Thread(() -> {
            int copied = 0;
            try {
                Path thumbDir = ProjectPaths.getThumbnailsDirectory();
                Files.createDirectories(thumbDir);
                for (File f : files) {
                    Path dest = thumbDir.resolve(f.getName());
                    Files.copy(f.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            } catch (IOException e) {
                logger.error("[SETTINGS] Error copiando fotos", e);
            }
            final int total = copied;
            Platform.runLater(() -> {
                refreshPhotoCount();
                boolean en = I18n.isEnglish();
                showSaved(en ? "✓ " + total + " photo(s) added to library"
                             : "✓ " + total + " foto(s) añadida(s) a la biblioteca");
            });
        }, "add-photos-thread").start();
    }

    @FXML
    private void handleThemeToggle() {
        boolean dark = tglDarkTheme.isSelected();
        updateThemeToggle(dark);
        AppSettings.setDarkTheme(dark);
        applyThemeToScene();
    }

    @FXML
    private void handleSoundToggle() {
        boolean enabled = tglSound.isSelected();
        updateSoundToggle(enabled);
        AppSettings.setSoundEnabled(enabled);
    }

    @FXML
    private void handleSave() {
        // Recoger valores de los spinners
        try {
            spnPhotosPerRound.commitValue();
            spnMinScore.commitValue();
        } catch (Exception ignored) {}

        AppSettings.setPhotosPerRound(spnPhotosPerRound.getValue());
        // min score: guardamos en la BD directamente
        org.refcolor.buscareferencias.database.DatabaseManager
            .setSetting("search_min_score", String.valueOf(spnMinScore.getValue()));

        applyThemeToScene();
        showSaved(I18n.isEnglish() ? "✓ Settings saved" : "✓ Ajustes guardados");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void refreshPhotoCount() {
        try {
            Path dir = ProjectPaths.getThumbnailsDirectory();
            long count = Files.exists(dir)
                ? Files.list(dir).filter(Files::isRegularFile).count() : 0L;
            lblPhotoCount.setText(count + " foto(s) en la biblioteca");
            lblLibraryPath.setText(dir.toAbsolutePath().toString());
        } catch (Exception e) {
            lblPhotoCount.setText("No se pudo leer la biblioteca");
        }
    }

    private void updateThemeToggle(boolean dark) {
        tglDarkTheme.setSelected(dark);
        tglDarkTheme.setText(dark ? "ON" : "OFF");
        tglDarkTheme.setStyle(dark
            ? "-fx-background-color: #845460; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 14; -fx-background-radius: 20;"
            : "-fx-background-color: #1C3A50; -fx-text-fill: #6B8FA3; -fx-font-size: 12px; -fx-padding: 6 14; -fx-background-radius: 20;");
    }

    private void updateSoundToggle(boolean enabled) {
        tglSound.setSelected(enabled);
        tglSound.setText(enabled ? "ON" : "OFF");
        tglSound.setStyle(enabled
            ? "-fx-background-color: #5A8A7C; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 14; -fx-background-radius: 20;"
            : "-fx-background-color: #1C3A50; -fx-text-fill: #6B8FA3; -fx-font-size: 12px; -fx-padding: 6 14; -fx-background-radius: 20;");
    }

    private void applyThemeToScene() {
        // Obtener scene desde el stage o directamente desde cualquier nodo @FXML
        Scene scene = (stage != null) ? stage.getScene()
                    : (spnPhotosPerRound != null ? spnPhotosPerRound.getScene() : null);
        if (scene == null) return;

        var res = BuscaReferenciasApp.class.getResource("style-light.css");
        String lightUrl = (res != null) ? res.toExternalForm() : null;
        if (lightUrl == null) return;

        boolean dark = AppSettings.isDarkTheme();
        if (!dark) {
            // Añadir tema claro si no está ya
            if (!scene.getStylesheets().contains(lightUrl))
                scene.getStylesheets().add(lightUrl);
        } else {
            scene.getStylesheets().remove(lightUrl);
        }

        // También forzar colores en los labels de toggle para que el estado visual sea coherente
        updateThemeToggle(dark);
    }

    private void applyI18n() {
        boolean en = I18n.isEnglish();
        if (lblTitle          != null) lblTitle.setText(en ? "⚙ Settings" : "⚙ Ajustes");
        if (lblSectionSearch  != null) lblSectionSearch.setText(en ? "SEARCH" : "BÚSQUEDA");
        if (lblSectionLibrary != null) lblSectionLibrary.setText(en ? "PHOTO LIBRARY" : "BIBLIOTECA DE FOTOS");
        if (lblSectionAppearance != null) lblSectionAppearance.setText(en ? "APPEARANCE" : "APARIENCIA");
        if (lblSectionNotif   != null) lblSectionNotif.setText(en ? "NOTIFICATIONS" : "NOTIFICACIONES");
        if (lblPhotosPerRound != null) lblPhotosPerRound.setText(en ? "Photos per round" : "Fotos analizadas por ronda");
        if (lblMinScore       != null) lblMinScore.setText(en ? "Min similarity score (%)" : "Score mínimo de similitud (%)");
        if (lblDarkTheme      != null) lblDarkTheme.setText(en ? "Dark theme" : "Tema oscuro");
        if (lblSound          != null) lblSound.setText(en ? "Sound on search complete" : "Sonido al terminar análisis");
        if (btnAddPhotos      != null) btnAddPhotos.setText(en ? "📂  Add photos to library" : "📂  Añadir fotos a la biblioteca");
        if (btnSave           != null) btnSave.setText(en ? "✓  Save changes" : "✓  Guardar cambios");
    }

    private void showSaved(String msg) {
        lblSaved.setText(msg);
        PauseTransition p = new PauseTransition(Duration.seconds(3));
        p.setOnFinished(e -> lblSaved.setText(""));
        p.play();
    }

    private javafx.scene.Parent loadMain() {
        try {
            return new javafx.fxml.FXMLLoader(
                BuscaReferenciasApp.class.getResource("main-view.fxml")).load();
        } catch (Exception e) {
            logger.error("[SETTINGS] Error cargando main-view", e);
            return null;
        }
    }
}
