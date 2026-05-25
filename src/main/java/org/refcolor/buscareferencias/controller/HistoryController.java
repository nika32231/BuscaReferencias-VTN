package org.refcolor.buscareferencias.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.refcolor.buscareferencias.BuscaReferenciasApp;
import org.refcolor.buscareferencias.auth.UserManager;
import org.refcolor.buscareferencias.database.DatabaseManager;
import org.refcolor.buscareferencias.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * Controlador del historial de dibujos.
 * Muestra los dibujos guardados con su snapshot y fecha.
 */
public class HistoryController {

    private static final Logger logger = LoggerFactory.getLogger(HistoryController.class);

    @FXML private Label      lblTitle;
    @FXML private Label      lblUser;
    @FXML private Label      lblEmptyTitle;
    @FXML private Label      lblEmptySubtitle;
    @FXML private VBox       emptyState;
    @FXML private ScrollPane scrollPane;
    @FXML private FlowPane   historyGrid;

    private Stage    stage;
    private Runnable onBack;

    @FXML
    public void initialize() {
        boolean en = I18n.isEnglish();
        if (lblTitle         != null) lblTitle.setText(en ? "📋 Drawing history" : "📋 Historial de dibujos");
        if (lblEmptyTitle    != null) lblEmptyTitle.setText(en ? "No saved drawings" : "No hay dibujos guardados");
        if (lblEmptySubtitle != null) lblEmptySubtitle.setText(en ? "When you save a drawing from the app, it will appear here."
                                                                  : "Cuando guardes un dibujo desde la app, aparecerá aquí.");
        var session = UserManager.getCurrentSession();
        if (session != null) {
            lblUser.setText("👤 " + session.username());
        }
        loadHistory();
    }

    public void setStage(Stage stage)   { this.stage = stage; }
    public void setOnBack(Runnable r)   { this.onBack = r; }

    @FXML
    private void handleBack() {
        if (onBack != null) Platform.runLater(onBack);
    }

    // ── Carga ─────────────────────────────────────────────────────────────────

    private void loadHistory() {
        int userId = UserManager.getCurrentUserId();
        new Thread(() -> {
            List<DatabaseManager.DrawingRecord> records =
                DatabaseManager.getDrawingHistory(userId, 60);
            Platform.runLater(() -> {
                historyGrid.getChildren().clear();
                if (records.isEmpty()) {
                    emptyState.setVisible(true);
                    emptyState.setManaged(true);
                    scrollPane.setVisible(false);
                    scrollPane.setManaged(false);
                } else {
                    emptyState.setVisible(false);
                    emptyState.setManaged(false);
                    scrollPane.setVisible(true);
                    scrollPane.setManaged(true);
                    for (var rec : records) {
                        historyGrid.getChildren().add(buildCard(rec));
                    }
                }
            });
        }, "history-loader").start();
    }

    private VBox buildCard(DatabaseManager.DrawingRecord rec) {
        boolean en = I18n.isEnglish();

        VBox card = new VBox(6);
        card.setStyle(
            "-fx-background-color: #12263A;" +
            "-fx-background-radius: 10;" +
            "-fx-border-color: rgba(100,150,200,0.2);" +
            "-fx-border-radius: 10;" +
            "-fx-padding: 10;");
        card.setPrefWidth(160);

        // Snapshot o placeholder
        ImageView iv = new ImageView();
        iv.setFitWidth(140);
        iv.setFitHeight(110);
        iv.setPreserveRatio(true);

        if (rec.snapshotPath() != null) {
            File f = new File(rec.snapshotPath());
            if (f.exists()) {
                try {
                    iv.setImage(new Image(f.toURI().toString(), 140, 110, true, true, true));
                } catch (Exception e) {
                    setPlaceholder(iv);
                }
            } else {
                setPlaceholder(iv);
            }
        } else {
            setPlaceholder(iv);
        }

        // Título
        String titulo = (rec.titulo() != null && !rec.titulo().isBlank())
            ? rec.titulo() : "Dibujo #" + rec.id();
        Label lblTitulo = new Label(titulo);
        lblTitulo.setStyle("-fx-text-fill: #EAD3CB; -fx-font-size: 12px; -fx-font-weight: bold;");
        lblTitulo.setWrapText(true);
        lblTitulo.setMaxWidth(140);

        // Fecha
        String fecha = rec.fechaCreacion() != null
            ? rec.fechaCreacion().substring(0, Math.min(10, rec.fechaCreacion().length())) : "";
        Label lblFecha = new Label("📅 " + fecha);
        lblFecha.setStyle("-fx-text-fill: #6B8FA3; -fx-font-size: 11px;");

        // Búsquedas
        String searchesText = en
            ? "🔍 " + rec.numBusquedas() + " search(es)"
            : "🔍 " + rec.numBusquedas() + " búsqueda(s)";
        Label lblBusq = new Label(searchesText);
        lblBusq.setStyle("-fx-text-fill: #5A8A7C; -fx-font-size: 11px;");

        // ── Botones de acción ─────────────────────────────────────────────────
        Button btnRename = new Button(en ? "✏ Rename" : "✏ Renombrar");
        btnRename.setStyle(
            "-fx-background-color: #1C3A50; -fx-text-fill: #64B5F6;" +
            "-fx-font-size: 10px; -fx-padding: 3 6; -fx-background-radius: 6; -fx-cursor: hand;");
        btnRename.setOnAction(e -> {
            TextInputDialog dlg = new TextInputDialog(titulo);
            dlg.setTitle(en ? "Rename drawing" : "Renombrar dibujo");
            dlg.setHeaderText(null);
            dlg.setContentText(en ? "New name:" : "Nuevo nombre:");
            if (stage != null) dlg.initOwner(stage);
            Optional<String> result = dlg.showAndWait();
            result.ifPresent(newName -> {
                if (!newName.isBlank()) {
                    DatabaseManager.renameDrawing(rec.id(), newName.trim());
                    loadHistory();
                }
            });
        });

        Button btnDelete = new Button(en ? "🗑 Delete" : "🗑 Eliminar");
        btnDelete.setStyle(
            "-fx-background-color: #1C3A50; -fx-text-fill: #F44336;" +
            "-fx-font-size: 10px; -fx-padding: 3 6; -fx-background-radius: 6; -fx-cursor: hand;");
        btnDelete.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(en ? "Delete drawing" : "Eliminar dibujo");
            confirm.setHeaderText(null);
            confirm.setContentText(en
                ? "Delete \"" + titulo + "\"? This cannot be undone."
                : "¿Eliminar \"" + titulo + "\"? Esta acción no se puede deshacer.");
            if (stage != null) confirm.initOwner(stage);
            confirm.showAndWait().ifPresent(type -> {
                if (type == ButtonType.OK) {
                    DatabaseManager.deleteDrawing(rec.id());
                    loadHistory();
                }
            });
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox btnRow = new HBox(4, btnRename, spacer, btnDelete);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(iv, lblTitulo, lblFecha, lblBusq, btnRow);
        return card;
    }

    private void setPlaceholder(ImageView iv) {
        // Canvas negro con icono de lápiz como placeholder
        javafx.scene.canvas.Canvas c = new javafx.scene.canvas.Canvas(140, 120);
        var gc = c.getGraphicsContext2D();
        gc.setFill(javafx.scene.paint.Color.web("#1C3A50"));
        gc.fillRoundRect(0, 0, 140, 120, 12, 12);
        gc.setFill(javafx.scene.paint.Color.web("#2B4F60"));
        gc.setFont(javafx.scene.text.Font.font(36));
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        gc.setTextBaseline(javafx.geometry.VPos.CENTER);
        gc.fillText("✎", 70, 60);
        // Convertir canvas a snapshot para mostrarlo en ImageView
        javafx.scene.image.WritableImage snap = new javafx.scene.image.WritableImage(140, 120);
        c.snapshot(null, snap);
        iv.setImage(snap);
    }
}
