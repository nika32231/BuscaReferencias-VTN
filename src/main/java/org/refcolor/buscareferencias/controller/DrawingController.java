package org.refcolor.buscareferencias.controller;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.service.SearchService;
import org.refcolor.buscareferencias.utils.DrawingProcessor;
import org.refcolor.buscareferencias.utils.ProjectPaths;
import org.refcolor.buscareferencias.utils.SearchTermGenerator;

public class DrawingController {

    private static final Logger logger = LoggerFactory.getLogger(DrawingController.class);

    private static final double LINE_WIDTH = 5.0;
    private static final Color CANVAS_FRAME_COLOR = Color.web("#b0b7c3");
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
    @FXML private StackPane canvasContainer;
    @FXML private StackPane canvasRoot;
    @FXML private SplitPane canvasGallerySplitPane;
    @FXML private FlowPane galleryPane;

    private GraphicsContext gc;
    private AnatomyPart currentPart = AnatomyPart.HEAD;
    private double lastX, lastY;

    private final Deque<WritableImage> undoStack = new ArrayDeque<>();
    private final Deque<WritableImage> redoStack = new ArrayDeque<>();

    private ToggleGroup toolGroup;
    private PoseData lastAnalyzedPose;
    private HostServices hostServices;
    private int currentSearchId = -1;

    private boolean resizingCanvas = false;
    private double resizeStartX, resizeStartY, resizeStartW, resizeStartH;

    private static final double CANVAS_INITIAL_W = 900;
    private static final double CANVAS_INITIAL_H = 600;
    private static final double CANVAS_MIN_W = 500;
    private static final double CANVAS_MIN_H = 350;
    private static final double CANVAS_MAX_W = 1600;
    private static final double CANVAS_MAX_H = 1200;
    private static final double BREAKPOINT_COMPACT = 1180;
    private static final double BREAKPOINT_PHONE = 820;
    private static final double CANVAS_VIEW_PADDING = 24;

    private boolean responsiveListenersAttached = false;
    private double currentGalleryImageSize = 150;

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

        logger.info("[UI] DrawingController.initialize() end en {} ms", Duration.between(t0, Instant.now()).toMillis());
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
                statusLabel.setText("Lienzo: " + (int) canvas.getWidth() + "x" + (int) canvas.getHeight());
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

        boolean compact = sceneWidth < BREAKPOINT_COMPACT;
        boolean phone = sceneWidth < BREAKPOINT_PHONE;
        boolean touchMode = phone || sceneHeight < 620;

        rootPane.getStyleClass().removeAll("compact-mode", "phone-mode", "touch-mode");
        if (compact) rootPane.getStyleClass().add("compact-mode");
        if (phone) rootPane.getStyleClass().add("phone-mode");
        if (touchMode) rootPane.getStyleClass().add("touch-mode");

        if (sidePalette != null) {
            if (phone) {
                sidePalette.setPrefWidth(136);
                sidePalette.setMinWidth(112);
            } else if (compact) {
                sidePalette.setPrefWidth(160);
                sidePalette.setMinWidth(136);
            } else {
                sidePalette.setPrefWidth(200);
                sidePalette.setMinWidth(180);
            }
        }

        if (canvasGallerySplitPane != null) {
            canvasGallerySplitPane.setDividerPositions(phone ? 0.48 : 0.55);
        }
    }

    private void clearToWhite() {
        gc.setFill(Color.WHITE);
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
        canvasRoot.widthProperty().addListener((obs, oldV, newV) -> updateCanvasScaleToViewport());
        canvasRoot.heightProperty().addListener((obs, oldV, newV) -> updateCanvasScaleToViewport());
        canvas.widthProperty().addListener((obs, oldV, newV) -> updateCanvasScaleToViewport());
        canvas.heightProperty().addListener((obs, oldV, newV) -> updateCanvasScaleToViewport());
        updateCanvasScaleToViewport();
    }

    private void updateCanvasScaleToViewport() {
        if (canvasRoot == null || canvasContainer == null || canvas == null) return;
        double availableW = Math.max(120, canvasRoot.getWidth() - CANVAS_VIEW_PADDING);
        double availableH = Math.max(120, canvasRoot.getHeight() - CANVAS_VIEW_PADDING);
        double scale = Math.min(availableW / Math.max(1, canvas.getWidth()), availableH / Math.max(1, canvas.getHeight()));
        scale = clamp(scale, 0.35, 1.8);
        canvasContainer.setScaleX(scale);
        canvasContainer.setScaleY(scale);
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

        double target = sceneWidth < BREAKPOINT_PHONE ? 120 : (sceneWidth < BREAKPOINT_COMPACT ? 136 : 150);
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
    }

    private void setupPalette() {
        ToggleGroup paletteGroup = new ToggleGroup();
        for (AnatomyPart part : AnatomyPart.values()) {
            ToggleButton colorBtn = new ToggleButton(part.getName());
            colorBtn.setToggleGroup(paletteGroup);
            colorBtn.getStyleClass().add("palette-button");

            // Swatch circular más grande y suave
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(10, Color.web(part.getHexColor()));
            dot.setStroke(Color.rgb(255, 255, 255, 0.18));
            dot.setStrokeWidth(1.5);
            colorBtn.setGraphic(dot);
            colorBtn.setGraphicTextGap(10);
            colorBtn.setTooltip(new Tooltip("Pintar: " + part.getName()));

            // Estilo con borde izquierdo del color de la parte
            applyPaletteButtonStyle(colorBtn, part, false);

            colorBtn.selectedProperty().addListener((obs, was, now) ->
                    applyPaletteButtonStyle(colorBtn, part, now));

            colorBtn.setOnAction(e -> {
                currentPart = part;
                btnDraw.setSelected(true);
                statusLabel.setText("Dibujando: " + part.getName());
                gc.setStroke(Color.web(currentPart.getHexColor()));
            });

            if (part == AnatomyPart.HEAD) colorBtn.setSelected(true);
            paletteContainer.getChildren().add(colorBtn);
        }
    }

    private static void applyPaletteButtonStyle(ToggleButton btn, AnatomyPart part, boolean selected) {
        String hex = part.getHexColor();
        Color c = Color.web(hex);
        // Fondo tintado al 12% del color de la parte cuando está seleccionado
        String tint = String.format("rgba(%d,%d,%d,0.12)",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
        if (selected) {
            btn.setStyle(
                "-fx-border-color: " + hex + " transparent " + hex + " " + hex + ";" +
                "-fx-border-width: 1 0 1 3;" +
                "-fx-background-color: " + tint + ";"
            );
        } else {
            btn.setStyle(
                "-fx-border-color: transparent transparent transparent " + hex + ";" +
                "-fx-border-width: 0 0 0 3;"
            );
        }
    }

    @FXML private void handleMousePressed(MouseEvent e)  { beginStroke(e.getX(), e.getY()); }
    @FXML private void handleMouseDragged(MouseEvent e)  { continueStroke(e.getX(), e.getY()); }
    @FXML private void handleMouseReleased(MouseEvent e) { finishStroke(); }

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
            gc.setFill(Color.WHITE);
            gc.fillRect(x - 10, y - 10, 20, 20);
        } else {
            gc.setStroke(Color.web(currentPart.getHexColor()));
            gc.beginPath();
            gc.moveTo(lastX, lastY);
            gc.lineTo(lastX, lastY);
            gc.stroke();
        }
    }

    private void continueStroke(double x, double y) {
        if (btnErase.isSelected()) {
            gc.setFill(Color.WHITE);
            gc.fillRect(x - 10, y - 10, 20, 20);
        } else {
            gc.lineTo(x, y);
            gc.stroke();
        }
        lastX = x; lastY = y;
    }

    private void finishStroke() {
        if (!btnErase.isSelected()) { gc.stroke(); gc.closePath(); }
        saveCurrentState();
    }

    @FXML
    private void handleClear() {
        redoStack.clear();
        clearToWhite();
        saveCurrentState();
        statusLabel.setText("Lienzo limpio");
    }

    @FXML
    private void handleUndo() {
        if (undoStack.size() > 1) {
            redoStack.push(undoStack.pop());
            clearToWhite();
            gc.drawImage(undoStack.peek(), 0, 0);
            statusLabel.setText("Deshacer realizado");
        }
    }

    @FXML
    private void handleRedo() {
        if (!redoStack.isEmpty()) {
            WritableImage next = redoStack.pop();
            undoStack.push(next);
            clearToWhite();
            gc.drawImage(next, 0, 0);
            statusLabel.setText("Rehacer realizado");
        }
    }

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
            batchNumLabel.setText(String.format("Lote %d/%d", round, totalRounds));
        } else {
            batchNumLabel.setText(round > 0 ? "Analizando..." : "");
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
        statusLabel.setText("Analizando dibujo...");
        setSearchProgress(true, 0.0, 0.0, 0, 1);
        galleryPane.getChildren().clear();

        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        final WritableImage snapshot = canvas.snapshot(params, null);

        AtomicReference<PoseData> computedPoseRef = new AtomicReference<>();

        Task<List<ImageResult>> searchTask = new Task<>() {
            @Override
            protected List<ImageResult> call() {
                PoseData pose = lastAnalyzedPose;
                if (pose == null || pose.getAllJoints().isEmpty()) {
                    pose = DrawingProcessor.processImage(snapshot);
                }
                computedPoseRef.set(pose);

                if (pose != null && !pose.getAllJoints().isEmpty()) {
                    List<String> terms = SearchTermGenerator.generateTerms(pose);
                    final int partCount = pose.getAllJoints().size();
                    currentSearchId = DatabaseManager.saveDrawing(pose, terms, null);
                    Platform.runLater(() ->
                            statusLabel.setText("Pose detectada (" + partCount + " partes). Buscando en fotos...")
                    );
                    return SearchService.searchImages(terms, pose,
                            msg -> Platform.runLater(() -> statusLabel.setText(msg)),
                            p -> Platform.runLater(() ->
                                setSearchProgress(true, p[0], p[1], (int) p[2], (int) p[3]))
                    );
                }
                Platform.runLater(() -> statusLabel.setText("Sin pose detectada. Mostrando fotos recientes..."));
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
                statusLabel.setText(buildEmptySearchMessage());
            } else {
                double best = results.stream().mapToDouble(ImageResult::getScore).filter(s -> s >= 0).max().orElse(0);
                statusLabel.setText(String.format("Búsqueda completada: %d fotos · mejor similitud %.0f%%",
                        results.size(), best * 100));
                if (currentSearchId != -1) {
                    DatabaseManager.saveResults(currentSearchId, results);
                }
            }
        });

        searchTask.setOnFailed(e -> {
            setSearchProgress(false, -1);
            statusLabel.setText("Error en la búsqueda. Revisa logs.");
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
        statusLabel.setText("Copiando " + files.size() + " foto(s)...");

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
            statusLabel.setText(copied + " foto(s) añadidas a la biblioteca.");
            logger.info("[FOTOS] {} imágenes añadidas a {}", copied, targetDir);
        });

        copyTask.setOnFailed(e -> {
            setSearchProgress(false, 0.0);
            String msg = copyTask.getException() != null ? copyTask.getException().getMessage() : "error desconocido";
            statusLabel.setText("Error al añadir fotos: " + msg);
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
        for (ImageResult result : results) {
            VBox card = new VBox(5);
            card.getStyleClass().add("image-card");
            card.setAlignment(Pos.CENTER);
            card.setPrefWidth(currentGalleryImageSize + 10);
            card.setPrefHeight(currentGalleryImageSize + 56);

            ImageView iv = new ImageView();
            iv.setFitWidth(currentGalleryImageSize);
            iv.setFitHeight(currentGalleryImageSize);
            iv.setPreserveRatio(true);

            try {
                String thumbUrl = result.getDisplayThumbnailUrl() == null || result.getDisplayThumbnailUrl().isBlank()
                        ? result.getThumbnailUrl() : result.getDisplayThumbnailUrl();
                Image img = new Image(thumbUrl, currentGalleryImageSize, currentGalleryImageSize, true, true, true);
                iv.setImage(img);
                img.exceptionProperty().addListener((obs, oldEx, newEx) -> {
                    if (newEx != null) logger.warn("Error al cargar imagen: {}", thumbUrl);
                });
            } catch (Exception e) {
                logger.warn("Error al instanciar imagen: {}", result.getThumbnailUrl());
            }

            Label rankBadge = new Label(String.valueOf(rank));
            rankBadge.getStyleClass().add("gallery-rank");
            StackPane imageStack = new StackPane(iv, rankBadge);
            StackPane.setAlignment(rankBadge, Pos.TOP_LEFT);

            Label label;
            if (result.getScore() <= 0.0) {
                label = new Label(String.format("#%d · %s", rank,
                        result.getTitle() == null || result.getTitle().isBlank() ? "Referencia" : result.getTitle()));
                label.setStyle("-fx-font-size: 11px; -fx-text-fill: #8880b0;");
            } else {
                double pct = result.getScore() * 100;
                label = new Label(String.format("%.0f%% similitud", pct));
                label.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 3 8 3 8;" +
                    "-fx-background-radius: 8; -fx-background-color: " +
                    (pct >= 60 ? "rgba(104,211,145,0.18); -fx-text-fill: #68d391;"
                               : "rgba(252,129,129,0.18); -fx-text-fill: #fc8181;"));
            }

            card.getChildren().addAll(imageStack, label);
            card.setCursor(Cursor.HAND);
            Tooltip.install(card, new Tooltip(buildTooltip(result, rank)));
            card.setOnMouseClicked(e -> openResultSource(result));
            galleryPane.getChildren().add(card);
            rank++;
        }
        refreshGalleryCardsSize();
        logger.info("[GALLERY] {} resultados mostrados", results.size());
    }

    private String buildEmptySearchMessage() {
        try {
            Path dir = ProjectPaths.getThumbnailsDirectory();
            long count = Files.list(dir).filter(Files::isRegularFile).count();
            if (count == 0) return "No hay fotos en: " + dir + "  · Usa ➕ Añadir fotos para empezar.";
            return "Hay " + count + " fotos pero no se pudo analizar la pose. "
                    + "Asegúrate de tener Python con MediaPipe instalado.";
        } catch (Exception e) {
            return "Sin resultados. Usa ➕ Añadir fotos o revisa el entorno Python.";
        }
    }

    private String buildTooltip(ImageResult result, int rank) {
        StringBuilder sb = new StringBuilder("Posición: #").append(rank);
        if (result.getTitle() != null && !result.getTitle().isBlank())
            sb.append("\n").append(result.getTitle());
        if (result.getScore() > 0.0)
            sb.append("\n").append(String.format("Similitud: %.0f%%", result.getScore() * 100.0));
        sb.append("\nHaz clic para abrir el archivo");
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
}
