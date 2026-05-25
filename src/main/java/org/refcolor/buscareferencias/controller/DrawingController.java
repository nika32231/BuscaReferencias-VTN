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
import java.util.Map;
import java.util.Set;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.refcolor.buscareferencias.database.DatabaseManager;
import org.refcolor.buscareferencias.i18n.I18n;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.model.SimilarityBreakdown;
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

    /**
     * Conexiones del esqueleto MediaPipe Pose (33 landmarks).
     * Usadas para visualizar la pose detectada sobre la foto en el diálogo de detalle.
     */
    private static final int[][] SKELETON_CONNECTIONS = {
        // Cara
        {0,1},{1,2},{2,3},{3,7}, {0,4},{4,5},{5,6},{6,8}, {9,10},
        // Torso superior
        {11,12},
        // Brazo izquierdo
        {11,13},{13,15},
        // Brazo derecho
        {12,14},{14,16},
        // Manos (dedos simplificados)
        {15,17},{15,19},{15,21}, {16,18},{16,20},{16,22},
        // Torso
        {11,23},{12,24},{23,24},
        // Pierna izquierda
        {23,25},{25,27},{27,29},{29,31},{27,31},
        // Pierna derecha
        {24,26},{26,28},{28,30},{30,32},{28,32}
    };

    /** Landmarks del lado izquierdo de MediaPipe (verde en visualización auténtica). */
    private static final java.util.Set<Integer> SKEL_LEFT_IDS = java.util.Set.of(
            1, 2, 3, 7, 11, 13, 15, 17, 19, 21, 23, 25, 27, 29, 31);
    /** Landmarks del lado derecho de MediaPipe (rojo en visualización auténtica). */
    private static final java.util.Set<Integer> SKEL_RIGHT_IDS = java.util.Set.of(
            4, 5, 6, 8, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32);
    // Centro (0, 9, 10) → blanco
    private static final Color SKEL_LEFT   = Color.web("#00CC88");  // verde
    private static final Color SKEL_RIGHT  = Color.web("#FF5555");  // rojo
    private static final Color SKEL_CENTER = Color.web("#FFFFFF");  // blanco

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

    // ── Callback de logout (inyectado por SplashController) ──────────────────
    private Runnable onLogout;

    // Stage guardado para poder navegar de vuelta desde subscreens (canvas.getScene()
    // devuelve null una vez que setRoot() sustituye el root con otra vista)
    private javafx.stage.Stage savedStage;

    public void setOnLogout(Runnable r) { this.onLogout = r; }

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

    // ── Control de versión del canvas (para detección de cambio entre búsquedas) ─
    /** Incrementa con cada trazo, undo, redo o clear. */
    private long canvasVersion       = 0L;
    /** Versión del canvas en el momento de la última búsqueda lanzada. */
    private long canvasVersionAtSearch = -1L;

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

        // Tema: reconstruir paleta cuando el usuario cambie el tema desde Ajustes
        org.refcolor.buscareferencias.settings.ThemeManager.addChangeListener(
            () -> Platform.runLater(this::applyThemeChange));

        // Añadir botones de usuario/historial/ajustes al toolbar (dinámicamente)
        addUserToolbarButtons();

        // Cargar ajustes (photo limit, sound, etc.)
        org.refcolor.buscareferencias.settings.AppSettings.load();

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
                sidePalette.setPrefWidth(220);
                sidePalette.setMinWidth(200);
            } else if (compact) {
                sidePalette.setPrefWidth(285);
                sidePalette.setMinWidth(260);
            } else if (large) {
                sidePalette.setPrefWidth(360);
                sidePalette.setMinWidth(330);
            } else {
                sidePalette.setPrefWidth(310);
                sidePalette.setMinWidth(285);
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

        double target = sceneWidth < BREAKPOINT_PHONE ? 160
                      : sceneWidth < BREAKPOINT_COMPACT ? 180
                      : sceneWidth >= BREAKPOINT_LARGE  ? 240
                      : 200;
        int cols = Math.max(2, (int) Math.floor((available + 12) / (target + 12)));
        currentGalleryImageSize = clamp((available - ((cols - 1) * 12.0)) / cols, 140, 280);
        galleryPane.setPrefWrapLength(available);
        refreshGalleryCardsSize();
    }

    private void refreshGalleryCardsSize() {
        if (galleryPane == null) return;
        for (Node n : galleryPane.getChildren()) {
            // Estructura: HBox(container) → VBox(imageCard) + StackPane(infoPanelWrapper)
            if (!(n instanceof HBox container)) continue;
            if (container.getChildren().isEmpty()) continue;
            Node first = container.getChildren().get(0);
            if (!(first instanceof VBox imageCard)) continue;
            imageCard.setPrefWidth(currentGalleryImageSize + 10);
            imageCard.setPrefHeight(currentGalleryImageSize + 56);
            for (Node child : imageCard.getChildren()) {
                if (child instanceof StackPane stack) {
                    if (stack.getClip() instanceof Rectangle clip) {
                        clip.setWidth(currentGalleryImageSize);
                        clip.setHeight(currentGalleryImageSize);
                    }
                    for (Node sc : stack.getChildren()) {
                        if (sc instanceof ImageView iv) {
                            iv.setFitWidth(currentGalleryImageSize);
                            iv.setFitHeight(currentGalleryImageSize);
                        }
                    }
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
        paletteContainer.getChildren().clear();

        // ── Cabeza y torso ─────────────────────────────────────────────────────
        paletteContainer.getChildren().add(createPaletteBtn(AnatomyPart.HEAD,  paletteGroup, selected));
        paletteContainer.getChildren().add(createPaletteBtn(AnatomyPart.TORSO, paletteGroup, selected));

        // ── Sección: Brazos ────────────────────────────────────────────────────
        paletteContainer.getChildren().add(makeSectionLabel(
                I18n.isEnglish() ? "ARMS" : "BRAZOS"));
        paletteContainer.getChildren().add(createPaletteBtn(AnatomyPart.SHOULDERS, paletteGroup, selected));
        paletteContainer.getChildren().add(makePalettePairRow(
                AnatomyPart.LEFT_ARM,     AnatomyPart.RIGHT_ARM,     paletteGroup, selected));
        paletteContainer.getChildren().add(makePalettePairRow(
                AnatomyPart.LEFT_FOREARM, AnatomyPart.RIGHT_FOREARM, paletteGroup, selected));
        paletteContainer.getChildren().add(makePalettePairRow(
                AnatomyPart.LEFT_HAND,    AnatomyPart.RIGHT_HAND,    paletteGroup, selected));

        // ── Sección: Piernas ───────────────────────────────────────────────────
        paletteContainer.getChildren().add(makeSectionLabel(
                I18n.isEnglish() ? "LEGS" : "PIERNAS"));
        paletteContainer.getChildren().add(createPaletteBtn(AnatomyPart.HIPS, paletteGroup, selected));
        paletteContainer.getChildren().add(makePalettePairRow(
                AnatomyPart.LEFT_THIGH, AnatomyPart.RIGHT_THIGH, paletteGroup, selected));
        paletteContainer.getChildren().add(makePalettePairRow(
                AnatomyPart.LEFT_CALF,  AnatomyPart.RIGHT_CALF,  paletteGroup, selected));
        paletteContainer.getChildren().add(makePalettePairRow(
                AnatomyPart.LEFT_FOOT,  AnatomyPart.RIGHT_FOOT,  paletteGroup, selected));
    }

    /** Crea un separador de sección con texto pequeño. */
    private static javafx.scene.control.Label makeSectionLabel(String text) {
        var lbl = new javafx.scene.control.Label(text);
        lbl.setMaxWidth(Double.MAX_VALUE);
        lbl.setStyle(
            "-fx-font-size: 10px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: rgba(180,210,235,0.60);" +
            "-fx-padding: 8 0 3 6;" +
            "-fx-border-width: 0 0 1 0;" +
            "-fx-border-color: rgba(180,210,235,0.22);" +
            "-fx-background-color: transparent;");
        return lbl;
    }

    /** Crea una fila HBox con dos botones de paleta lado a lado (Izq. | Der.). */
    private HBox makePalettePairRow(AnatomyPart left, AnatomyPart right,
                                    ToggleGroup group, AnatomyPart selected) {
        HBox row = new HBox(5);
        row.setMaxWidth(Double.MAX_VALUE);
        ToggleButton lBtn = createPaletteBtn(left,  group, selected);
        ToggleButton rBtn = createPaletteBtn(right, group, selected);
        HBox.setHgrow(lBtn, Priority.ALWAYS);
        HBox.setHgrow(rBtn, Priority.ALWAYS);
        lBtn.setMaxWidth(Double.MAX_VALUE);
        rBtn.setMaxWidth(Double.MAX_VALUE);
        row.getChildren().addAll(lBtn, rBtn);
        return row;
    }

    /** Crea un único ToggleButton de paleta con sus listeners de estilo y acción. */
    private ToggleButton createPaletteBtn(AnatomyPart part, ToggleGroup group, AnatomyPart selected) {
        String partName = I18n.t("anatomy." + part.name());
        ToggleButton btn = new ToggleButton();
        btn.setToggleGroup(group);
        btn.getStyleClass().add("palette-pill-button");
        btn.setUserData(part);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setTooltip(new Tooltip(I18n.fmt("palette.tooltip", partName)));
        btn.setGraphic(buildPaletteGraphic(part, partName, false));
        btn.setStyle(buildPillStyle(part, 0));

        btn.hoverProperty().addListener((obs, was, now) -> {
            if (!btn.isSelected()) btn.setStyle(buildPillStyle(part, now ? 1 : 0));
        });
        btn.selectedProperty().addListener((obs, was, now) -> {
            btn.setGraphic(buildPaletteGraphic(part, partName, now));
            btn.setStyle(buildPillStyle(part, now ? 2 : 0));
            btn.setScaleX(1.0); btn.setScaleY(1.0);
            if (now) {
                ScaleTransition bounce = new ScaleTransition(Duration.millis(130), btn);
                bounce.setFromX(0.94); bounce.setFromY(0.94);
                bounce.setToX(1.0);   bounce.setToY(1.0);
                bounce.setInterpolator(Interpolator.EASE_OUT);
                bounce.play();
            }
        });
        btn.setOnAction(e -> {
            currentPart = part;
            btnDraw.setSelected(true);
            statusLabel.setText(I18n.fmt("status.drawing", I18n.t("anatomy." + part.name())));
            gc.setStroke(Color.web(part.getHexColor()));
        });
        if (part == selected) btn.setSelected(true);
        return btn;
    }

    /**
     * Construye el gráfico interno del botón: [● swatch coloreado] + [texto].
     *
     * El círculo de color SIEMPRE muestra el hex exacto de la parte anatómica,
     * independientemente del estado del botón (normal / hover / seleccionado).
     * Así el usuario siempre ve con qué color va a dibujar.
     */
    private static HBox buildPaletteGraphic(AnatomyPart part, String label, boolean selected) {
        boolean dark = org.refcolor.buscareferencias.settings.AppSettings.isDarkTheme();
        Color base = Color.web(part.getHexColor());

        // ── Círculo swatch ──────────────────────────────────────────────────────
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(selected ? 11.0 : 9.5);
        dot.setFill(base);
        // En tema claro el anillo del swatch es oscuro para que contraste con el fondo blanco
        dot.setStroke(Color.web(selected
            ? (dark ? "rgba(255,255,255,0.95)" : "rgba(0,0,0,0.70)")
            : (dark ? "rgba(255,255,255,0.55)" : "rgba(0,0,0,0.35)")));
        dot.setStrokeWidth(selected ? 2.0 : 1.0);
        if (selected) {
            javafx.scene.effect.DropShadow glow = new javafx.scene.effect.DropShadow(
                    14, 0, 0, new Color(base.getRed(), base.getGreen(), base.getBlue(), 0.90));
            dot.setEffect(glow);
        }

        // ── Etiqueta de texto ───────────────────────────────────────────────────
        // En tema oscuro: blanco/azul claro. En tema claro: dejamos que CSS lo controle.
        Label txt = new Label(label);
        if (dark) {
            txt.setStyle("-fx-text-fill: " + (selected ? "#FFFFFF" : "rgba(198,220,242,0.92)") + ";"
                       + " -fx-font-size: 14px;"
                       + (selected ? " -fx-font-weight: bold;" : ""));
        } else {
            // Tema claro: eliminar inline color y dejar que style-light.css lo defina
            txt.setStyle("-fx-font-size: 14px;" + (selected ? " -fx-font-weight: bold;" : ""));
        }

        HBox box = new HBox(10, dot, txt);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * Genera el inline style para un botón-pastilla de paleta.
     *
     * En tema OSCURO usa colores hardcoded (fondo oscuro + acento lateral de color):
     *   state 0 = normal   → fondo oscuro neutro + franja izquierda coloreada (3.5 px)
     *   state 1 = hover    → fondo ligeramente más claro + franja 4 px + glow suave
     *   state 2 = selected → fondo tintado con el color + borde blanco 2 px + glow fuerte
     *
     * En tema CLARO retorna un inline style mínimo (solo border-radius y el glow de color)
     * para que style-light.css pueda controlar el fondo, texto y bordes.
     *
     * El círculo swatch del gráfico ({@link #buildPaletteGraphic}) siempre muestra
     * el color exacto, por lo que el fondo del botón no necesita serlo.
     */
    private static String buildPillStyle(AnatomyPart part, int state) {
        boolean dark = org.refcolor.buscareferencias.settings.AppSettings.isDarkTheme();
        Color base = Color.web(part.getHexColor());
        int r = (int)(base.getRed()   * 255);
        int g = (int)(base.getGreen() * 255);
        int b = (int)(base.getBlue()  * 255);
        String glow = "dropshadow(gaussian, rgba(" + r + "," + g + "," + b + ",0.85), 18, 0.22, 0, 0)";

        if (!dark) {
            // Tema claro: inline style mínimo — solo efecto de glow y borde coloreado;
            // el CSS de style-light.css gestiona el fondo y el color de texto.
            return switch (state) {
                case 1 -> // hover
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: transparent transparent transparent rgba(" + r + "," + g + "," + b + ",0.90);" +
                    "-fx-border-width: 0 0 0 4;" +
                    "-fx-border-radius: 8;" +
                    "-fx-effect: " + glow + ";";
                case 2 -> // selected
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: rgba(" + r + "," + g + "," + b + ",1.0);" +
                    "-fx-border-width: 2;" +
                    "-fx-border-radius: 8;" +
                    "-fx-effect: " + glow + ";";
                default -> // normal
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: transparent transparent transparent rgba(" + r + "," + g + "," + b + ",0.70);" +
                    "-fx-border-width: 0 0 0 3.5;" +
                    "-fx-border-radius: 8;";
            };
        }

        // Tema oscuro: colores hardcoded (comportamiento original)
        Color selectedBg = base.interpolate(Color.web("#0D1B26"), 0.78);
        return switch (state) {
            case 1 -> // hover: fondo algo más claro + franja izquierda 4 px + glow suave
                "-fx-background-color: rgba(28,54,82,0.96);" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: transparent transparent transparent rgba(" + r + "," + g + "," + b + ",1.0);" +
                "-fx-border-width: 0 0 0 4;" +
                "-fx-border-radius: 8;" +
                "-fx-effect: " + glow + ";";
            case 2 -> // selected: fondo tintado + borde blanco 2 px + glow fuerte
                "-fx-background-color: " + toHex(selectedBg) + ";" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: rgba(255,255,255,0.92);" +
                "-fx-border-width: 2;" +
                "-fx-border-radius: 8;" +
                "-fx-effect: " + glow + ";";
            default -> // normal: fondo oscuro + franja izquierda coloreada 3.5 px
                "-fx-background-color: rgba(18,36,56,0.88);" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: transparent transparent transparent rgba(" + r + "," + g + "," + b + ",0.80);" +
                "-fx-border-width: 0 0 0 3.5;" +
                "-fx-border-radius: 8;" +
                "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.48),4,0,0,1);";
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
        canvasVersion++;
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
            canvasVersion++;
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
            canvasVersion++;
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
            canvasVersion++;
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
                    drawTutorialStickman();
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

    /**
     * Dibuja un stickman de demostración <em>anatómicamente correcto</em> usando
     * los colores de cada {@link AnatomyPart}.
     *
     * <h3>Diseño anatómico</h3>
     * <ul>
     *   <li><b>SHOULDERS (hombros)</b> — círculos rellenos en la articulación exacta.
     *       Mapean directamente a los landmarks LM 11/12 de MediaPipe → escala y
     *       vectores de ángulo precisos.</li>
     *   <li><b>ARMS</b> — líneas desde hombro hasta codo (parte superior del brazo).</li>
     *   <li><b>FOREARMS</b> — líneas desde codo hasta muñeca (antebrazo doblado arriba).</li>
     *   <li><b>HANDS</b> — pequeños óvalos en la muñeca.</li>
     *   <li><b>HIPS (caderas)</b> — círculos rellenos en la articulación exacta.
     *       Mapean a LM 23/24 → misma referencia de escala que la imagen.</li>
     *   <li><b>THIGHS → CALVES → FEET</b> — segmentos de pierna desde cada cadera.</li>
     * </ul>
     *
     * <p>Todos los pares (brazos, piernas, hombros, caderas) aparecen en ambos
     * semilados del canvas para que {@link org.refcolor.buscareferencias.utils.DrawingProcessor}
     * active la detección bilateral en cada parte.</p>
     */
    private void drawTutorialStickman() {
        if (gc == null || canvas == null) return;

        double W  = canvas.getWidth();
        double H  = canvas.getHeight();
        double cx = W * 0.50;

        gc.save();
        gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        gc.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        gc.setLineWidth(12.0);

        // ── HEAD ──────────────────────────────────────────────────────────────
        gc.setStroke(Color.web(AnatomyPart.HEAD.getHexColor()));
        gc.strokeOval(cx - W * 0.037, H * 0.032, W * 0.074, H * 0.115);
        //   Centro del óvalo: (cx, H*0.090)

        // ── TORSO (columna vertebral) ──────────────────────────────────────────
        gc.setStroke(Color.web(AnatomyPart.TORSO.getHexColor()));
        gc.strokeLine(cx, H * 0.148, cx, H * 0.500);

        // ── SHOULDERS — círculos rellenos en la articulación exacta ────────────
        //   Posición: (cx ± W*0.110, H*0.220)  →  ≈ LM 11 / LM 12 de MediaPipe
        double dotR = W * 0.016; // radio del círculo ≈ 14 px
        Color shouldersColor = Color.web(AnatomyPart.SHOULDERS.getHexColor());
        gc.setFill(shouldersColor);
        gc.setStroke(shouldersColor);
        gc.fillOval(cx - W * 0.110 - dotR, H * 0.220 - dotR, dotR * 2, dotR * 2); // hombro izq.
        gc.fillOval(cx + W * 0.110 - dotR, H * 0.220 - dotR, dotR * 2, dotR * 2); // hombro der.

        // ── BRAZOS (hombro → codo): color propio por lado ──────────────────────
        gc.setStroke(Color.web(AnatomyPart.LEFT_ARM.getHexColor()));
        gc.strokeLine(cx - W * 0.110, H * 0.220, cx - W * 0.220, H * 0.375); // brazo izq.
        gc.setStroke(Color.web(AnatomyPart.RIGHT_ARM.getHexColor()));
        gc.strokeLine(cx + W * 0.110, H * 0.220, cx + W * 0.220, H * 0.375); // brazo der.

        // ── ANTEBRAZOS (codo → muñeca, doblado hacia arriba): color por lado ───
        gc.setStroke(Color.web(AnatomyPart.LEFT_FOREARM.getHexColor()));
        gc.strokeLine(cx - W * 0.220, H * 0.375, cx - W * 0.270, H * 0.275); // antebrazo izq.
        gc.setStroke(Color.web(AnatomyPart.RIGHT_FOREARM.getHexColor()));
        gc.strokeLine(cx + W * 0.220, H * 0.375, cx + W * 0.270, H * 0.275); // antebrazo der.

        // ── MANOS (muñecas): color propio por lado ──────────────────────────────
        double hw = W * 0.022, hh = H * 0.040;
        gc.setStroke(Color.web(AnatomyPart.LEFT_HAND.getHexColor()));
        gc.strokeOval(cx - W * 0.281, H * 0.251, hw, hh); // mano izq. → centro ≈ (cx-W*0.270, H*0.271)
        gc.setStroke(Color.web(AnatomyPart.RIGHT_HAND.getHexColor()));
        gc.strokeOval(cx + W * 0.259, H * 0.251, hw, hh); // mano der. → centro ≈ (cx+W*0.270, H*0.271)

        // ── HIPS — círculos rellenos en la articulación exacta ─────────────────
        //   Posición: (cx ± W*0.055, H*0.500)  →  ≈ LM 23 / LM 24 de MediaPipe
        Color hipsColor = Color.web(AnatomyPart.HIPS.getHexColor());
        gc.setFill(hipsColor);
        gc.setStroke(hipsColor);
        gc.fillOval(cx - W * 0.055 - dotR, H * 0.500 - dotR, dotR * 2, dotR * 2); // cadera izq.
        gc.fillOval(cx + W * 0.055 - dotR, H * 0.500 - dotR, dotR * 2, dotR * 2); // cadera der.

        // ── MUSLOS (cadera → rodilla): color propio por lado ──────────────────
        gc.setStroke(Color.web(AnatomyPart.LEFT_THIGH.getHexColor()));
        gc.strokeLine(cx - W * 0.055, H * 0.500, cx - W * 0.090, H * 0.665); // muslo izq.
        gc.setStroke(Color.web(AnatomyPart.RIGHT_THIGH.getHexColor()));
        gc.strokeLine(cx + W * 0.055, H * 0.500, cx + W * 0.090, H * 0.665); // muslo der.

        // ── PANTORRILLAS (rodilla → tobillo): color propio por lado ────────────
        gc.setStroke(Color.web(AnatomyPart.LEFT_CALF.getHexColor()));
        gc.strokeLine(cx - W * 0.090, H * 0.665, cx - W * 0.093, H * 0.825); // pantorrilla izq.
        gc.setStroke(Color.web(AnatomyPart.RIGHT_CALF.getHexColor()));
        gc.strokeLine(cx + W * 0.090, H * 0.665, cx + W * 0.093, H * 0.825); // pantorrilla der.

        // ── PIES: color propio por lado ────────────────────────────────────────
        gc.setStroke(Color.web(AnatomyPart.LEFT_FOOT.getHexColor()));
        gc.strokeLine(cx - W * 0.093, H * 0.825, cx - W * 0.148, H * 0.843); // pie izq.
        gc.setStroke(Color.web(AnatomyPart.RIGHT_FOOT.getHexColor()));
        gc.strokeLine(cx + W * 0.093, H * 0.825, cx + W * 0.148, H * 0.843); // pie der.

        gc.restore();

        // Restablecer grosor y color del lápiz activo tras gc.restore()
        gc.setLineWidth(effectiveLineWidth);
        gc.setStroke(Color.web(currentPart.getHexColor()));

        // Marcar el lienzo como no vacío y gestionar stacks de undo/redo
        canvasHasContent = true;
        updateCanvasHint();
        redoStack.clear();
        saveCurrentState(); // apila el stickman como estado deshacer
        updateUndoRedoButtons();

        logger.info("[TUTORIAL] Stickman de demostración dibujado ({} × {} px).", (int) W, (int) H);
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

    /**
     * Reconstruye la paleta cuando el usuario cambia el tema (claro ↔ oscuro).
     * Se invoca desde ThemeManager — siempre en el hilo de JavaFX.
     */
    private void applyThemeChange() {
        if (paletteContainer != null) {
            paletteContainer.getChildren().clear();
            setupPalette();
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
        if (!canvasHasContent) {
            drawTutorialStickman();
        }
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
        // Determinar si el lienzo ha cambiado desde la última búsqueda
        boolean isFreshSearch = (canvasVersion != canvasVersionAtSearch);
        canvasVersionAtSearch = canvasVersion;  // registrar para la próxima vez

        if (isFreshSearch) {
            statusLabel.setText(I18n.t("status.analyzing"));
            galleryPane.getChildren().clear();
            updateGalleryEmptyState(false);
        } else {
            // Mismo dibujo → continuar con el siguiente lote no analizado aún
            statusLabel.setText(I18n.isEnglish() ? "⏩ Continuing search..." : "⏩ Continuando búsqueda...");
        }
        setSearchProgress(true, 0.0, 0.0, 0, 1);

        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        final WritableImage snapshot = canvas.snapshot(params, null);

        // Si es continuación y tenemos pose guardada, reutilizarla para evitar
        // re-procesar el canvas (el pose del stickman no ha cambiado).
        final PoseData reuseablePose = (!isFreshSearch && lastAnalyzedPose != null)
            ? lastAnalyzedPose : null;

        AtomicReference<PoseData> computedPoseRef = new AtomicReference<>();

        Task<List<ImageResult>> searchTask = new Task<>() {
            @Override
            protected List<ImageResult> call() {
                PoseData pose;
                if (reuseablePose != null) {
                    // Reutilizar pose: no hace falta volver a analizar el canvas
                    pose = reuseablePose;
                } else {
                    pose = DrawingProcessor.processImage(snapshot);
                }
                computedPoseRef.set(pose);

                if (pose != null && !pose.getAllJoints().isEmpty()) {
                    List<String> terms = SearchTermGenerator.generateTerms(pose);
                    final int partCount = countMeaningfulJoints(pose);
                    if (isFreshSearch) {
                        currentSearchId = DatabaseManager.saveDrawing(pose, terms, null);
                    }
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

            if (isFreshSearch) {
                // Búsqueda nueva: reemplazar galería por completo
                galleryPane.getChildren().clear();
                displayResults(results);
            } else {
                // Continuación: agregar nuevos resultados a los existentes (sin duplicar)
                Set<String> existingPaths = galleryPane.getChildren().stream()
                    .map(n -> (String) n.getUserData())
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
                List<ImageResult> newResults = results.stream()
                    .filter(r -> !existingPaths.contains(r.getThumbnailUrl()))
                    .collect(java.util.stream.Collectors.toList());
                displayResults(newResults);
            }

            setSearchProgress(false, -1);

            if (galleryPane.getChildren().isEmpty()) {
                updateGalleryEmptyState(true);
                statusLabel.setText(buildEmptySearchMessage());
            } else {
                // Calcular el mejor score de TODOS los resultados visibles en galería
                long total = galleryPane.getChildren().size();
                int newCount = results.size();
                double best = results.stream().mapToDouble(ImageResult::getScore).filter(s -> s >= 0).max().orElse(0);
                if (isFreshSearch) {
                    statusLabel.setText(I18n.fmt("status.searchDone", total, Math.round(best * 100)));
                } else {
                    statusLabel.setText(I18n.isEnglish()
                        ? "✓ +" + newCount + " more results · " + total + " total"
                        : "✓ +" + newCount + " resultados más · " + total + " en total");
                }
                if (currentSearchId != -1) {
                    DatabaseManager.saveResults(currentSearchId, results);
                }
            }
            // Sonido de finalización
            org.refcolor.buscareferencias.utils.SoundUtils.playSearchDing();
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
            HBox card = buildGalleryCard(result, rank++);
            card.setUserData(result.getThumbnailUrl());  // usado para deduplicación en continue-search
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

    private HBox buildGalleryCard(ImageResult result, int rank) {
        // ── Tarjeta de imagen (lado izquierdo) ────────────────────────────────
        VBox imageCard = new VBox(5);
        imageCard.getStyleClass().add("image-card");
        imageCard.setAlignment(Pos.CENTER);
        imageCard.setPrefWidth(currentGalleryImageSize + 10);
        imageCard.setPrefHeight(currentGalleryImageSize + 56);

        Label rankBadge = new Label(String.valueOf(rank));
        rankBadge.getStyleClass().add("gallery-rank");

        StackPane imageStack = new StackPane(buildCardImageView(result), rankBadge);
        StackPane.setAlignment(rankBadge, Pos.TOP_LEFT);
        imageStack.setClip(new Rectangle(currentGalleryImageSize, currentGalleryImageSize));

        imageCard.getChildren().addAll(imageStack, buildScoreLabel(result, rank));
        imageCard.setCursor(Cursor.HAND);
        imageCard.setOnMouseClicked(e -> showImageDetailDialog(result, rank));

        // ── Panel de análisis (lado derecho, oculto al inicio) ────────────────
        VBox infoPanel = buildPosePopup(result);
        infoPanel.setOpacity(0);

        // El wrapper ocupa CERO espacio de layout (para no desplazar el FlowPane),
        // pero visualmente se renderiza a través del clip, superponiéndose sobre
        // las tarjetas vecinas. Esto evita el bucle enter/exit del último elemento.
        StackPane infoPanelWrapper = new StackPane(infoPanel);
        infoPanelWrapper.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        infoPanelWrapper.setPrefWidth(0);
        infoPanelWrapper.setMinWidth(0);
        infoPanelWrapper.setMaxWidth(0);  // siempre 0 — nunca afecta al layout
        Rectangle panelClip = new Rectangle(0, 600);
        infoPanelWrapper.setClip(panelClip);

        // ── Contenedor HBox (ocupa el espacio en el FlowPane) ─────────────────
        HBox container = new HBox(0, imageCard, infoPanelWrapper);
        container.setAlignment(javafx.geometry.Pos.TOP_LEFT);

        final double INFO_W = 262.0;
        // Referencia a la animación activa para poder cancelarla si el ratón
        // entra/sale rápidamente
        final Timeline[] active = {null};

        container.setOnMouseEntered(e -> {
            if (active[0] != null) active[0].stop();
            container.setViewOrder(-1);  // renderizar por encima de las vecinas
            double curClip = panelClip.getWidth();
            double curOp   = infoPanel.getOpacity();
            double curTY   = imageCard.getTranslateY();
            Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                    new KeyValue(panelClip.widthProperty(),      curClip),
                    new KeyValue(infoPanel.opacityProperty(),    curOp),
                    new KeyValue(imageCard.translateYProperty(), curTY)),
                new KeyFrame(Duration.millis(210),
                    new KeyValue(panelClip.widthProperty(),      INFO_W, Interpolator.EASE_OUT),
                    new KeyValue(infoPanel.opacityProperty(),    1.0,    Interpolator.EASE_OUT),
                    new KeyValue(imageCard.translateYProperty(), -4.0,   Interpolator.EASE_OUT))
            );
            active[0] = tl;
            tl.play();
        });

        container.setOnMouseExited(e -> {
            if (active[0] != null) active[0].stop();
            double curClip = panelClip.getWidth();
            double curOp   = infoPanel.getOpacity();
            double curTY   = imageCard.getTranslateY();
            Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                    new KeyValue(panelClip.widthProperty(),      curClip),
                    new KeyValue(infoPanel.opacityProperty(),    curOp),
                    new KeyValue(imageCard.translateYProperty(), curTY)),
                new KeyFrame(Duration.millis(170),
                    new KeyValue(panelClip.widthProperty(),      0,   Interpolator.EASE_IN),
                    new KeyValue(infoPanel.opacityProperty(),    0.0, Interpolator.EASE_IN),
                    new KeyValue(imageCard.translateYProperty(), 0.0, Interpolator.EASE_OUT))
            );
            tl.setOnFinished(ev -> container.setViewOrder(0));
            active[0] = tl;
            tl.play();
        });

        return container;
    }

    /**
     * Construye el panel de análisis de pose que aparece a la derecha de la
     * tarjeta al hacer hover, expandiéndose inline y empujando las demás fotos.
     */
    private VBox buildPosePopup(ImageResult result) {
        VBox box = new VBox(8);
        box.setMinWidth(248);
        box.setMaxWidth(248);
        box.setPrefWidth(248);
        // Mismo fondo oscuro que la tarjeta, borde izquierdo como separador
        box.setStyle(
            "-fx-background-color: rgba(10,22,38,0.98);" +
            "-fx-background-radius: 0 10 10 0;" +
            "-fx-border-color: rgba(140,195,240,0.30) rgba(140,195,240,0.18) rgba(140,195,240,0.18) rgba(100,160,220,0.45);" +
            "-fx-border-width: 1 1 1 2;" +
            "-fx-border-radius: 0 10 10 0;" +
            "-fx-padding: 14 16 14 14;");

        SimilarityBreakdown bd = result.getScoreBreakdown();

        // ── Título ──────────────────────────────────────────────────────────
        Label title = new Label(I18n.t("pose.analysis"));
        title.setStyle("-fx-text-fill: #B8D4EC; -fx-font-size: 12px; -fx-font-weight: bold;");
        box.getChildren().add(title);

        // ── Separador ───────────────────────────────────────────────────────
        javafx.scene.layout.Region sep = new javafx.scene.layout.Region();
        sep.setPrefHeight(1);
        sep.setMaxWidth(Double.MAX_VALUE);
        sep.setStyle("-fx-background-color: rgba(140,195,240,0.20);");
        box.getChildren().add(sep);

        if (bd == null || !bd.hasData()) {
            Label noData = new Label(I18n.t("pose.nodata"));
            noData.setStyle("-fx-text-fill: #556677; -fx-font-size: 12px;");
            box.getChildren().add(noData);
            return box;
        }

        // ── Filas de score ───────────────────────────────────────────────────
        if (bd.hasEmbeddings()) {
            box.getChildren().add(poseScoreRowLarge(I18n.t("pose.embedding"), bd.cosine(), "#18CC96"));
        }
        box.getChildren().add(poseScoreRowLarge(I18n.t("pose.angles"),    bd.angles(),    colorForScore(bd.angles())));
        if (bd.hasPositions()) {
            box.getChildren().add(poseScoreRowLarge(I18n.t("pose.positions"), bd.positions(), colorForScore(bd.positions())));
        }
        box.getChildren().add(poseScoreRowLarge(I18n.t("pose.skeleton"),  bd.skeleton(),  colorForScore(bd.skeleton())));
        box.getChildren().add(poseScoreRowLarge(I18n.t("pose.contour"),   bd.contour(),   colorForScore(bd.contour())));
        if (bd.coverage() < 0.97) {
            box.getChildren().add(poseScoreRowLarge(I18n.t("pose.coverage"), bd.coverage(), colorForScore(bd.coverage())));
        }

        // ── Pie: landmarks / joints ─────────────────────────────────────────
        javafx.scene.layout.Region sep2 = new javafx.scene.layout.Region();
        sep2.setPrefHeight(1);
        sep2.setMaxWidth(Double.MAX_VALUE);
        sep2.setStyle("-fx-background-color: rgba(140,195,240,0.14);");
        box.getChildren().add(sep2);

        Label pts = new Label("📍 " + bd.landmarkCount() + " " + I18n.t("pose.points")
                + "   ✏ " + bd.jointCount() + " " + I18n.t("pose.joints"));
        pts.setStyle("-fx-text-fill: #4A6880; -fx-font-size: 11px;");
        box.getChildren().add(pts);

        return box;
    }

    /** Fila de métrica para el popup: [nombre 76px] [barra 84px] [% 38px]. */
    private HBox poseScoreRowLarge(String name, double score, String color) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Label nameLbl = new Label(name);
        nameLbl.setMinWidth(76);
        nameLbl.setStyle("-fx-text-fill: #8AACC4; -fx-font-size: 12px;");

        double pct  = Math.max(0.0, Math.min(1.0, score));
        double barW = 84.0;
        javafx.scene.shape.Rectangle bg = new javafx.scene.shape.Rectangle(barW, 7);
        bg.setFill(javafx.scene.paint.Color.web("rgba(255,255,255,0.10)"));
        bg.setArcWidth(5); bg.setArcHeight(5);

        javafx.scene.shape.Rectangle fill = new javafx.scene.shape.Rectangle(Math.max(3, barW * pct), 7);
        fill.setFill(javafx.scene.paint.Color.web(color));
        fill.setArcWidth(5); fill.setArcHeight(5);

        StackPane bar = new StackPane(bg, fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);

        Label pctLbl = new Label(Math.round(pct * 100) + "%");
        pctLbl.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        pctLbl.setMinWidth(38);

        row.getChildren().addAll(nameLbl, bar, pctLbl);
        return row;
    }

    /** Elige un color tráfico según el score: verde > 0.65, ámbar > 0.45, rojo. */
    private static String colorForScore(double score) {
        if (score >= 0.65) return "#7DC9B0";   // teal — bueno
        if (score >= 0.45) return "#D4A064";   // ámbar — medio
        return "#E07070";                       // rojo  — bajo
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

    /** Resuelve la URL de visualización de la imagen (preferencia: displayThumbnailUrl). */
    private static String resolveDisplayUrl(ImageResult result) {
        String url = result.getDisplayThumbnailUrl();
        if (url == null || url.isBlank()) url = result.getThumbnailUrl();
        if (url == null || url.isBlank()) url = result.getOriginalUrl();
        return url;
    }

    // ── Diálogo de detalle de imagen ──────────────────────────────────────────

    private void showImageDetailDialog(ImageResult result, int rank) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(canvas.getScene().getWindow());
        dialog.setTitle(I18n.fmt("detail.title", rank));
        dialog.setMinWidth(700);
        dialog.setMinHeight(540);

        VBox root = new VBox(14);
        root.setPadding(new javafx.geometry.Insets(18));
        root.setStyle("-fx-background-color: #0A1626;");

        // ── Título con rango y score ───────────────────────────────────────────
        String scoreText = result.getScore() > 0
                ? String.format("  ·  %.0f%%", result.getScore() * 100) : "";
        Label titleLabel = new Label(I18n.fmt("detail.title", rank) + scoreText);
        titleLabel.setStyle("-fx-text-fill: #B8D4EC; -fx-font-size: 15px; -fx-font-weight: bold;");

        // ── Área de imagen (con toggle foto/esqueleto) ────────────────────────
        double dispW = 480, dispH = 540;
        PoseData poseData = result.getPoseData();
        String imageUrl = resolveDisplayUrl(result);
        // Para el fondo del esqueleto se prefiere la imagen original (mayor resolución)
        String skeletonUrl = result.getOriginalUrl() != null && !result.getOriginalUrl().isBlank()
                ? result.getOriginalUrl() : imageUrl;

        ToggleButton btnPhoto    = new ToggleButton(I18n.t("detail.photo"));
        ToggleButton btnSkeleton = new ToggleButton(I18n.t("detail.skeleton"));
        ToggleGroup viewGroup = new ToggleGroup();
        btnPhoto.setToggleGroup(viewGroup);
        btnSkeleton.setToggleGroup(viewGroup);
        btnPhoto.setSelected(true);
        boolean hasPose = poseData != null && !poseData.getAllLandmarks().isEmpty();
        btnSkeleton.setDisable(!hasPose);

        String toggleStyle = "-fx-font-size: 12px; -fx-padding: 5 12;";
        btnPhoto.setStyle(toggleStyle);
        btnSkeleton.setStyle(toggleStyle);

        ImageView imageView = new ImageView();
        imageView.setFitWidth(dispW);
        imageView.setFitHeight(dispH);
        imageView.setPreserveRatio(true);
        if (imageUrl != null) {
            Image img = new Image(imageUrl, dispW, dispH, true, true, true);
            imageView.setImage(img);
        }

        Canvas skeletonCanvas = new Canvas(dispW, dispH);
        skeletonCanvas.setVisible(false);
        if (hasPose) {
            loadAndDrawSkeleton(skeletonCanvas, skeletonUrl, poseData, dispW, dispH);
        }

        StackPane displayPane = new StackPane(imageView, skeletonCanvas);
        displayPane.setPrefWidth(dispW);
        displayPane.setPrefHeight(dispH);
        displayPane.setMaxWidth(dispW);
        displayPane.setStyle("-fx-background-color: #000000;");

        viewGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            boolean showSkel = (now == btnSkeleton);
            imageView.setVisible(!showSkel);
            skeletonCanvas.setVisible(showSkel);
        });

        HBox toggleRow = new HBox(8, btnPhoto, btnSkeleton);
        VBox imageSection = new VBox(8, toggleRow, displayPane);

        // ── Panel de desglose de scores ───────────────────────────────────────
        VBox scorePanel = buildDetailScorePanel(result);
        scorePanel.setMinWidth(230);
        scorePanel.setPrefWidth(250);

        HBox content = new HBox(16, imageSection, scorePanel);
        content.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        VBox.setVgrow(content, Priority.ALWAYS);

        // ── Botones de acción ─────────────────────────────────────────────────
        Button btnOpen = new Button(I18n.t("detail.openFile"));
        btnOpen.setOnAction(e -> openResultSource(result));
        btnOpen.setStyle(
            "-fx-background-color: #1A3A5C;" +
            "-fx-text-fill: #7EC8E3;" +
            "-fx-font-size: 12px;" +
            "-fx-padding: 7 16;" +
            "-fx-background-radius: 6;" +
            "-fx-cursor: hand;");

        Button btnClose = new Button(I18n.t("detail.close"));
        btnClose.setOnAction(e -> dialog.close());
        btnClose.setStyle(
            "-fx-background-color: #1C2A3A;" +
            "-fx-text-fill: #607080;" +
            "-fx-font-size: 12px;" +
            "-fx-padding: 7 16;" +
            "-fx-background-radius: 6;" +
            "-fx-cursor: hand;");

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(10, spacer, btnOpen, btnClose);
        bottomBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        root.getChildren().addAll(titleLabel, content, bottomBar);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(javafx.scene.paint.Color.web("#0A1626"));
        dialog.setScene(scene);
        dialog.show();
    }

    /**
     * Construye el panel de desglose de scores para el diálogo de detalle.
     */
    private VBox buildDetailScorePanel(ImageResult result) {
        VBox box = new VBox(9);
        box.setStyle(
            "-fx-background-color: rgba(10,22,38,0.95);" +
            "-fx-background-radius: 8;" +
            "-fx-border-color: rgba(100,160,220,0.30);" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 8;" +
            "-fx-padding: 14 14 14 14;");

        Label title = new Label(I18n.t("pose.analysis"));
        title.setStyle("-fx-text-fill: #B8D4EC; -fx-font-size: 12px; -fx-font-weight: bold;");
        box.getChildren().add(title);

        javafx.scene.layout.Region sep = new javafx.scene.layout.Region();
        sep.setPrefHeight(1); sep.setMaxWidth(Double.MAX_VALUE);
        sep.setStyle("-fx-background-color: rgba(100,160,220,0.22);");
        box.getChildren().add(sep);

        SimilarityBreakdown bd = result.getScoreBreakdown();
        if (bd == null || !bd.hasData()) {
            Label noData = new Label(I18n.t("pose.nodata"));
            noData.setStyle("-fx-text-fill: #445566; -fx-font-size: 12px;");
            box.getChildren().add(noData);
            return box;
        }

        if (bd.hasEmbeddings()) {
            box.getChildren().add(detailScoreRow(I18n.t("pose.embedding"), bd.cosine(),   "#18CC96"));
        }
        box.getChildren().add(detailScoreRow(I18n.t("pose.angles"),    bd.angles(),    colorForScore(bd.angles())));
        if (bd.hasPositions()) {
            box.getChildren().add(detailScoreRow(I18n.t("pose.positions"), bd.positions(), colorForScore(bd.positions())));
        }
        box.getChildren().add(detailScoreRow(I18n.t("pose.skeleton"),  bd.skeleton(),  colorForScore(bd.skeleton())));
        box.getChildren().add(detailScoreRow(I18n.t("pose.contour"),   bd.contour(),   colorForScore(bd.contour())));
        if (bd.coverage() < 0.97) {
            box.getChildren().add(detailScoreRow(I18n.t("pose.coverage"), bd.coverage(), colorForScore(bd.coverage())));
        }

        javafx.scene.layout.Region sep2 = new javafx.scene.layout.Region();
        sep2.setPrefHeight(1); sep2.setMaxWidth(Double.MAX_VALUE);
        sep2.setStyle("-fx-background-color: rgba(100,160,220,0.14);");
        box.getChildren().add(sep2);

        Label pts = new Label("📍 " + bd.landmarkCount() + " " + I18n.t("pose.points")
                + "   ✏ " + bd.jointCount() + " " + I18n.t("pose.joints"));
        pts.setStyle("-fx-text-fill: #3D5468; -fx-font-size: 11px;");
        box.getChildren().add(pts);

        return box;
    }

    /** Fila de métrica para el diálogo de detalle. */
    private HBox detailScoreRow(String name, double score, String color) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        Label nameLbl = new Label(name);
        nameLbl.setMinWidth(80);
        nameLbl.setStyle("-fx-text-fill: #8AACC4; -fx-font-size: 12px;");

        double pct  = Math.max(0.0, Math.min(1.0, score));
        double barW = 100.0;
        javafx.scene.shape.Rectangle bg = new javafx.scene.shape.Rectangle(barW, 8);
        bg.setFill(javafx.scene.paint.Color.web("rgba(255,255,255,0.08)"));
        bg.setArcWidth(5); bg.setArcHeight(5);

        javafx.scene.shape.Rectangle fill = new javafx.scene.shape.Rectangle(Math.max(3, barW * pct), 8);
        fill.setFill(javafx.scene.paint.Color.web(color));
        fill.setArcWidth(5); fill.setArcHeight(5);

        StackPane bar = new StackPane(bg, fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);

        Label pctLbl = new Label(Math.round(pct * 100) + "%");
        pctLbl.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        pctLbl.setMinWidth(38);

        row.getChildren().addAll(nameLbl, bar, pctLbl);
        return row;
    }

    /**
     * Carga la imagen en un hilo de fondo y dibuja el esqueleto MediaPipe sobre el Canvas.
     */
    private void loadAndDrawSkeleton(Canvas skeletonCanvas, String imageUrl,
                                     PoseData poseData, double canvasW, double canvasH) {
        new Thread(() -> {
            Image img = null;
            if (imageUrl != null && !imageUrl.isBlank()) {
                try {
                    img = new Image(imageUrl, canvasW, canvasH, true, true);
                } catch (Exception ex) {
                    logger.warn("[SKELETON] Error cargando imagen: {}", ex.getMessage());
                }
            }
            final Image finalImg = img;
            Platform.runLater(() -> drawSkeletonOnCanvas(skeletonCanvas, finalImg, poseData, canvasW, canvasH));
        }, "skeleton-loader").start();
    }

    /**
     * Renderiza la imagen de referencia con el esqueleto MediaPipe superpuesto,
     * usando colores auténticos por región corporal (izquierda=verde, derecha=rojo, centro=blanco)
     * y transparencia ponderada por la visibilidad de cada landmark.
     */
    private void drawSkeletonOnCanvas(Canvas canvas, Image img, PoseData poseData,
                                      double canvasW, double canvasH) {
        GraphicsContext sgc = canvas.getGraphicsContext2D();
        sgc.setFill(javafx.scene.paint.Color.BLACK);
        sgc.fillRect(0, 0, canvasW, canvasH);

        // Calcular letterbox fit
        double ox = 0, oy = 0, drawW = canvasW, drawH = canvasH;
        if (img != null && !img.isError() && img.getWidth() > 0 && img.getHeight() > 0) {
            double scale = Math.min(canvasW / img.getWidth(), canvasH / img.getHeight());
            drawW = img.getWidth() * scale;
            drawH = img.getHeight() * scale;
            ox = (canvasW - drawW) / 2.0;
            oy = (canvasH - drawH) / 2.0;
            sgc.drawImage(img, ox, oy, drawW, drawH);
        }

        // Superposición oscura semi-transparente para hacer destacar el esqueleto
        sgc.setFill(javafx.scene.paint.Color.color(0, 0, 0, 0.42));
        sgc.fillRect(0, 0, canvasW, canvasH);

        Map<Integer, javafx.geometry.Point2D> landmarks = poseData.getAllLandmarks();
        if (landmarks.isEmpty()) return;

        boolean hasVis = poseData.hasVisibilityData();

        // Dibujar conexiones con colores por región corporal (auténtico MediaPipe)
        for (int[] conn : SKELETON_CONNECTIONS) {
            javafx.geometry.Point2D a = landmarks.get(conn[0]);
            javafx.geometry.Point2D b = landmarks.get(conn[1]);
            if (a == null || b == null) continue;

            double visA = hasVis ? poseData.getLandmarkVisibility(conn[0]) : 1.0;
            double visB = hasVis ? poseData.getLandmarkVisibility(conn[1]) : 1.0;
            if (visA < 0) visA = 1.0;
            if (visB < 0) visB = 1.0;
            double vis = (visA + visB) / 2.0;
            if (vis < 0.15) continue;

            double ax = ox + a.getX() * drawW, ay = oy + a.getY() * drawH;
            double bx = ox + b.getX() * drawW, by = oy + b.getY() * drawH;

            // Sombra negra gruesa para contraste
            sgc.setStroke(javafx.scene.paint.Color.color(0, 0, 0, Math.min(1.0, vis * 0.75)));
            sgc.setLineWidth(5.0);
            sgc.strokeLine(ax, ay, bx, by);

            // Línea de color (mezcla del color de ambos extremos)
            javafx.scene.paint.Color lineColor = blendColors(
                    withAlpha(getLandmarkColor(conn[0]), vis),
                    withAlpha(getLandmarkColor(conn[1]), vis));
            sgc.setStroke(lineColor);
            sgc.setLineWidth(2.5);
            sgc.strokeLine(ax, ay, bx, by);
        }

        // Dibujar nodos articulares con colores por región corporal
        for (Map.Entry<Integer, javafx.geometry.Point2D> entry : landmarks.entrySet()) {
            int id = entry.getKey();
            javafx.geometry.Point2D lm = entry.getValue();
            double x = ox + lm.getX() * drawW;
            double y = oy + lm.getY() * drawH;

            double vis = hasVis ? poseData.getLandmarkVisibility(id) : 1.0;
            if (vis < 0) vis = 1.0;
            if (vis < 0.15) continue;

            javafx.scene.paint.Color dotColor = withAlpha(getLandmarkColor(id), vis);
            double r = 5.0;

            // Halo negro para contraste
            sgc.setFill(javafx.scene.paint.Color.color(0, 0, 0, Math.min(1.0, vis * 0.80)));
            sgc.fillOval(x - r - 2, y - r - 2, (r + 2) * 2, (r + 2) * 2);

            // Disco de color exterior
            sgc.setFill(dotColor);
            sgc.fillOval(x - r, y - r, r * 2, r * 2);

            // Centro blanco pequeño
            sgc.setFill(javafx.scene.paint.Color.WHITE);
            sgc.fillOval(x - 2, y - 2, 4, 4);
        }
    }

    /** Devuelve el color MediaPipe para el landmark dado (izquierda=verde, derecha=rojo, centro=blanco). */
    private static javafx.scene.paint.Color getLandmarkColor(int id) {
        if (SKEL_LEFT_IDS.contains(id))  return SKEL_LEFT;
        if (SKEL_RIGHT_IDS.contains(id)) return SKEL_RIGHT;
        return SKEL_CENTER;
    }

    /** Devuelve un color con alpha ajustado al valor de visibilidad dado (mínimo 0.25). */
    private static javafx.scene.paint.Color withAlpha(javafx.scene.paint.Color c, double alpha) {
        return new javafx.scene.paint.Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.max(0.25, Math.min(1.0, alpha)));
    }

    /** Mezcla dos colores a partes iguales, promediando también el alpha. */
    private static javafx.scene.paint.Color blendColors(javafx.scene.paint.Color c1, javafx.scene.paint.Color c2) {
        return new javafx.scene.paint.Color(
                (c1.getRed()     + c2.getRed())     / 2.0,
                (c1.getGreen()   + c2.getGreen())   / 2.0,
                (c1.getBlue()    + c2.getBlue())    / 2.0,
                (c1.getOpacity() + c2.getOpacity()) / 2.0);
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

    // ── Toolbar de usuario ────────────────────────────────────────────────────

    /**
     * Añade dinámicamente al toolbar los botones de usuario, historial,
     * ajustes y logout. Se llaman desde initialize().
     */
    private void addUserToolbarButtons() {
        if (toolBar == null) return;

        javafx.scene.control.Separator sep = new javafx.scene.control.Separator(
                javafx.geometry.Orientation.VERTICAL);

        // Nombre del usuario activo
        var session = org.refcolor.buscareferencias.auth.UserManager.getCurrentSession();
        String userName = (session != null) ? session.username() : "Invitado";
        Label lblUser = new Label("👤 " + userName);
        lblUser.setStyle("-fx-text-fill: #BDC7C9; -fx-font-size: 12px; -fx-padding: 0 4;");

        // Botón Historial
        Button btnHistory = new Button("📋");
        btnHistory.setStyle("-fx-background-color: transparent; -fx-text-fill: #BDC7C9; " +
                            "-fx-font-size: 14px; -fx-cursor: hand;");
        btnHistory.setTooltip(new javafx.scene.control.Tooltip(I18n.t("tooltip.history")));
        btnHistory.setOnAction(e -> openHistoryScreen());

        // Botón Ajustes
        Button btnSettingsBtn = new Button("⚙");
        btnSettingsBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #BDC7C9; " +
                                "-fx-font-size: 14px; -fx-cursor: hand;");
        btnSettingsBtn.setTooltip(new javafx.scene.control.Tooltip(I18n.t("tooltip.settings")));
        btnSettingsBtn.setOnAction(e -> openSettingsScreen());

        // Botón Guardar dibujo
        Button btnSaveDraw = new Button("💾");
        btnSaveDraw.setStyle("-fx-background-color: transparent; -fx-text-fill: #BDC7C9; " +
                             "-fx-font-size: 14px; -fx-cursor: hand;");
        btnSaveDraw.setTooltip(new javafx.scene.control.Tooltip(I18n.t("tooltip.saveDrawing")));
        btnSaveDraw.setOnAction(e -> saveCurrentDrawingToHistory());

        // Botón Logout (solo si no es invitado)
        Button btnLogout = new Button("⏏");
        btnLogout.setStyle("-fx-background-color: transparent; -fx-text-fill: #6B8FA3; " +
                           "-fx-font-size: 14px; -fx-cursor: hand;");
        btnLogout.setTooltip(new javafx.scene.control.Tooltip(I18n.t("tooltip.logout")));
        btnLogout.setOnAction(e -> handleLogout());

        toolBar.getItems().addAll(sep, lblUser, btnSaveDraw, btnHistory, btnSettingsBtn, btnLogout);
    }

    private void openHistoryScreen() {
        try {
            // Guardar Stage ANTES de reemplazar el root (canvas.getScene() pasa a null tras setRoot)
            savedStage = (javafx.stage.Stage) canvas.getScene().getWindow();
            var loader = new javafx.fxml.FXMLLoader(
                org.refcolor.buscareferencias.BuscaReferenciasApp.class.getResource("history-view.fxml"));
            javafx.scene.Parent root = loader.load();
            HistoryController ctrl = loader.getController();
            ctrl.setStage(savedStage);
            ctrl.setOnBack(this::returnFromSubscreen);
            savedStage.getScene().setRoot(root);
        } catch (Exception e) {
            logger.error("[UI] Error abriendo historial", e);
        }
    }

    private void openSettingsScreen() {
        try {
            // Guardar Stage ANTES de reemplazar el root
            savedStage = (javafx.stage.Stage) canvas.getScene().getWindow();
            var loader = new javafx.fxml.FXMLLoader(
                org.refcolor.buscareferencias.BuscaReferenciasApp.class.getResource("settings-view.fxml"));
            javafx.scene.Parent root = loader.load();
            SettingsController ctrl = loader.getController();
            ctrl.setStage(savedStage);
            ctrl.setOnBack(this::returnFromSubscreen);
            savedStage.getScene().setRoot(root);
        } catch (Exception e) {
            logger.error("[UI] Error abriendo ajustes", e);
        }
    }

    private void returnFromSubscreen() {
        // Volver a la pantalla principal recargándola (usa savedStage porque canvas.getScene() es null)
        try {
            var loader = new javafx.fxml.FXMLLoader(
                org.refcolor.buscareferencias.BuscaReferenciasApp.class.getResource("main-view.fxml"));
            javafx.scene.Parent root = loader.load();
            DrawingController ctrl = loader.getController();
            if (ctrl != null) ctrl.setOnLogout(this.onLogout);
            if (savedStage != null) {
                savedStage.getScene().setRoot(root);
            } else {
                // Fallback: si savedStage es null, intentar desde el nodo de ajustes
                logger.warn("[UI] savedStage es null en returnFromSubscreen");
            }
        } catch (Exception e) {
            logger.error("[UI] Error volviendo a main", e);
        }
    }

    private void handleLogout() {
        org.refcolor.buscareferencias.auth.UserManager.logout();
        if (onLogout != null) Platform.runLater(onLogout);
    }

    /**
     * Guarda el estado actual del canvas como snapshot PNG en el directorio
     * de la app y registra el dibujo en el historial de la BD.
     */
    private void saveCurrentDrawingToHistory() {
        if (!canvasHasContent) {
            statusLabel.setText("El lienzo está vacío — dibuja algo primero.");
            return;
        }
        // Capturar snapshot del canvas
        javafx.scene.image.WritableImage snap = new javafx.scene.image.WritableImage(
                (int) canvas.getWidth(), (int) canvas.getHeight());
        canvas.snapshot(null, snap);

        new Thread(() -> {
            try {
                java.nio.file.Path snapshotDir = java.nio.file.Paths.get(
                    System.getProperty("user.home"), ".buscareferencias", "snapshots");
                java.nio.file.Files.createDirectories(snapshotDir);
                String fileName = "draw_" + System.currentTimeMillis() + ".png";
                java.nio.file.Path snapshotFile = snapshotDir.resolve(fileName);

                // Guardar imagen como PNG usando PixelReader (sin SwingFXUtils)
                writePng(snap, snapshotFile.toFile());

                // Registrar en BD
                int userId = org.refcolor.buscareferencias.auth.UserManager.getCurrentUserId();
                String poseJson = lastAnalyzedPose != null ? lastAnalyzedPose.getJointsJson() : "{}";
                // Insertar dibujo en BD y obtener id
                int idDibujo = org.refcolor.buscareferencias.database.DatabaseManager
                    .saveDrawing(lastAnalyzedPose != null ? lastAnalyzedPose
                               : new org.refcolor.buscareferencias.model.PoseData(),
                               java.util.List.of(), java.util.List.of());
                if (idDibujo > 0) {
                    org.refcolor.buscareferencias.database.DatabaseManager
                        .saveSnapshotPath(idDibujo, snapshotFile.toAbsolutePath().toString());
                    org.refcolor.buscareferencias.database.DatabaseManager
                        .incrementUserStats(userId, false);
                }

                Platform.runLater(() ->
                    statusLabel.setText("✓ Dibujo guardado en el historial."));
            } catch (Exception e) {
                logger.error("[DRAW] Error guardando snapshot", e);
                Platform.runLater(() ->
                    statusLabel.setText("Error al guardar el dibujo."));
            }
        }, "save-snapshot").start();
    }

    /**
     * Escribe un WritableImage de JavaFX como archivo PNG sin usar SwingFXUtils.
     * Usa PixelReader + javax.imageio directamente sobre un BufferedImage construido
     * manualmente con el tipo TYPE_INT_ARGB.
     */
    private static void writePng(javafx.scene.image.WritableImage img, java.io.File dest)
            throws java.io.IOException {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        javafx.scene.image.PixelReader pr = img.getPixelReader();
        // TYPE_INT_ARGB = 2
        java.awt.image.BufferedImage bi =
            new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = pr.getArgb(x, y);
                bi.setRGB(x, y, argb);
            }
        }
        javax.imageio.ImageIO.write(bi, "png", dest);
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
