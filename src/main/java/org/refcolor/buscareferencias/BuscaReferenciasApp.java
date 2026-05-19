package org.refcolor.buscareferencias;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.refcolor.buscareferencias.controller.DrawingController;
import org.refcolor.buscareferencias.core.FallbackUi;
import org.refcolor.buscareferencias.core.FeatureFlags;
import org.refcolor.buscareferencias.database.DatabaseManager;
import org.refcolor.buscareferencias.client.PythonImageSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

public class BuscaReferenciasApp extends Application {
    private static final Logger logger = LoggerFactory.getLogger(BuscaReferenciasApp.class);

    @Override
    public void start(Stage stage) throws IOException {
        Instant t0 = Instant.now();
        boolean safeMode = FeatureFlags.isSafeMode();
        logger.info("[STARTUP] start() begin  safeMode={} thread={}", safeMode, Thread.currentThread().getName());

        Scene scene;
        FXMLLoader fxmlLoader = new FXMLLoader(BuscaReferenciasApp.class.getResource("main-view.fxml"));

        try {
            if (FeatureFlags.forceFallbackUi()) {
                throw new IllegalStateException("ui.forceFallback=true (forzado)");
            }
            logger.info("[STARTUP] Cargando FXML...");
            Parent root = fxmlLoader.load();
            scene = new Scene(root, 1400, 900);
        } catch (Exception e) {
            logger.error("[STARTUP] Error cargando UI principal, activando fallback", e);
            scene = FallbackUi.createFallbackScene("Error cargando UI principal (FXML)", e);
        }

        logger.info("[STARTUP] UI preparada en {} ms", Duration.between(t0, Instant.now()).toMillis());

        // Inyectar HostServices (si el controller existe)
        try {
            DrawingController controller = fxmlLoader.getController();
            if (controller != null) {
                controller.setHostServices(getHostServices());
            }
        } catch (Exception e) {
            logger.error("[STARTUP] Error configurando controller", e);
        }

        stage.setTitle("Buscador de Referencias por Colores");
        stage.setScene(scene);
        stage.setMinWidth(360);
        stage.setMinHeight(620);

        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        if (visualBounds.getWidth() >= 1280) {
            stage.setMaximized(true);
        } else {
            stage.setWidth(Math.min(1100, visualBounds.getWidth()));
            stage.setHeight(Math.min(850, visualBounds.getHeight()));
        }

        logger.info("[STARTUP] stage.show()...");
        stage.show();
        logger.info("[STARTUP] stage.show() complete en {} ms", Duration.between(t0, Instant.now()).toMillis());

        // Tareas post-arranque: nunca deben impedir que la UI abra
        if (!safeMode) {
            if (FeatureFlags.enableDbInitOnStartup()) {
                new Thread(() -> {
                    try {
                        logger.info("[STARTUP] Inicializando DB...");
                        DatabaseManager.initDatabase();
                        logger.info("[STARTUP] DB inicializada.");
                    } catch (Exception e) {
                        logger.error("[STARTUP] Error inicializando DB", e);
                    }
                }, "db-init-thread").start();
            } else {
                logger.info("[STARTUP] DB init en arranque desactivado por flag features.dbInitOnStartup=false");
            }

            if (FeatureFlags.enableDepsCheckOnStartup()) {
                checkDependenciesAsync();
            } else {
                logger.info("[STARTUP] Deps check en arranque desactivado por flag features.depsCheckOnStartup=false");
            }
        } else {
            logger.warn("[STARTUP] SAFE MODE activo: se omiten tareas pesadas y módulos experimentales.");
        }

        logger.info("[STARTUP] start() end (UI visible) en {} ms", Duration.between(t0, Instant.now()).toMillis());

    }

    private void checkDependenciesAsync() {
        Thread t = new Thread(() -> {
            try {
                PythonImageSearchClient.CommandResult pythonVersion = PythonImageSearchClient.probePythonVersion();
                logger.info("[DEPS] python --version => started={} exitCode={} stdout='{}' stderr='{}' error='{}'",
                        pythonVersion.started(),
                        pythonVersion.exitCode(),
                        pythonVersion.stdout(),
                        pythonVersion.stderr(),
                        pythonVersion.error());

                if (pythonVersion.succeeded()) {
                    PythonImageSearchClient.CommandResult smoke = PythonImageSearchClient.runPythonCommand(
                            java.util.List.of("-c", "import mediapipe; import cv2; print('OK')"),
                            null,
                            10
                    );
                    logger.info("[DEPS] smoke import mediapipe/cv2 => started={} exitCode={} stdout='{}' stderr='{}' error='{}'",
                            smoke.started(),
                            smoke.exitCode(),
                            smoke.stdout(),
                            smoke.stderr(),
                            smoke.error());

                    if (smoke.succeeded()) {
                        logger.info("[DEPS] MediaPipe y OpenCV detectados correctamente.");
                    } else {
                        logger.warn("[DEPS] Python encontrado, pero el smoke test de imports falló.");
                    }
                } else {
                    logger.warn("[DEPS] Python no disponible o no usable; se omite el smoke test de imports.");
                }
            } catch (Exception e) {
                logger.warn("[DEPS] Dependencias Python no disponibles o timeout: {}", e.toString());
            }
        }, "deps-check-thread");
        t.setDaemon(true);
        t.start();
    }


    private void showFatalError(String title, Exception e) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(title);
            alert.setContentText(e.getMessage());

            StringBuilder sb = new StringBuilder();
            for (StackTraceElement el : e.getStackTrace()) {
                sb.append(el).append("\n");
            }

            TextArea textArea = new TextArea(sb.toString());
            textArea.setEditable(false);
            textArea.setWrapText(false);

            VBox.setVgrow(textArea, Priority.ALWAYS);
            VBox box = new VBox(textArea);
            box.setMaxWidth(Double.MAX_VALUE);

            alert.getDialogPane().setExpandableContent(box);
            alert.getDialogPane().setExpanded(true);
            alert.showAndWait();
        });
    }
}
