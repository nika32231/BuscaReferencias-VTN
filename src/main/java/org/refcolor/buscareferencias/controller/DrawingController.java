package org.refcolor.buscareferencias.controller;

import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.refcolor.buscareferencias.model.AnatomyColors;
import org.refcolor.buscareferencias.model.PoseData;
import org.refcolor.buscareferencias.utils.DrawingProcessor;

import java.util.Stack;

public class DrawingController {

    @FXML private Canvas canvas;
    @FXML private VBox paletteContainer;
    @FXML private Label statusLabel;
    @FXML private ToggleButton btnDraw;
    @FXML private ToggleButton btnErase;

    private GraphicsContext gc;
    private Color currentColor = AnatomyColors.HEAD;
    private double lastX, lastY;
    
    private final Stack<WritableImage> undoStack = new Stack<>();
    private final Stack<WritableImage> redoStack = new Stack<>();

    private ToggleGroup toolGroup;

    @FXML
    public void initialize() {
        gc = canvas.getGraphicsContext2D();
        gc.setLineWidth(5.0);
        gc.setStroke(currentColor);
        
        // Inicializar el lienzo con fondo transparente (por defecto ya lo es, pero aseguramos)
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        
        toolGroup = new ToggleGroup();
        btnDraw.setToggleGroup(toolGroup);
        btnErase.setToggleGroup(toolGroup);
        btnDraw.setSelected(true);

        setupPalette();
        saveState();
    }

    private void setupPalette() {
        ToggleGroup paletteGroup = new ToggleGroup();
        for (Color color : AnatomyColors.getAll()) {
            ToggleButton colorBtn = new ToggleButton(AnatomyColors.getName(color));
            colorBtn.setToggleGroup(paletteGroup);
            colorBtn.setPrefWidth(130);
            colorBtn.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            
            // Cuadrito del color al lado del texto
            javafx.scene.shape.Rectangle colorSquare = new javafx.scene.shape.Rectangle(15, 15, color);
            colorSquare.setStroke(Color.BLACK);
            colorSquare.setStrokeWidth(1);
            colorBtn.setGraphic(colorSquare);
            colorBtn.setGraphicTextGap(10);
            
            // Fondo blanco para los botones
            colorBtn.setStyle("-fx-background-color: white; " +
                             "-fx-text-fill: #333333; " +
                             "-fx-font-weight: bold; " +
                             "-fx-border-color: #cccccc; " +
                             "-fx-border-width: 1px; " +
                             "-fx-background-radius: 5; " +
                             "-fx-border-radius: 5;");

            colorBtn.setTooltip(new Tooltip("Pintar: " + AnatomyColors.getName(color)));

            colorBtn.setOnAction(e -> {
                currentColor = color;
                btnDraw.setSelected(true);
                statusLabel.setText("Herramienta: Dibujar - " + AnatomyColors.getName(color));
            });
            
            if (color.equals(AnatomyColors.HEAD)) {
                colorBtn.setSelected(true);
            }
            
            paletteContainer.getChildren().add(colorBtn);
        }
    }

    @FXML
    private void handleMousePressed(MouseEvent e) {
        saveState();
        lastX = e.getX();
        lastY = e.getY();
        
        if (btnErase.isSelected()) {
            gc.clearRect(e.getX() - 10, e.getY() - 10, 20, 20);
        } else {
            gc.setStroke(currentColor);
            gc.beginPath();
            gc.moveTo(lastX, lastY);
            gc.stroke();
        }
    }

    @FXML
    private void handleMouseDragged(MouseEvent e) {
        if (btnErase.isSelected()) {
            gc.clearRect(e.getX() - 10, e.getY() - 10, 20, 20);
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
            gc.lineTo(e.getX(), e.getY());
            gc.stroke();
            gc.closePath();
        }
    }

    @FXML
    private void handleClear() {
        saveState();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        statusLabel.setText("Lienzo limpio");
    }

    @FXML
    private void handleUndo() {
        if (!undoStack.isEmpty()) {
            javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            redoStack.push(canvas.snapshot(params, null));
            WritableImage image = undoStack.pop();
            gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            gc.drawImage(image, 0, 0);
            statusLabel.setText("Deshacer realizado");
        }
    }

    @FXML
    private void handleRedo() {
        if (!redoStack.isEmpty()) {
            javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            undoStack.push(canvas.snapshot(params, null));
            WritableImage image = redoStack.pop();
            gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            gc.drawImage(image, 0, 0);
            statusLabel.setText("Rehacer realizado");
        }
    }

    @FXML
    private void handleSearch() {
        statusLabel.setText("Procesando dibujo...");
        
        PoseData pose = DrawingProcessor.processCanvas(canvas);
        
        if (pose.getAllJoints().isEmpty()) {
            statusLabel.setText("No se detectaron colores anatómicos.");
        } else {
            statusLabel.setText("Colores detectados: " + pose.getAllJoints().size());
            System.out.println("DEBUG (Hito 1): " + pose);
        }
    }

    private void saveState() {
        // Para que el snapshot mantenga la transparencia, usamos SnapshotParameters con fill transparente
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage snapshot = canvas.snapshot(params, null);
        undoStack.push(snapshot);
        redoStack.clear();
        if (undoStack.size() > 20) {
            undoStack.remove(0);
        }
    }
}
