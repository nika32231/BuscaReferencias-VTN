package org.refcolor.buscareferencias.core;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class FallbackUi {

    private FallbackUi() {
    }

    public static Scene createFallbackScene(String title, Throwable error) {
        Label header = new Label(title);
        header.setFont(Font.font(18));

        Label hint = new Label("La app arrancó en modo seguro. Se desactivó la UI avanzada para evitar un crash.");

        TextArea details = new TextArea(stackTraceToString(error));
        details.setEditable(false);
        details.setWrapText(false);

        Button copyBtn = new Button("Copiar error");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(details.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });

        Button closeBtn = new Button("Cerrar");
        closeBtn.setOnAction(e -> System.exit(1));

        HBox actions = new HBox(8, copyBtn, closeBtn);

        VBox root = new VBox(10, header, hint, actions, details);
        VBox.setVgrow(details, Priority.ALWAYS);
        root.setStyle("-fx-padding: 16; -fx-background-color: #111; -fx-text-fill: #eee;");
        header.setStyle("-fx-text-fill: #eee;");
        hint.setStyle("-fx-text-fill: #ccc;");
        actions.setStyle("-fx-padding: 4 0 4 0;");

        return new Scene(root, 1100, 700);
    }

    private static String stackTraceToString(Throwable t) {
        if (t == null) return "(sin error)";
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}

