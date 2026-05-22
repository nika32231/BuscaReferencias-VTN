package org.refcolor.buscareferencias.controller;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import javafx.util.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.refcolor.buscareferencias.database.DatabaseManager;
import org.refcolor.buscareferencias.i18n.I18n;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.service.SearchService;
import org.refcolor.buscareferencias.tutorial.TutorialOverlay;
import org.refcolor.buscareferencias.utils.DrawingProcessor;
import org.refcolor.buscareferencias.utils.ProjectPaths;
import org.refcolor.buscareferencias.utils.SearchTermGenerator;

public class DrawingController {

    private static final Logger logger = LoggerFactory.getLogger(DrawingController.class);

    /** Target visual stroke width in screen pixels. Compensated per scale. */
    private static final double LINE_WIDTH = 10.0;
    /** Visual eraser half-size in screen pixels. */
    private static final double ERASER_RADIUS = 18.0;
    private static final Color CANVAS_FRAME_COLOR = Color.web("#BDC7C9");
    private static final int UNDO_STACK_LIMIT = 20;

    @FXML private Canvas canvas;
    @FXML private BorderPane rootPane;
    @FXML private VBox paletteContainer;
    @FXML private VBox sidePalette;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private ProgressBar batchProgressBar;
    @FXML private VBox progressWrapper;
    @FXML private Label progressLabel;
    @FXML private Label batchPctLabel;
    @FXML private Label batchNumLabel;
    @FXML private ToggleButton btnDraw;
    @FXML private ToggleButton btnErase;
    @FXML private Button btnSearch;
    @FXML private Button btnAddPhotos;
    @FXML private Button btnHelp;
    @FXML private Button btnLang;
    @FXML private Button btnClear;
    @FXML private Button btnUndo;
    @FXML private Button btnRedo;
    @FXML private ToolBar toolBar;
    @FXML private Label lblPalette;
    @FXML private Label lblGallery;
    @FXML private Label lblStatusPrefix;
    @FXML private Label lblBatch;
    @FXML private Label lblTotal;
    @FXML private StackPane canvasContainer;
    @FXML private StackPane canvasRoot;
    @FXML private SplitPane canvasGallerySplitPane;
    @FXML private FlowPane galleryPane;
    @FXML private VBox galleryEmptyState;
    @FXML private Label galleryEmptyLabel;

    private GraphicsContext gc;
    private AnatomyPart currentPart = AnatomyPart.HEAD;
    private double lastX, lastY;

    private final Deque<WritableImage> undoStack = new ArrayDeque<>();
    private final Deque<WritableImage> redoStack = new ArrayDeque<>();

    private ToggleGroup toolGroup;
    private PoseData lastAnalyzedPose;
    private HostServices hostServices;
    private int currentSearchId = -1;
    private TutorialOverlay tutorialOverlay;

    private boolean resizingCanvas = false;
    private double resizeStartX, resizeStartY, resizeStartW, resizeStartH;

    /** Current canvas display scale (updated by updateCanvasScaleToViewport). */
    private double currentScale = 1.0;
    /** Effective canvas-pixel line width (= LINE_WIDTH / currentScale, clamped). */
    private double effectiveLineWidth = LINE_WIDTH;

    private static final double CANVAS_INITIAL_W = 900;
    private static final double CANVAS_INITIAL_H = 600;
    private static final double CANVAS_MIN_W = 500;
    private static final double CANVAS_MIN_H = 350;
    private static final double CANVAS_MAX_W = 1600;
    private static final double CANVAS_MAX_H = 1200;
    private static final double BREAKPOINT_LARGE   = 1680;
    private static final double BREAKPOINT_COMPACT = 1180;
    private static final double BREAKPOINT_PHONE   = 820;
    private static final double CANVAS_VIEW_PADDING = 12;

    private boolean responsiveListenersAttached = false;
    private double  currentGalleryImageSize = 150;

    // ── Interactividad ────────────────────────────────────────────────────────
    /** Canvas transparente sobre el canvas principal para mostrar el cursor del borrador. */
    private Canvas  cursorOverlay;
    /** Etiqueta de pista mostrada cuando el lienzo está vacío. */
    private Label   canvasHintLabel;
    /** true una vez que el usuario ha trazado algún trazo (no borrador). */
    private boolean canvasHasContent = false;

    public void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    @FXML
    public void initialize() {
        Instant t0 = Instant.now();
        logger.info("[UI] DrawingController.initialize() begin thread={}", Thread.currentThread().getName());

        gc = canvas.getGraphicsContext2D();
        gc.setLineWidth(LINE_WIDTH);
        gc.setStroke(Color.web(currentPart.getHexColor()));

        canvas.setWidth(CANVAS_INITIAL_W);
        canvas.setHeight(CANVAS_INITIAL_H);
        clearToWhite();

        toolGroup = new ToggleGroup();
        btnDraw.setToggleGroup(toolGroup);
        btnErase.setToggleGroup(toolGroup);
        btnDraw.setSelected(true);

        setupPalette();
        saveCurrentState();
        setupManualCanvasResize();
        setupTouchInput();
        setupCanvasAutoFit();
        attachResponsiveListeners();
        setupGalleryResponsive();
        setupCursorOverlay();
        setupCanvasHint();
        updateUndoRedoButtons();
        updateGalleryEmptyState(true);
        setupTutorial();

        // i18n: apply initial locale texts and register listener for language changes
        I18n.addChangeListener(() -> Platform.runLater(this::applyI18n));
        applyI18n();

        logger.info("[UI] DrawingController.initialize() end en {} ms", java.time.Duration.between(t0, Instant.now()).toMillis());
    }

    private void setupManualCanvasResize() {
        if (canvasRoot == null) return;

        canvasRoot.setOnMouseMoved(e -> {
            boolean nearCorner = (e.getX() > canvasRoot.getWidth() - 24) && (e.getY() > canvasRoot.getHeight() - 24);
            canvasRoot.setCursor(nearCorner ? Cursor.SE_RESIZE : Cursor.DEFAULT);
        });

        canvasRoot.setOnMousePressed(e -> {
            boolean nearCorner = (e.getX() > canvasRoot.getWidth() - 24) && (e.getY() > canvasRoot.getHeight() - 24);
            if (!nearCorner) return;
            resizingCanvas = true;
            resizeStartX = e.getScreenX();
            resizeStartY = e.getScreenY();
            resizeStartW = canvas.getWidth();
            resizeStartH = canvas.getHeight();
            e.consume();
        });

        canvasRoot.setOnMouseDragged(e -> {
            if (!resizingCanvas) return;
            double dx = e.getScreenX() - resizeStartX;
            double dy = e.getScreenY() - resizeStartY;
            resizeCanvasKeepingContent(
                    (int) clamp(resizeStartW + dx, CANVAS_MIN_W, CANVAS_MAX_W),
                    (int) clamp(resizeStartH + dy, CANVAS_MIN_H, CANVAS_MAX_H));
            e.consume();
        });

        canvasRoot.setOnMouseReleased(e -> {
            if (!resizingCanvas) return;
            resizingCanvas = false;
            saveCurrentState();
            if (statusLabel != null) {
                statusLabel.setText(I18n.fmt("status.canvasSize", (int) canvas.getWidth(), (int) canvas.getHeight()));
            }
            e.consume();
        });
    }

    private void resizeCanvasKeepingContent(int newW, int newH) {
        if ((int) canvas.getWidth() == newW && (int) canvas.getHeight() == newH) return;
        WritableImage snapshot = canvas.snapshot(null, null);
        canvas.setWidth(newW);
        canvas.setHeight(newH);
        clearToWhite();
        // Draw at original position — no stretching, so strokes stay crisp.
        // Enlarging the canvas just adds white space; shrinking clips the edge.
        gc.drawImage(snapshot, 0, 0);
        drawCanvasFrame();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void setupTouchInput() {
        if (canvas == null) return;
        canvas.setOnTouchPressed(this::handleTouchPressed);
        canvas.setOnTouchMoved(this::handleTouchDragged);
        canvas.setOnTouchReleased(this::handleTouchReleased);
    }

    private void attachResponsiveListeners() {
        if (canvas == null || responsiveListenersAttached) return;
        canvas.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            applyResponsiveMode(newScene.getWidth(), newScene.getHeight());
            newScene.widthProperty().addListener((o, oldW, newW) -> applyResponsiveMode(newW.doubleValue(), newScene.getHeight()));
            newScene.heightProperty().addListener((o, oldH, newH) -> applyResponsiveMode(newScene.getWidth(), newH.doubleValue()));
        });
        responsiveListenersAttached = true;
    }

    private void applyResponsiveMode(double sceneWidth, double sceneHeight) {
        if (rootPane == null) return;

        boolean large   = sceneWidth >= BREAKPOINT_LARGE;
        boolean compact = sceneWidth < BREAKPOINT_COMPACT;
        boolean phone   = sceneWidth < BREAKPOINT_PHONE;
        boolean touchMode = phone || sceneHeight < 620;

        rootPane.getStyleClass().removeAll("large-mode", "compact-mode", "phone-mode", "touch-mode");
        if (large)     rootPane.getStyleClass().add("large-mode");
        if (compact)   rootPane.getStyleClass().add("compact-mode");
        if (phone)     rootPane.getStyleClass().add("phone-mode");
        if (touchMode) rootPane.getStyleClass().add("touch-mode");

        if (sidePalette != null) {
            if (phone) {
                sidePalette.setPrefWidth(144);
                sidePalette.setMinWidth(120);
            } else if (compact) {
                sidePalette.setPrefWidth(178);
                sidePalette.setMinWidth(150);
            } else if (large) {
                sidePalette.setPrefWidth(232);
                sidePalette.setMinWidth(210);
            } else {
                sidePalette.setPrefWidth(210);
                sidePalette.setMinWidth(190);
            }
        }

        if (canvasGallerySplitPane != null) {
            double divider = phone ? 0.48 : (sceneHeight >= 1000 ? 0.65 : 0.60);
            canvasGallerySplitPane.setDividerPositions(divider);
        }
    }

    private void clearToWhite() {
        gc.setFill(Color.web("#FAF8F6")); // crema cálida en lugar de blanco puro
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        drawCanvasFrame();
    }

    private void drawCanvasFrame() {
        if (gc == null || canvas == null) return;
        double w = Math.max(1, canvas.getWidth());
        double h = Math.max(1, canvas.getHeight());
        gc.save();
        gc.setStroke(CANVAS_FRAME_COLOR);
        gc.setLineWidth(1.5);
        gc.strokeRect(0.75, 0.75, Math.max(0, w - 1.5), Math.max(0, h - 1.5));
        gc.restore();
    }

    private void setupCanvasAutoFit() {
        if (canvasRoot == null || canvasContainer == null || canvas == null) return;
        // Pin the container to the canvas pixel size so the parent StackPane
        // cannot resize it. Without this, the parent shrinks the container and
        // the subsequent Scale transform makes the canvas appear tiny.
        syncContainerSize();
        canvasRoot.widthProperty().addListener((obs, oldV, newV) -> updateCanvasScaleToViewport());
        canvasRoot.heightProperty().addListener((obs, oldV, newV) -> updateCanvasScaleToViewport());
        canvas.widthProperty().addListener((obs, oldV, newV) -> { syncContainerSize(); updateCanvasScaleToViewport(); });
        canvas.heightProperty().addListener((obs, oldV, newV) -> { syncContainerSize(); updateCanvasScaleToViewport(); });
        updateCanvasScaleToViewport();
    }

    /**
     * Locks canvasContainer to exactly the canvas pixel dimensions.
     * This prevents the parent StackPane from resizing the container,
     * which would break the scale-to-fit calculation.
     */
    private void syncContainerSize() {
        if (canvasContainer == null || canvas == null) return;
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        canvasContainer.setPrefWidth(w);
        canvasContainer.setPrefHeight(h);
        canvasContainer.setMinWidth(w);
        canvasContainer.setMinHeight(h);
        canvasContainer.setMaxWidth(w);
        canvasContainer.setMaxHeight(h);
    }

    private void updateCanvasScaleToViewport() {
        if (canvasRoot == null || canvasContainer == null || canvas == null) return;
        double availableW = Math.max(120, canvasRoot.getWidth()  - CANVAS_VIEW_PADDING);
        double availableH = Math.max(120, canvasRoot.getHeight() - CANVAS_VIEW_PADDING);
        double scale = Math.min(availableW / Math.max(1, canvas.getWidth()),
                                availableH / Math.max(1, canvas.getHeight()));
        scale = clamp(scale, 0.35, 2.5);
        currentScale = scale;
        canvasContainer.setScaleX(scale);
        canvasContainer.setScaleY(scale);

        // Keep visual stroke thickness constant regardless of display scale.
        // Clamp so lines don't become excessively thick at very small scales
        // or unusably thin at very large ones.
        effectiveLineWidth = clamp(LINE_WIDTH / scale, LINE_WIDTH / 2.0, LINE_WIDTH * 2.5);
        if (gc != null) gc.setLineWidth(effectiveLineWidth);
    }

    private void setupGalleryResponsive() {
        if (galleryPane == null) return;
        galleryPane.widthProperty().addListener((obs, oldV, newV) -> applyGalleryResponsiveLayout());
        if (canvas != null) {
            canvas.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene == null) return;
                applyGalleryResponsiveLayout();
                newScene.widthProperty().addListener((o, oldW, newW) -> applyGalleryResponsiveLayout());
            });
        }
    }

    private void applyGalleryResponsiveLayout() {
        if (galleryPane == null) return;
        double sceneWidth = rootPane != null && rootPane.getScene() != null ? rootPane.getScene().getWidth() : 1400;
        double available = galleryPane.getWidth();
        if (available <= 0) available = sceneWidth < BREAKPOINT_PHONE ? 320 : 760;

        double target = sceneWidth < BREAKPOINT_PHONE ? 120
                      : sceneWidth < BREAKPOINT_COMPACT ? 136
                      : sceneWidth >= BREAKPOINT_LARGE  ? 180
                      : 150;
        int cols = Math.max(2, (int) Math.floor((available + 12) / (target + 12)));
        currentGalleryImageSize = clamp((available - ((cols - 1) * 12.0)) / cols, 104, 170);
        galleryPane.setPrefWrapLength(available);
        refreshGalleryCardsSize();
    }

    private void refreshGalleryCardsSize() {
        if (galleryPane == null) return;
        for (Node n : galleryPane.getChildren()) {
            if (!(n instanceof VBox card)) continue;
            card.setPrefWidth(currentGalleryImageSize + 10);
            card.setPrefHeight(currentGalleryImageSize + 56);
            for (Node child : card.getChildren()) {
                if (child instanceof ImageView iv) {
                    iv.setFitWidth(currentGalleryImageSize);
                    iv.setFitHeight(currentGalleryImageSize);
                }
            }
        }
    }

    private void saveCurrentState() {
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        undoStack.push(canvas.snapshot(params, null));
        if (undoStack.size() > UNDO_STACK_LIMIT) undoStack.removeLast();
        updateUndoRedoButtons();
    }

    private void setupPalette() {
        AnatomyPart selected = currentPart != null ? currentPart : AnatomyPart.HEAD;
        ToggleGroup paletteGroup = new ToggleGroup();

        for (AnatomyPart part : AnatomyPart.values()) {
            if (!part.isPaletteItem()) continue;

            String partName = I18n.t("anatomy." + part.name());

            ToggleButton colorBtn = new ToggleButton(partName);
            colorBtn.setToggleGroup(paletteGroup);
            colorBtn.getStyleClass().add("palette-pill-button");
            colorBtn.setUserData(part);
            colorBtn.setMaxWidth(Double.MAX_VALUE);
            colorBtn.setTooltip(new Tooltip(I18n.fmt("palette.tooltip", partName)));

            // Estilo inicial
            colorBtn.setStyle(buildPillStyle(part, 0));

            // Hover: cambia estilo inline igual que la barra de herramientas
            colorBtn.hoverProperty().addListener((obs, was, now) -> {
                if (!colorBtn.isSelected()) {
                    colorBtn.setStyle(buildPillStyle(part, now ? 1 : 0));
                }
            });

            colorBtn.selectedProperty().addListener((obs, was, now) -> {
                colorBtn.setStyle(buildPillStyle(part, now ? 2 : 0));
                colorBtn.setScaleX(1.0);
                colorBtn.setScaleY(1.0);
                if (now) {
                    ScaleTransition bounce = new ScaleTransition(Duration.millis(130), colorBtn);
                    bounce.setFromX(0.94); bounce.setFromY(0.94);
                    bounce.setToX(1.0);   bounce.setToY(1.0);
                    bounce.setInterpolator(Interpolator.EASE_OUT);
                    bounce.play();
                }
            });

            colorBtn.setOnAction(e -> {
                currentPart = part;
                btnDraw.setSelected(true);
                statusLabel.setText(I18n.fmt("status.drawing", I18n.t("anatomy." + part.name())));
                gc.setStroke(Color.web(currentPart.getHexColor()));
            });

            if (part == selected) colorBtn.setSelected(true);
            paletteContainer.getChildren().add(colorBtn);
        }
    }

    /**
     * Genera el inline style para un botón-pastilla de paleta.
     *
     * El fondo se obtiene mezclando el color de cada parte con el acero oscuro
     * del toolbar (#243D52 / #1C3A50) en lugar de negro puro.
     * Resultado: mismo nivel de oscuridad y saturación que los botones del toolbar
     * pero con el matiz de cada parte claramente visible en borde y texto.
     *
     * state 0 = normal   → fondo acero tintado, borde y texto del color de la parte
     * state 1 = hover    → fondo ligeramente más claro (más hacia el color propio)
     * state 2 = selected → color completo brillante con glow, igual que toolbar:selected
     */
    private static String buildPillStyle(AnatomyPart part, int state) {
        Color base  = Color.web(part.getHexColor());
        // Color "acero oscuro" del toolbar: mezclarlo con el color de la parte
        // desatura y oscurece a la vez, acercando el botón al look del toolbar
        Color steel     = Color.web("#243D52");
        Color steelDark = Color.web("#1C3A50");

        // Normal: 25% del color propio + 75% del acero del toolbar
        String dark1  = toHex(base.interpolate(steel,     0.75));
        String dark2  = toHex(base.interpolate(steelDark, 0.80));
        // Hover: 40% del color propio + 60% del acero → más vivo
        String hover1 = toHex(base.interpolate(steel,     0.58));
        String hover2 = toHex(base.interpolate(steelDark, 0.65));

        // Selected: gradiente del propio color (como toolbar:selected pero con el color de la parte)
        String sel1   = toHex(base.interpolate(Color.WHITE, 0.20));

        // Borde y texto: el color real de la parte (el indicador visible del color)
        int r = (int)(base.getRed()   * 255);
        int g = (int)(base.getGreen() * 255);
        int b = (int)(base.getBlue()  * 255);
        // Borde: color real al 80 % de opacidad
        String colorBdr  = "rgba(" + r + "," + g + "," + b + ",0.80)";
        String colorBdrH = "rgba(" + r + "," + g + "," + b + ",0.95)";
        // Texto: el color de la parte aclarado un 50 % para contraste sobre fondo oscuro
        String textColor = toHex(base.interpolate(Color.WHITE, 0.50));

        return switch (state) {
            case 1 -> // hover
                "-fx-background-color: linear-gradient(from 0% 0% to 0% 100%, " + hover1 + ", " + hover2 + ");" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: " + colorBdrH + ";" +
                "-fx-border-width: 1.5;" +
                "-fx-border-radius: 10;" +
                "-fx-text-fill: " + textColor + ";" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.40), 4, 0, 0, 1);";
            case 2 -> // selected: color completo, igual que toolbar toggle:selected
                "-fx-background-color: linear-gradient(from 0% 0% to 0% 100%, " + sel1 + ", " + part.getHexColor() + ");" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: rgba(255,255,255,0.55);" +
                "-fx-border-width: 1.5;" +
                "-fx-border-radius: 10;" +
                "-fx-text-fill: rgba(255,255,255,0.97);" +
                "-fx-effect: dropshadow(gaussian, rgba(" + r + "," + g + "," + b + ",0.80), 22, 0.10, 0, 0)," +
                    "innershadow(gaussian, rgba(255,255,255,0.18), 4, 0, 0, 1);";
            default -> // normal: fondo acero tintado + borde y texto del color real
                "-fx-background-color: linear-gradient(from 0% 0% to 0% 100%, " + dark1 + ", " + dark2 + ");" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: " + colorBdr + ";" +
                "-fx-border-width: 1.5;" +
                "-fx-border-radius: 10;" +
                "-fx-text-fill: " + textColor + ";" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.40), 4, 0, 0, 1);";
        };
    }

    /** Convierte un Color de JavaFX a cadena hexadecimal #RRGGBB. */
    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
            Math.min(255, (int)(c.getRed()   * 255)),
            Math.min(255, (int)(c.getGreen() * 255)),
            Math.min(255, (int)(c.getBlue()  * 255)));
    }

    @FXML private void handleMousePressed(MouseEvent e)  { beginStroke(e.getX(), e.getY()); }
    @FXML private void handleMouseDragged(MouseEvent e)  { continueStroke(e.getX(), e.getY()); }
    @FXML private void handleMouseReleased(MouseEvent e) { finishStroke(); }

    @FXML
    private void handleMouseMoved(MouseEvent e) {
        if (btnErase.isSelected()) {
            drawEraserPreview(e.getX(), e.getY());
            canvas.setCursor(Cursor.NONE);
        } else {
            clearCursorOverlay();
            canvas.setCursor(Cursor.CROSSHAIR);
        }
    }

    @FXML
    private void handleMouseExited(MouseEvent e) {
        clearCursorOverlay();
        canvas.setCursor(Cursor.DEFAULT);
    }

    @FXML
    private void handleTouchPressed(TouchEvent e) {
        if (e.getTouchCount() > 1) return;
        beginStroke(e.getTouchPoint().getX(), e.getTouchPoint().getY());
        e.consume();
    }

    @FXML
    private void handleTouchDragged(TouchEvent e) {
        if (e.getTouchCount() > 1) return;
        continueStroke(e.getTouchPoint().getX(), e.getTouchPoint().getY());
        e.consume();
    }

    @FXML
    private void handleTouchReleased(TouchEvent e) { finishStroke(); e.consume(); }

    private void beginStroke(double x, double y) {
        redoStack.clear();
        lastX = x; lastY = y;
        if (btnErase.isSelected()) {
            double er = ERASER_RADIUS / currentScale;
            gc.setFill(Color.WHITE);
            gc.fillRect(x - er, y - er, er * 2, er * 2);
            canvas.setCursor(Cursor.NONE);
            drawEraserPreview(x, y);
        } else {
            gc.setStroke(Color.web(currentPart.getHexColor()));
            gc.setLineWidth(effectiveLineWidth);
            gc.beginPath();
            gc.moveTo(lastX, lastY);
            gc.lineTo(lastX, lastY);
            gc.stroke();
            canvas.setCursor(Cursor.CROSSHAIR);
            // Ocultar pista cuando el usuario empieza a dibujar
            if (!canvasHasContent) {
                canvasHasContent = true;
                updateCanvasHint();
            }
        }
    }

    private void continueStroke(double x, double y) {
        if (btnErase.isSelected()) {
            double er = ERASER_RADIUS / currentScale;
            gc.setFill(Color.WHITE);
            gc.fillRect(x - er, y - er, er * 2, er * 2);
            drawEraserPreview(x, y);
        } else {
            gc.lineTo(x, y);
            gc.stroke();
        }
        lastX = x; lastY = y;
    }

    private void finishStroke() {
        if (!btnErase.isSelected()) { gc.stroke(); gc.closePath(); }
        clearCursorOverlay();
        saveCurrentState();
    }

    @FXML
    private void handleClear() {
        redoStack.clear();
        canvasHasContent = false;
        statusLabel.setText(I18n.t("status.cleared"));
        // Animación flash: parpadeo rápido antes de limpiar
        FadeTransition flash = new FadeTransition(Duration.millis(80), canvasContainer);
        flash.setFromValue(1.0);
        flash.setToValue(0.08);
        flash.setOnFinished(ev -> {
            clearToWhite();
            updateCanvasHint();
            saveCurrentState();
            FadeTransition restore = new FadeTransition(Duration.millis(120), canvasContainer);
            restore.setFromValue(0.08);
            restore.setToValue(1.0);
            restore.play();
        });
        flash.play();
    }

    @FXML
    private void handleUndo() {
        if (undoStack.size() > 1) {
            redoStack.push(undoStack.pop());
            clearToWhite();
            gc.drawImage(undoStack.peek(), 0, 0);
            statusLabel.setText(I18n.t("status.undo"));
            updateUndoRedoButtons();
        }
    }

    @FXML
    private void handleRedo() {
        if (!redoStack.isEmpty()) {
            WritableImage next = redoStack.pop();
            undoStack.push(next);
            clearToWhite();
            gc.drawImage(next, 0, 0);
            statusLabel.setText(I18n.t("status.redo"));
            updateUndoRedoButtons();
        }
    }

    // ── Tutorial ──────────────────────────────────────────────────────────────

    private void setupTutorial() {
        rootPane.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) return;
            // Wait for first layout pass so node bounds are valid
            Platform.runLater(() -> Platform.runLater(() -> {
                tutorialOverlay = buildTutorial();
                if (!TutorialOverlay.hasSeenTutorial()) {
                    tutorialOverlay.show();
                }
            }));
        });
    }

    private TutorialOverlay buildTutorial() {
        var steps = List.of(
            new TutorialOverlay.Step(
                "🎨", "tutorial.welcome.title", "tutorial.welcome.body"
            ),
            new TutorialOverlay.Step(
                sidePalette, "🖌️", "tutorial.palette.title", "tutorial.palette.body"
            ),
            new TutorialOverlay.Step(
                canvasContainer, "✏️", "tutorial.canvas.title", "tutorial.canvas.body"
            ),
            new TutorialOverlay.Step(
                toolBar, "🛠", "tutorial.tools.title", "tutorial.tools.body"
            ),
            new TutorialOverlay.Step(
                btnSearch, "🔍", "tutorial.search.title", "tutorial.search.body"
            ),
            new TutorialOverlay.Step(
                btnAddPhotos, "📸", "tutorial.library.title", "tutorial.library.body"
            ),
            new TutorialOverlay.Step(
                galleryPane, "🖼", "tutorial.gallery.title", "tutorial.gallery.body"
            ),
            new TutorialOverlay.Step(
                "🚀", "tutorial.finish.title", "tutorial.finish.body"
            )
        );

        TutorialOverlay t = new TutorialOverlay(rootPane, steps);
        t.setOnFinish(TutorialOverlay::markSeen);
        return t;
    }

    // ── i18n ──────────────────────────────────────────────────────────────────

    /**
     * Actualiza todos los textos estáticos de la UI al idioma activo.
     * Se llama en initialize() y cuando el usuario cambia el idioma.
     * Siempre se ejecuta en el hilo de JavaFX.
     */
    private void applyI18n() {
        // Toolbar buttons
        if (btnClear    != null) btnClear.setText(I18n.t("toolbar.clear"));
        if (btnUndo     != null) btnUndo.setText(I18n.t("toolbar.undo"));
        if (btnRedo     != null) btnRedo.setText(I18n.t("toolbar.redo"));
        if (btnDraw     != null) btnDraw.setText(I18n.t("toolbar.draw"));
        if (btnErase    != null) btnErase.setText(I18n.t("toolbar.erase"));
        if (btnSearch   != null) btnSearch.setText(I18n.t("toolbar.search"));
        if (btnAddPhotos != null) btnAddPhotos.setText(I18n.t("toolbar.addPhotos"));
        if (btnHelp     != null) btnHelp.setText(I18n.t("toolbar.help"));
        // Language button: shows which language you will switch TO
        if (btnLang != null) btnLang.setText(I18n.isEnglish() ? "🌐 ES" : "🌐 EN");

        // Section labels
        if (lblPalette      != null) lblPalette.setText(I18n.t("section.palette"));
        if (lblGallery      != null) lblGallery.setText(I18n.t("section.gallery"));
        if (lblStatusPrefix != null) lblStatusPrefix.setText(I18n.t("status.prefix"));
        if (lblBatch        != null) lblBatch.setText(I18n.t("section.progress.batch"));
        if (lblTotal        != null) lblTotal.setText(I18n.t("section.progress.total"));

        // Initial status (only if it still shows the ready message)
        if (statusLabel != null && shouldResetStatusToReady(statusLabel.getText())) {
            statusLabel.setText(I18n.t("status.ready"));
        }

        // Rebuild palette to translate anatomy part names
        if (paletteContainer != null) {
            paletteContainer.getChildren().clear();
            setupPalette();
        }
        // Actualizar pista del lienzo
        if (canvasHintLabel != null) {
            canvasHintLabel.setText(I18n.t("canvas.hint"));
        }
        // Actualizar placeholder de galería vacía
        if (galleryEmptyLabel != null) {
            galleryEmptyLabel.setText(I18n.t("gallery.emptyState"));
        }

        // Update tutorial if it's already built
        if (tutorialOverlay != null) {
            tutorialOverlay.applyI18n();
        }
    }

    /** True si el statusLabel aún muestra el mensaje inicial (vacío o "ready"). */
    private static boolean shouldResetStatusToReady(String currentText) {
        return currentText == null
            || currentText.isBlank()
            || currentText.equals("Listo para dibujar")
            || currentText.equals("Ready to draw");
    }

    @FXML
    private void handleLang() {
        I18n.toggleLocale();
        // applyI18n() will be called via the registered listener
    }

    @FXML
    private void handleHelp() {
        if (tutorialOverlay == null) {
            tutorialOverlay = buildTutorial();
        }
        TutorialOverlay.resetSeen();
        tutorialOverlay.show();
    }

    // ── Progress helpers ──────────────────────────────────────────────────────

    private void setSearchProgress(boolean visible, double batchPct, double totalPct, int round, int totalRounds) {
        progressWrapper.setVisible(visible);
        progressWrapper.setManaged(visible);
        if (!visible) {
            batchNumLabel.setText("");
            return;
        }
        // Batch bar
        double b = Math.min(1.0, Math.max(0.0, batchPct));
        batchProgressBar.setProgress(b);
        batchPctLabel.setText(b > 0 ? String.format("%.0f%%", b * 100) : "");
        // Total bar
        double t = Math.min(1.0, Math.max(0.0, totalPct));
        progressBar.setProgress(t);
        progressLabel.setText(String.format("%.0f%%", t * 100));
        // Batch counter badge
        if (totalRounds > 1) {
            batchNumLabel.setText(I18n.fmt("status.batch", round, totalRounds));
        } else {
            batchNumLabel.setText(round > 0 ? I18n.t("status.analyzing.round") : "");
        }
    }

    private void setSearchProgress(boolean visible, double indeterminate) {
        progressWrapper.setVisible(visible);
        progressWrapper.setManaged(visible);
        if (!visible) { batchNumLabel.setText(""); return; }
        batchProgressBar.setProgress(indeterminate);
        batchPctLabel.setText("");
        progressBar.setProgress(indeterminate);
        progressLabel.setText("");
        batchNumLabel.setText("");
    }

    @FXML
    private void handleLocalPhotoSearch() {
        statusLabel.setText(I18n.t("status.analyzing"));
        setSearchProgress(true, 0.0, 0.0, 0, 1);
        galleryPane.getChildren().clear();
        updateGalleryEmptyState(false);

        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        final WritableImage snapshot = canvas.snapshot(params, null);

        AtomicReference<PoseData> computedPoseRef = new AtomicReference<>();

        Task<List<ImageResult>> searchTask = new Task<>() {
            @Override
            protected List<ImageResult> call() {
                // Siempre re-analizar el lienzo actual para que cada búsqueda
                // refleje el stickman que el usuario acaba de dibujar.
                PoseData pose = DrawingProcessor.processImage(snapshot);
                computedPoseRef.set(pose);

                if (pose != null && !pose.getAllJoints().isEmpty()) {
                    List<String> terms = SearchTermGenerator.generateTerms(pose);
                    // Cuenta semántica: cada lado bilateral (L/R) suma 1;
                    // el centroide combinado no cuenta si ya hay splits detectados.
                    final int partCount = countMeaningfulJoints(pose);
                    currentSearchId = DatabaseManager.saveDrawing(pose, terms, null);
                    Platform.runLater(() ->
                            statusLabel.setText(I18n.fmt("status.poseDetected", partCount))
                    );
                    return SearchService.searchImages(terms, pose,
                            msg -> Platform.runLater(() -> statusLabel.setText(msg)),
                            p -> Platform.runLater(() ->
                                setSearchProgress(true, p[0], p[1], (int) p[2], (int) p[3]))
                    );
                }
                Platform.runLater(() -> statusLabel.setText(I18n.t("status.noPose")));
                return SearchService.searchLocalPhotos(List.of(), org.refcolor.buscareferencias.utils.PoseToleranceConfig.maxResults());
            }
        };

        searchTask.setOnSucceeded(e -> {
            PoseData pose = computedPoseRef.get();
            if (pose != null && !pose.getAllJoints().isEmpty()) {
                lastAnalyzedPose = pose;
            }

            List<ImageResult> results = searchTask.getValue();
            galleryPane.getChildren().clear();
            displayResults(results);
            setSearchProgress(false, -1);

            if (results.isEmpty()) {
                updateGalleryEmptyState(true);
                statusLabel.setText(buildEmptySearchMessage());
            } else {
                double best = results.stream().mapToDouble(ImageResult::getScore).filter(s -> s >= 0).max().orElse(0);
                statusLabel.setText(I18n.fmt("status.searchDone", results.size(), Math.round(best * 100)));
                if (currentSearchId != -1) {
                    DatabaseManager.saveResults(currentSearchId, results);
                }
            }
        });

        searchTask.setOnFailed(e -> {
            setSearchProgress(false, -1);
            statusLabel.setText(I18n.t("status.searchError"));
            logger.error("Error en búsqueda local", searchTask.getException());
        });

        new Thread(searchTask).start();
    }

    @FXML
    private void handleAddPhotos() {
        Path targetDir = ProjectPaths.getThumbnailsDirectory();

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Añadir fotos a la biblioteca de referencias");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Imágenes", "*.jpg", "*.jpeg", "*.png", "*.webp", "*.gif")
        );
        // Abrir directamente en la carpeta de la biblioteca
        try {
            Files.createDirectories(targetDir);
            chooser.setInitialDirectory(targetDir.toFile());
        } catch (Exception ignored) {}

        List<File> files = chooser.showOpenMultipleDialog(canvas.getScene().getWindow());
        if (files == null || files.isEmpty()) return;

        setSearchProgress(true, -1);
        statusLabel.setText(I18n.fmt("status.copying", files.size()));

        Task<Integer> copyTask = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                Files.createDirectories(targetDir);
                int copied = 0;
                for (File file : files) {
                    // Si el archivo ya está en la carpeta, no hace falta copiar
                    if (file.toPath().getParent().toAbsolutePath().equals(targetDir.toAbsolutePath())) {
                        copied++;
                        continue;
                    }
                    Path dest = resolveDestPath(targetDir, file.getName());
                    Files.copy(file.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
                return copied;
            }
        };

        copyTask.setOnSucceeded(e -> {
            setSearchProgress(false, 0.0);
            int copied = copyTask.getValue();
            statusLabel.setText(I18n.fmt("status.photosCopied", copied));
            logger.info("[FOTOS] {} imágenes añadidas a {}", copied, targetDir);
        });

        copyTask.setOnFailed(e -> {
            setSearchProgress(false, 0.0);
            String msg = copyTask.getException() != null ? copyTask.getException().getMessage() : "?";
            statusLabel.setText(I18n.fmt("status.photosError", msg));
            logger.error("Error copiando fotos a la biblioteca", copyTask.getException());
        });

        new Thread(copyTask).start();
    }

    private static Path resolveDestPath(Path dir, String fileName) {
        Path dest = dir.resolve(fileName);
        if (!Files.exists(dest)) return dest;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext  = dot > 0 ? fileName.substring(dot) : "";
        int counter = 1;
        do {
            dest = dir.resolve(base + "_" + counter + ext);
            counter++;
        } while (Files.exists(dest));
        return dest;
    }

    private void displayResults(List<ImageResult> results) {
        applyGalleryResponsiveLayout();
        if (results == null || results.isEmpty()) return;
        int rank = 1;
        int delayMs = 0;
        for (ImageResult result : results) {
            VBox card = buildGalleryCard(result, rank++);
            card.setOpacity(0);
            card.setTranslateY(14);
            galleryPane.getChildren().add(card);
            final int d = delayMs;
            new Timeline(
                new KeyFrame(Duration.millis(d),
                    new KeyValue(card.opacityProperty(),    0),
                    new KeyValue(card.translateYProperty(), 14)),
                new KeyFrame(Duration.millis(d + 220),
                    new KeyValue(card.opacityProperty(),    1,  Interpolator.EASE_OUT),
                    new KeyValue(card.translateYProperty(), 0,  Interpolator.EASE_OUT))
            ).play();
            delayMs = Math.min(delayMs + 45, 480);
        }
        refreshGalleryCardsSize();
        logger.info("[GALLERY] {} resultados mostrados", results.size());
    }

    private VBox buildGalleryCard(ImageResult result, int rank) {
        VBox card = new VBox(5);
        card.getStyleClass().add("image-card");
        card.setAlignment(Pos.CENTER);
        card.setPrefWidth(currentGalleryImageSize + 10);
        card.setPrefHeight(currentGalleryImageSize + 56);

        Label rankBadge = new Label(String.valueOf(rank));
        rankBadge.getStyleClass().add("gallery-rank");
        StackPane imageStack = new StackPane(buildCardImageView(result), rankBadge);
        StackPane.setAlignment(rankBadge, Pos.TOP_LEFT);

        card.getChildren().addAll(imageStack, buildScoreLabel(result, rank));
        card.setCursor(Cursor.HAND);
        Tooltip.install(card, new Tooltip(buildTooltip(result, rank)));
        card.setOnMouseClicked(e -> openResultSource(result));

        // Animación hover: levantar la card y volver a su lugar
        card.setOnMouseEntered(e -> {
            card.setViewOrder(-1); // renderizar sobre las demás
            new Timeline(new KeyFrame(Duration.millis(110),
                new KeyValue(card.translateYProperty(), -5, Interpolator.EASE_OUT)
            )).play();
        });
        card.setOnMouseExited(e -> {
            Timeline drop = new Timeline(new KeyFrame(Duration.millis(110),
                new KeyValue(card.translateYProperty(), 0, Interpolator.EASE_OUT)
            ));
            drop.setOnFinished(ev -> card.setViewOrder(0));
            drop.play();
        });
        return card;
    }

    private ImageView buildCardImageView(ImageResult result) {
        ImageView iv = new ImageView();
        iv.setFitWidth(currentGalleryImageSize);
        iv.setFitHeight(currentGalleryImageSize);
        iv.setPreserveRatio(true);
        try {
            String thumbUrl = resolveThumbUrl(result);
            Image img = new Image(thumbUrl, currentGalleryImageSize, currentGalleryImageSize, true, true, true);
            iv.setImage(img);
            img.exceptionProperty().addListener((obs, oldEx, newEx) -> {
                if (newEx != null) logger.warn("Error al cargar imagen: {}", thumbUrl);
            });
        } catch (Exception e) {
            logger.warn("Error al instanciar imagen: {}", result.getThumbnailUrl());
        }
        return iv;
    }

    private static String resolveThumbUrl(ImageResult result) {
        String display = result.getDisplayThumbnailUrl();
        return (display == null || display.isBlank()) ? result.getThumbnailUrl() : display;
    }

    private Label buildScoreLabel(ImageResult result, int rank) {
        if (result.getScore() <= 0.0) {
            String title = result.getTitle() == null || result.getTitle().isBlank() ? "Ref" : result.getTitle();
            Label label = new Label(String.format("#%d · %s", rank, title));
            label.setStyle("-fx-font-size: 11px; -fx-text-fill: #7A9AA8;");
            return label;
        }
        double pct = result.getScore() * 100;
        Label label = new Label(I18n.fmt("gallery.similarity", Math.round(pct)));
        label.setStyle(buildScoreLabelStyle(pct));
        return label;
    }

    private static String buildScoreLabelStyle(double pct) {
        // 3 niveles alineados con la paleta del proyecto
        String colorPart;
        if (pct >= 70) {
            // Alto: Sage teal  (#5A8A7C)
            colorPart = "rgba(90,138,124,0.22); -fx-text-fill: #7DC9B0;";
        } else if (pct >= 60) {
            // Medio: Amber (#D4A064)
            colorPart = "rgba(212,160,100,0.22); -fx-text-fill: #D4A064;";
        } else {
            // Bajo: Copper (#C17B5E)
            colorPart = "rgba(193,123,94,0.22); -fx-text-fill: #C17B5E;";
        }
        return "-fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 3 8 3 8;" +
               "-fx-background-radius: 8; -fx-background-color: " + colorPart;
    }

    private String buildEmptySearchMessage() {
        try {
            Path dir = ProjectPaths.getThumbnailsDirectory();
            long count = Files.list(dir).filter(Files::isRegularFile).count();
            if (count == 0) return I18n.fmt("gallery.emptyLibrary", dir);
            return I18n.fmt("gallery.emptyNoPose", count);
        } catch (Exception e) {
            return I18n.t("gallery.noResults");
        }
    }

    private String buildTooltip(ImageResult result, int rank) {
        StringBuilder sb = new StringBuilder(I18n.fmt("tooltip.rank", rank));
        if (result.getTitle() != null && !result.getTitle().isBlank())
            sb.append("\n").append(result.getTitle());
        if (result.getScore() > 0.0)
            sb.append("\n").append(I18n.fmt("tooltip.similarity", Math.round(result.getScore() * 100.0)));
        sb.append("\n").append(I18n.t("tooltip.open"));
        return sb.toString();
    }

    private void openResultSource(ImageResult result) {
        String target = result.getOriginalUrl();
        if (target == null || target.isBlank()) target = result.getDisplayThumbnailUrl();
        if (target == null || target.isBlank()) { logger.warn("Sin ruta local para abrir."); return; }
        try {
            if (Desktop.isDesktopSupported()) {
                Path path = target.startsWith("file:")
                        ? java.nio.file.Paths.get(URI.create(target))
                        : java.nio.file.Paths.get(target);
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (Exception e) {
            logger.error("No se pudo abrir: {}", target, e);
        }
    }

    // ── Interactividad ────────────────────────────────────────────────────────

    /**
     * Crea el canvas overlay transparente que se usa para dibujar la
     * previsualización del borrador (círculo que sigue al cursor).
     */
    private void setupCursorOverlay() {
        if (canvasContainer == null || canvas == null) return;
        cursorOverlay = new Canvas(canvas.getWidth(), canvas.getHeight());
        cursorOverlay.setMouseTransparent(true);
        cursorOverlay.widthProperty().bind(canvas.widthProperty());
        cursorOverlay.heightProperty().bind(canvas.heightProperty());
        canvasContainer.getChildren().add(cursorOverlay);
    }

    /**
     * Crea la etiqueta de pista que aparece en el centro del lienzo vacío
     * ("Elige una parte y dibuja la pose aquí").
     * Se añade encima del canvas como overlay no interactivo.
     */
    private void setupCanvasHint() {
        if (canvasContainer == null) return;
        canvasHintLabel = new Label(I18n.t("canvas.hint"));
        canvasHintLabel.getStyleClass().add("canvas-hint-label");
        canvasHintLabel.setMouseTransparent(true);
        canvasHintLabel.setWrapText(true);
        canvasHintLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        canvasHintLabel.setAlignment(javafx.geometry.Pos.CENTER);
        canvasHintLabel.setMaxWidth(Double.MAX_VALUE);
        canvasContainer.getChildren().add(canvasHintLabel);
    }

    /**
     * Dibuja un círculo translúcido sobre el overlay mostrando el área
     * que borrará el borrador en la posición (x, y) del canvas.
     */
    private void drawEraserPreview(double x, double y) {
        if (cursorOverlay == null) return;
        GraphicsContext ogc = cursorOverlay.getGraphicsContext2D();
        ogc.clearRect(0, 0, cursorOverlay.getWidth(), cursorOverlay.getHeight());
        double er = ERASER_RADIUS / currentScale;
        // Relleno muy transparente
        ogc.setFill(Color.rgb(132, 84, 96, 0.10));
        ogc.fillOval(x - er, y - er, er * 2, er * 2);
        // Borde nítido mauve
        ogc.setStroke(Color.web("#845460"));
        ogc.setLineWidth(1.5 / currentScale);
        ogc.strokeOval(x - er, y - er, er * 2, er * 2);
        // Cruz central pequeña
        double cs = 4 / currentScale;
        ogc.setStroke(Color.rgb(132, 84, 96, 0.60));
        ogc.setLineWidth(1.0 / currentScale);
        ogc.strokeLine(x - cs, y, x + cs, y);
        ogc.strokeLine(x, y - cs, x, y + cs);
    }

    /** Limpia el overlay del cursor (borra el círculo del borrador). */
    private void clearCursorOverlay() {
        if (cursorOverlay == null) return;
        cursorOverlay.getGraphicsContext2D()
                     .clearRect(0, 0, cursorOverlay.getWidth(), cursorOverlay.getHeight());
    }

    /** Muestra u oculta la pista del lienzo vacío según {@code canvasHasContent}. */
    private void updateCanvasHint() {
        if (canvasHintLabel == null) return;
        canvasHintLabel.setVisible(!canvasHasContent);
        canvasHintLabel.setManaged(!canvasHasContent);
    }

    /** Habilita/deshabilita los botones Deshacer y Rehacer según el estado de las pilas. */
    private void updateUndoRedoButtons() {
        if (btnUndo != null) btnUndo.setDisable(undoStack.size() <= 1);
        if (btnRedo != null) btnRedo.setDisable(redoStack.isEmpty());
    }

    /**
     * Muestra u oculta el placeholder de la galería vacía.
     * Se muestra al arrancar y cuando no hay resultados; se oculta cuando hay
     * imágenes en la galería o cuando empieza una búsqueda.
     */
    private void updateGalleryEmptyState(boolean empty) {
        if (galleryEmptyState == null) return;
        galleryEmptyState.setVisible(empty);
    }

    /**
     * Cuenta las partes corporales de forma semántica:
     * <ul>
     *   <li>Las variantes bilaterales ({@code LEFT_ARM}, {@code RIGHT_ARM}, …) cuentan
     *       cada una como 1.</li>
     *   <li>El centroide combinado ({@code ARMS}) <em>no</em> se cuenta si ya existen
     *       splits bilaterales detectados para esa misma parte.</li>
     *   <li>Las partes no bilaterales ({@code HEAD}, {@code TORSO}) cuentan como 1 si
     *       están presentes en la pose.</li>
     * </ul>
     * Ejemplo: HEAD + LEFT_ARM + RIGHT_ARM + LEFT_FOREARM + RIGHT_FOREARM +
     * LEFT_HAND + RIGHT_HAND → 7 (el centroide combinado ARMS/FOREARMS/HANDS
     * no se suma porque sus splits ya cuentan).
     */
    private static int countMeaningfulJoints(PoseData pose) {
        java.util.Set<AnatomyPart> joints = pose.getAllJoints().keySet();
        int count = 0;
        for (AnatomyPart part : AnatomyPart.values()) {
            if (!part.isPaletteItem()) {
                // Split bilateral (LEFT_ARM, RIGHT_ARM, etc.): cuenta como parte propia
                if (joints.contains(part)) count++;
            } else {
                AnatomyPart leftVar = part.leftVariant();
                if (leftVar == null) {
                    // No bilateral (HEAD, TORSO): cuenta 1 si está presente
                    if (joints.contains(part)) count++;
                } else {
                    // Parte bilateral: el centroide combinado solo cuenta si NO hay splits
                    AnatomyPart rightVar = part.rightVariant();
                    boolean hasSplits = joints.contains(leftVar) || joints.contains(rightVar);
                    if (!hasSplits && joints.contains(part)) count++;
                    // Los splits se contabilizan en la rama !isPaletteItem() de arriba
                }
            }
        }
        return count;
    }
}
