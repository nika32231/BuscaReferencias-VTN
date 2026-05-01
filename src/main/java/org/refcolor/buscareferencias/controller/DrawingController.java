package org.refcolor.buscareferencias.controller;

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
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.refcolor.buscareferencias.BuscaReferenciasApp;
import org.refcolor.buscareferencias.model.AnatomyPart;
import org.refcolor.buscareferencias.model.ImageResult;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.utils.DatabaseManager;
import org.refcolor.buscareferencias.utils.DrawingProcessor;
import org.refcolor.buscareferencias.utils.SearchService;
import org.refcolor.buscareferencias.utils.SearchTermGenerator;

public class DrawingController {

    private static final Logger logger = Logger.getLogger(DrawingController.class.getName());

    @FXML private Canvas canvas;
    @FXML private VBox paletteContainer;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private ToggleButton btnDraw;
    @FXML private ToggleButton btnErase;
    
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

    @FXML
    public void initialize() {
        gc = canvas.getGraphicsContext2D();
        gc.setLineWidth(5.0);
        gc.setStroke(Color.web(currentPart.getHexColor()));
        
        // Inicializar el lienzo con fondo blanco
        clearToWhite();
        
        toolGroup = new ToggleGroup();
        btnDraw.setToggleGroup(toolGroup);
        btnErase.setToggleGroup(toolGroup);
        btnDraw.setSelected(true);

        setupPalette();
        // Guardar el estado inicial (lienzo vacío) como el primer elemento de la pila
        saveCurrentState();
    }

    @FXML
    private void handleAddTerm() {
        String term = newTermField.getText().trim();
        if (!term.isEmpty()) {
            termsListView.getItems().add(term);
            newTermField.clear();
        }
    }

    @FXML
    private void handleWebSearch() {
        if (termsListView.getItems() == null || termsListView.getItems().isEmpty()) {
            statusLabel.setText("No hay términos para buscar. Analiza el dibujo primero.");
            return;
        }
        
        if (lastAnalyzedPose == null) {
            statusLabel.setText("Debes analizar el dibujo antes de buscar.");
            return;
        }
        
        statusLabel.setText("Buscando imágenes en la web...");
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        galleryPane.getChildren().clear();

        List<String> terms = new ArrayList<>(termsListView.getItems());
        final PoseData poseForSearch = lastAnalyzedPose;

        Task<List<ImageResult>> searchTask = new Task<>() {
            @Override
            protected List<ImageResult> call() {
                return SearchService.searchImages(terms, poseForSearch);
            }
        };

        searchTask.setOnSucceeded(e -> {
            List<ImageResult> results = searchTask.getValue();
            displayResults(results);
            progressBar.setVisible(false);
            statusLabel.setText("Búsqueda completada. " + results.size() + " imágenes encontradas.");
        });

        searchTask.setOnFailed(e -> {
            progressBar.setVisible(false);
            statusLabel.setText("Error en la búsqueda web.");
        });

        new Thread(searchTask).start();
    }

    private void displayResults(List<ImageResult> results) {
        for (ImageResult result : results) {
            VBox card = new VBox(5);
            card.getStyleClass().add("image-card");
            card.setAlignment(javafx.geometry.Pos.CENTER);
            
            ImageView iv = new ImageView();
            try {
                // Carga asíncrona de la imagen
                Image img = new Image(result.getThumbnailUrl(), 150, 150, true, true, true);
                iv.setImage(img);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error al cargar miniatura: {0}", result.getThumbnailUrl());
            }
            
            Label label = new Label(String.format("%.0f%% match", result.getScore() * 100));
            label.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
            
            card.getChildren().addAll(iv, label);
            card.setCursor(javafx.scene.Cursor.HAND);
            Tooltip.install(card, new Tooltip(result.getTitle() + "\nHaz clic para ver original"));
            
            card.setOnMouseClicked(e -> {
                BuscaReferenciasApp.getInstance().getHostServices().showDocument(result.getOriginalUrl());
            });
            
            galleryPane.getChildren().add(card);
        }
    }

    private void clearToWhite() {
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void saveCurrentState() {
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        undoStack.push(canvas.snapshot(params, null));
        if (undoStack.size() > 20) {
            undoStack.removeLast();
        }
    }

    private void setupPalette() {
        ToggleGroup paletteGroup = new ToggleGroup();
        for (AnatomyPart part : AnatomyPart.values()) {
            ToggleButton colorBtn = new ToggleButton(part.getName());
            colorBtn.setToggleGroup(paletteGroup);
            colorBtn.getStyleClass().add("palette-button");
            
            // Cuadrito del color
            Rectangle colorSquare = new Rectangle(15, 15, Color.web(part.getHexColor()));
            colorSquare.setStroke(Color.BLACK);
            colorSquare.setStrokeWidth(1);
            colorBtn.setGraphic(colorSquare);
            colorBtn.setGraphicTextGap(10);

            colorBtn.setTooltip(new Tooltip("Pintar: " + part.getName()));

            colorBtn.setOnAction(e -> {
                currentPart = part;
                btnDraw.setSelected(true);
                statusLabel.setText("Herramienta: Dibujar - " + part.getName());
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
        if (!btnErase.isSelected()) {
            gc.stroke();
            gc.closePath();
        }
        // Guardar estado DESPUÉS de terminar el trazo
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
                
                // Hito 2: Guardar en Base de Datos
                DatabaseManager.saveDrawing(pose, terms);
                
                // Restauramos a INFO para que se vea en terminal, pero con mensaje descriptivo
                logger.log(Level.INFO, "Análisis de pose completado con éxito. Términos: {0}", terms);
                // Además imprimimos por System.out para asegurar que el usuario lo vea sin colores de error
                logger.info("RESULTADO ANÁLISIS: " + pose);
            }
        });

        analyzeTask.setOnFailed(e -> {
            progressBar.setVisible(false);
            statusLabel.setText("Error en el análisis.");
            // Nivel INFO para el error también si queremos evitar el rojo de SEVERE en terminales de IDE
            logger.log(Level.INFO, "AVISO: Error durante el análisis del dibujo. Comprueba los trazos.");
        });

        new Thread(analyzeTask).start();
    }

}
