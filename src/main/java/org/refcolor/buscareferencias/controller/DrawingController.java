package org.refcolor.buscareferencias.controller;

import java.awt.Desktop;
import javafx.application.HostServices;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.control.SplitPane;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.utils.DrawingProcessor;
import org.refcolor.buscareferencias.service.SearchService;
import org.refcolor.buscareferencias.utils.SearchTermGenerator;
import org.refcolor.buscareferencias.database.DatabaseManager;
import javafx.event.ActionEvent;
import javafx.scene.Cursor;
import java.time.Duration;
import java.time.Instant;

public class DrawingController {

    private static final Logger logger = LoggerFactory.getLogger(DrawingController.class);

    private static final double LINE_WIDTH = 5.0;
    private static final Color CANVAS_FRAME_COLOR = Color.web("#b0b7c3");
    private static final int UNDO_STACK_LIMIT = 20;

    @FXML private Canvas canvas;
    @FXML private VBox paletteContainer;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private ToggleButton btnDraw;
    @FXML private ToggleButton btnErase;
    @FXML private StackPane canvasContainer;
    @FXML private StackPane canvasRoot;
    @FXML private VBox rightPanel;
    @FXML private TitledPane searchPanel;
    @FXML private Button togglePanelBtn;
    @FXML private SplitPane mainSplitPane;

    // Hito 2: Términos de búsqueda
    @FXML private ListView<String> termsListView;
    @FXML private TextField newTermField;

    // Hito 3: Galería
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

    private boolean rightPanelCollapsed = false;

    private static final double CANVAS_INITIAL_W = 900;
    private static final double CANVAS_INITIAL_H = 600;
    private static final double CANVAS_MIN_W = 500;
    private static final double CANVAS_MIN_H = 350;
    private static final double CANVAS_MAX_W = 1600;
    private static final double CANVAS_MAX_H = 1200;

    private boolean resizingCanvas = false;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartW;
    private double resizeStartH;

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

        // Resize manual solamente (sin listeners automáticos)
        setupManualCanvasResize();

        logger.info("[UI] DrawingController.initialize() end en {} ms", Duration.between(t0, Instant.now()).toMillis());
    }

    /**
     * Sustituye el auto-resize (que puede causar loops) por un resize manual controlado.
     * Mantiene nitidez: NO reescala el contenido existente; solo expande el área en blanco.
     */
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

            double newW = clamp(resizeStartW + dx, CANVAS_MIN_W, CANVAS_MAX_W);
            double newH = clamp(resizeStartH + dy, CANVAS_MIN_H, CANVAS_MAX_H);

            resizeCanvasKeepingContent((int) newW, (int) newH);
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
        // Evitar trabajo si no cambia
        if ((int) canvas.getWidth() == newW && (int) canvas.getHeight() == newH) return;

        WritableImage snapshot = canvas.snapshot(null, null);

        canvas.setWidth(newW);
        canvas.setHeight(newH);

        // Fondo blanco
        clearToWhite();

        // Importante: NO escalamos. Dibujamos con el tamaño original para evitar pixelación.
        gc.drawImage(snapshot, 0, 0);
        drawCanvasFrame();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
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

    private void saveCurrentState() {
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        undoStack.push(canvas.snapshot(params, null));
        if (undoStack.size() > UNDO_STACK_LIMIT) {
            undoStack.removeLast();
        }
    }

    private void setupPalette() {
        ToggleGroup paletteGroup = new ToggleGroup();
        for (AnatomyPart part : AnatomyPart.values()) {
            ToggleButton colorBtn = new ToggleButton(part.getName());
            colorBtn.setToggleGroup(paletteGroup);
            colorBtn.getStyleClass().add("palette-button");
            
            // Cuadro de color más grande para ser más visual
            Rectangle colorSquare = new Rectangle(18, 18, Color.web(part.getHexColor()));
            colorSquare.setStroke(Color.BLACK);
            colorSquare.setStrokeWidth(1);
            colorBtn.setGraphic(colorSquare);
            colorBtn.setGraphicTextGap(12);

            colorBtn.setTooltip(new Tooltip("Pintar: " + part.getName()));

            colorBtn.setOnAction(e -> {
                currentPart = part;
                btnDraw.setSelected(true);
                statusLabel.setText("Herramienta: Dibujar - " + part.getName());
                gc.setStroke(Color.web(currentPart.getHexColor()));
            });
            
            if (part == AnatomyPart.HEAD) {
                colorBtn.setSelected(true);
            }
            
            paletteContainer.getChildren().add(colorBtn);
        }
    }

    @FXML
    private void handleMousePressed(MouseEvent e) {
        // Limpiamos el redoStack al iniciar un nuevo cambio manual
        redoStack.clear();
        
        lastX = e.getX();
        lastY = e.getY();
        
        if (btnErase.isSelected()) {
            gc.setFill(Color.WHITE);
            gc.fillRect(e.getX() - 10, e.getY() - 10, 20, 20);
        } else {
            gc.setStroke(Color.web(currentPart.getHexColor()));
            gc.beginPath();
            gc.moveTo(lastX, lastY);
            // Dibujar un punto inmediatamente para soportar clics sueltos
            gc.lineTo(lastX, lastY);
            gc.stroke();
        }
    }

    @FXML
    private void handleMouseDragged(MouseEvent e) {
        if (btnErase.isSelected()) {
            gc.setFill(Color.WHITE);
            gc.fillRect(e.getX() - 10, e.getY() - 10, 20, 20);
        } else {
            gc.lineTo(e.getX(), e.getY());
            gc.stroke();
        }
        lastX = e.getX();
        lastY = e.getY();
    }

    @FXML
    private void handleMouseReleased(MouseEvent e) {
        // e se mantiene por firma FXML
        if (!btnErase.isSelected()) {
            gc.stroke();
            gc.closePath();
        }
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
        if (undoStack.size() > 1) { // Necesitamos al menos 2 estados (el actual y el anterior)
            // Quitamos el estado actual (que es el que acabamos de dibujar)
            redoStack.push(undoStack.pop());
            
            // Miramos el estado anterior
            WritableImage previousImage = undoStack.peek();
            
            clearToWhite();
            gc.drawImage(previousImage, 0, 0);
            statusLabel.setText("Deshacer realizado");
        }
    }

    @FXML
    private void handleRedo() {
        if (!redoStack.isEmpty()) {
            WritableImage nextImage = redoStack.pop();
            undoStack.push(nextImage);
            
            clearToWhite();
            gc.drawImage(nextImage, 0, 0);
            statusLabel.setText("Rehacer realizado");
        }
    }

    @FXML
    private void handleWebSearch() {
        if (termsListView.getItems() == null || termsListView.getItems().isEmpty()) {
            statusLabel.setText("No hay términos para buscar. Añade uno o analiza el dibujo.");
            return;
        }

        statusLabel.setText("Buscando referencias online...");
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        galleryPane.getChildren().clear();

        List<String> terms = new ArrayList<>(termsListView.getItems());

        Task<List<ImageResult>> searchTask = new Task<>() {
            @Override
            protected List<ImageResult> call() {
                if (lastAnalyzedPose != null && !lastAnalyzedPose.getAllJoints().isEmpty()) {
                    return SearchService.searchImages(terms, lastAnalyzedPose);
                }
                return SearchService.searchWebThumbnailsOnly(terms, 24);
            }
        };

        searchTask.setOnSucceeded(e -> {
            List<ImageResult> results = searchTask.getValue();
            displayResults(results);
            progressBar.setVisible(false);
            if (results.isEmpty()) {
                statusLabel.setText("No se encontraron referencias online. Revisa la conexión o la caché local.");
            } else {
                statusLabel.setText("OK: " + results.size() + " referencias cargadas en galería.");
                if (currentSearchId != -1) {
                    DatabaseManager.saveResults(currentSearchId, results);
                }
            }
        });

        searchTask.setOnFailed(e -> {
            progressBar.setVisible(false);
            statusLabel.setText("Error en la búsqueda web (test). Revisa logs.");
            logger.error("Error en búsqueda online (test)", searchTask.getException());
        });

        new Thread(searchTask).start();
    }

    private void displayResults(List<ImageResult> results) {
        for (ImageResult result : results) {
            VBox card = new VBox(5);
            card.getStyleClass().add("image-card");
            card.setAlignment(javafx.geometry.Pos.CENTER);
            card.setPrefWidth(160);
            card.setPrefHeight(210);

            ImageView iv = new ImageView();
            iv.setFitWidth(150);
            iv.setFitHeight(150);
            iv.setPreserveRatio(true);

            try {
                String thumbUrl = result.getDisplayThumbnailUrl() == null || result.getDisplayThumbnailUrl().isBlank()
                        ? result.getThumbnailUrl()
                        : result.getDisplayThumbnailUrl();
                Image img = new Image(thumbUrl, 150, 150, true, true, true);
                iv.setImage(img);
                img.exceptionProperty().addListener((obs, oldEx, newEx) -> {
                    if (newEx != null) {
                        logger.warn("Error al cargar imagen: {}", thumbUrl);
                    }
                });
            } catch (Exception e) {
                logger.warn("Error al instanciar imagen: {}", result.getThumbnailUrl());
            }

            Label label;
            if (result.getScore() <= 0.0) {
                label = new Label(result.getTitle() == null || result.getTitle().isBlank() ? "Thumbnail" : result.getTitle());
                label.setStyle("-fx-font-size: 12px; -fx-text-fill: #b9beca;");
            } else {
                double scorePercent = result.getScore() * 100;
                // Mostrar porcentaje simple (ej: 87%) con color según umbral
                label = new Label(String.format("%.0f%%", scorePercent));
                if (scorePercent >= 60) {
                    label.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #2e7d32;");
                } else {
                    label.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #c62828;");
                }
            }

            card.getChildren().addAll(iv, label);
            card.setCursor(javafx.scene.Cursor.HAND);
            String tooltipText = buildTooltip(result);
            Tooltip.install(card, new Tooltip(tooltipText));

            card.setOnMouseClicked(e -> {
                openResultSource(result);
            });

            galleryPane.getChildren().add(card);
        }
    }

    @FXML
    private void handleSearch() {
        statusLabel.setText("Procesando dibujo...");
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        
        // 1. CAPTURA DEL SNAPSHOT EN EL HILO DE LA UI
        // Esta es la operación que lanzaba IllegalStateException antes
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        final WritableImage snapshot = canvas.snapshot(params, null);

        // 2. PROCESAMIENTO PESADO EN HILO SECUNDARIO
        Task<PoseData> analyzeTask = new Task<>() {
            @Override
            protected PoseData call() {
                // Pasamos la imagen capturada para procesarla píxel a píxel
                return DrawingProcessor.processImage(snapshot);
            }
        };

        analyzeTask.setOnSucceeded(e -> {
            PoseData pose = analyzeTask.getValue();
            this.lastAnalyzedPose = pose;
            progressBar.setVisible(false);
            if (pose.getAllJoints().isEmpty()) {
                statusLabel.setText("No se detectaron colores anatómicos.");
            } else {
                statusLabel.setText("Colores detectados: " + pose.getAllJoints().size());
                
                // Hito 2: Generar términos
                List<String> terms = SearchTermGenerator.generateTerms(pose);
                termsListView.getItems().setAll(terms);
                
                // Hito 2: Guardar en Base de Datos (inicialmente sin resultados)
                currentSearchId = DatabaseManager.saveDrawing(pose, terms, null);
                // Restauramos a INFO para que se vea en terminal, pero con mensaje descriptivo
                logger.info("Análisis de pose completado con éxito. Términos: {}", terms);
                // Además imprimimos por System.out para asegurar que el usuario lo vea sin colores de error
                logger.info("RESULTADO ANÁLISIS: {}", pose);
            }
        });

        analyzeTask.setOnFailed(e -> {
            progressBar.setVisible(false);
            statusLabel.setText("Error en el análisis.");
            // Nivel INFO para el error también si queremos evitar el rojo de SEVERE en terminales de IDE
            logger.info("AVISO: Error durante el análisis del dibujo. Comprueba los trazos.");
        });

        new Thread(analyzeTask).start();
    }

    @FXML
    private void handleAddTerm(ActionEvent event) {
        try {
            if (newTermField == null || termsListView == null) {
                logger.warn("UI no inicializada: newTermField o termsListView es null");
                return;
            }

            String term = newTermField.getText() == null ? "" : newTermField.getText().trim();
            if (term.isEmpty()) {
                return;
            }

            termsListView.getItems().add(term);
            newTermField.clear();
        } catch (Exception e) {
            logger.error("Error añadiendo término", e);
            if (statusLabel != null) {
                statusLabel.setText("No se pudo añadir el término");
            }
        }
    }

    @FXML
    private void toggleRightPanel(ActionEvent event) {
        // Delegamos en la lógica existente (si hubiera método público), o implementamos aquí.
        if (mainSplitPane == null || rightPanel == null) {
            if (searchPanel != null) {
                boolean expanded = searchPanel.isExpanded();
                searchPanel.setExpanded(!expanded);
                if (togglePanelBtn != null) {
                    togglePanelBtn.setText(expanded ? "⏴" : "⏵");
                }
            }
            return;
        }

        rightPanelCollapsed = !rightPanelCollapsed;
        if (rightPanelCollapsed) {
            rightPanel.setManaged(false);
            rightPanel.setVisible(false);
            if (togglePanelBtn != null) togglePanelBtn.setText("⏵");
            mainSplitPane.setDividerPositions(1.0);
        } else {
            rightPanel.setManaged(true);
            rightPanel.setVisible(true);
            if (togglePanelBtn != null) togglePanelBtn.setText("⏴");
            mainSplitPane.setDividerPositions(0.78);
        }
    }

    private String buildTooltip(ImageResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.getTitle() != null && !result.getTitle().isBlank()) {
            sb.append(result.getTitle());
        }
        if (result.getScore() > 0.0) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(String.format("Similitud: %.0f%%", result.getScore() * 100.0));
        }
        if (result.getSource() != null && !result.getSource().isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Fuente: ").append(result.getSource());
        }
        if (result.getSearchQuery() != null && !result.getSearchQuery().isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Búsqueda: ").append(result.getSearchQuery());
        }
        String sourcePageUrl = result.getSourcePageUrl();
        if (sourcePageUrl != null && !sourcePageUrl.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(sourcePageUrl);
        }
        if (sb.length() == 0) {
            sb.append("Haz clic para abrir la fuente original");
        } else {
            sb.append("\nHaz clic para abrir la fuente original");
        }
        return sb.toString();
    }

    private void openResultSource(ImageResult result) {
        String target = result.getSourcePageUrl();
        if (target == null || target.isBlank()) {
            target = result.getOriginalUrl();
        }

        if (target == null || target.isBlank()) {
            logger.warn("No hay URL de destino para abrir en la galería.");
            return;
        }

        try {
            if (hostServices != null) {
                hostServices.showDocument(target);
                return;
            }
        } catch (Exception e) {
            logger.warn("HostServices falló al abrir {}, intentando Desktop", target, e);
        }

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(target));
            } else {
                logger.warn("Desktop no soportado en este entorno: {}", target);
            }
        } catch (Exception e) {
            logger.error("No se pudo abrir la URL: {}", target, e);
        }
    }

}
