package org.refcolor.buscareferencias;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.refcolor.buscareferencias.controller.DrawingController;
import org.refcolor.buscareferencias.controller.SplashController;
import org.refcolor.buscareferencias.core.FallbackUi;
import org.refcolor.buscareferencias.core.FeatureFlags;
import org.refcolor.buscareferencias.database.DatabaseManager;
import org.refcolor.buscareferencias.settings.AppSettings;
import org.refcolor.buscareferencias.client.PythonImageSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        FXMLLoader fxmlLoader = new FXMLLoader(BuscaReferenciasApp.class.getResource("splash-view.fxml"));

        try {
            if (FeatureFlags.forceFallbackUi()) {
                throw new IllegalStateException("ui.forceFallback=true (forzado)");
            }
            logger.info("[STARTUP] Cargando pantalla inicial (splash)...");
            Parent root = fxmlLoader.load();
            scene = new Scene(root, 480, 640);
        } catch (Exception e) {
            logger.error("[STARTUP] Error cargando splash, activando fallback", e);
            scene = FallbackUi.createFallbackScene("Error cargando pantalla inicial", e);
        }

        logger.info("[STARTUP] UI preparada en {} ms", Duration.between(t0, Instant.now()).toMillis());

        // ── HiDPI: inyectar CSS escalado si la pantalla supera 1920×1080 ─────
        {
            Rectangle2D sb = Screen.getPrimary().getBounds();
            double uiSc = Math.max(1.0, Math.min(2.5,
                Math.min(sb.getWidth() / 1920.0, sb.getHeight() / 1080.0)));
            if (uiSc > 1.05) {
                try {
                    Path tmpCss;
                    if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                        tmpCss = Files.createTempFile("refcolor-dpi-", ".css");
                        tmpCss.toFile().setReadable(true, true);
                        tmpCss.toFile().setWritable(true, true);
                    } else {
                        java.nio.file.attribute.FileAttribute<java.util.Set<java.nio.file.attribute.PosixFilePermission>> attr =
                            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                        tmpCss = Files.createTempFile("refcolor-dpi-", ".css", attr);
                    }
                    tmpCss.toFile().deleteOnExit();
                    Files.writeString(tmpCss, buildScaledCss(uiSc));
                    scene.getStylesheets().add(tmpCss.toUri().toString());
                    logger.info("[STARTUP] HiDPI scale={} CSS aplicado desde {}",
                            String.format("%.2f", uiSc), tmpCss.getFileName());
                } catch (Exception ex) {
                    logger.warn("[STARTUP] HiDPI CSS no aplicado: {}", ex.getMessage());
                }
            }
        }

        // Inyectar Stage en SplashController
        try {
            Object ctrl = fxmlLoader.getController();
            if (ctrl instanceof SplashController splash) {
                splash.setStage(stage);
            } else if (ctrl instanceof DrawingController dc) {
                dc.setHostServices(getHostServices());
            }
        } catch (Exception e) {
            logger.error("[STARTUP] Error configurando controller", e);
        }

        stage.setTitle("Buscador de Referencias por Colores");
        stage.setScene(scene);
        stage.setMinWidth(360);
        stage.setMinHeight(500);

        // Splash: tamaño compacto; se expande al entrar a la app
        stage.setWidth(480);
        stage.setHeight(640);
        stage.centerOnScreen();

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


    // ── HiDPI CSS helpers ─────────────────────────────────────────────────────

    /**
     * Generates a CSS override string that scales key UI measurements
     * proportionally to the given scale factor (1.0 = 1920×1080 baseline).
     */
    private static String buildScaledCss(double s) {
        int f   = r(13 * s); // base font
        int fs  = r(10 * s); // small font (labels, section titles)
        int fs2 = r(12 * s); // status label font
        int fl  = r(11 * s); // progress label / batch-info font
        int bH  = r(40 * s); // toolbar button min-height
        int pH  = r(46 * s); // palette button min-height
        int pV  = r(8  * s); // button vertical padding
        int p1  = r(16 * s); // toolbar button h-padding
        int p2  = r(22 * s); // search button h-padding
        int p3  = r(18 * s); // add-photos h-padding
        int p4  = r(11 * s); // help button h-padding
        int p5  = r(6  * s); // help button v-padding
        int qV  = r(3  * s); // palette v-padding
        int qR  = r(8  * s); // palette right padding
        int qL  = r(5  * s); // palette left padding
        int pb  = r(11 * s); // batch progress bar height
        int pt  = r(20 * s); // total progress bar height
        int hf  = r(16 * s); // help-button emoji font

        return ".root { -fx-font-size: " + f + "px; }\n"
             + ".tool-bar { -fx-padding: " + pV + " " + p1 + " " + pV + " " + p1 + "; }\n"
             + ".tool-bar .button, .tool-bar .toggle-button {\n"
             + "    -fx-padding: " + pV + " " + p1 + " " + pV + " " + p1 + ";\n"
             + "    -fx-font-size: " + f + "px; -fx-min-height: " + bH + ";\n"
             + "}\n"
             + ".search-button-large { -fx-font-size: " + f + "px;"
             + " -fx-padding: " + pV + " " + p2 + " " + pV + " " + p2 + ";"
             + " -fx-min-height: " + bH + "; }\n"
             + ".add-photos-button { -fx-font-size: " + f + "px;"
             + " -fx-padding: " + pV + " " + p3 + " " + pV + " " + p3 + ";"
             + " -fx-min-height: " + bH + "; }\n"
             + ".help-button { -fx-font-size: " + hf + "px;"
             + " -fx-padding: " + p5 + " " + p4 + " " + p5 + " " + p4 + ";"
             + " -fx-min-height: " + bH + "; }\n"
             + ".section-title { -fx-font-size: " + fs + "px; }\n"
             + ".palette-pill-button { -fx-font-size: " + f + "px;"
             + " -fx-min-height: " + pH + ";"
             + " -fx-padding: " + qV + " " + qR + " " + qV + " " + qL + "; }\n"
             + ".gallery-rank { -fx-font-size: " + fs + "px; }\n"
             + ".status-prefix { -fx-font-size: " + fs + "px; }\n"
             + "#statusLabel { -fx-font-size: " + fs2 + "px; }\n"
             + ".progress-label { -fx-font-size: " + fl + "px; }\n"
             + ".progress-pct-small { -fx-font-size: " + r(9 * s) + "px; }\n"
             + ".progress-row-label { -fx-font-size: " + fs + "px; }\n"
             + ".batch-info-label { -fx-font-size: " + fl + "px; }\n"
             + ".progress-bar { -fx-min-height: " + pt + "; -fx-pref-height: " + pt + "; }\n"
             + ".batch-progress-bar { -fx-min-height: " + pb + "; -fx-pref-height: " + pb + "; }\n";
    }

    private static int r(double v) { return (int) Math.round(v); }

}
