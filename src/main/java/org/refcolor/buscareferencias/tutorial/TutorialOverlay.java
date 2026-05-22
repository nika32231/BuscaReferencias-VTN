package org.refcolor.buscareferencias.tutorial;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.util.Duration;

import java.util.List;
import java.util.prefs.Preferences;
import org.refcolor.buscareferencias.i18n.I18n;

/**
 * Overlay de tutorial interactivo estilo "spotlight".
 *
 * <h3>Interactividad</h3>
 * <ul>
 *   <li><b>Teclado</b>: → / Enter avanza, ← retrocede, Esc cierra.</li>
 *   <li><b>Clic en el spotlight</b>: avanza al paso siguiente (cursor mano).</li>
 *   <li><b>Dots clicables</b>: salto directo a cualquier paso.</li>
 *   <li><b>Spotlight pulsante</b>: glow animado sobre el elemento activo.</li>
 *   <li><b>Transición deslizante</b>: la tarjeta entra desde izq/der según dirección.</li>
 *   <li><b>Rebote del icono</b>: animación elástica al cambiar de paso.</li>
 *   <li><b>Barra de progreso</b>: fill animado mostrando el avance global.</li>
 *   <li><b>Contador</b>: "2 / 8" en la esquina de la tarjeta.</li>
 * </ul>
 */
public class TutorialOverlay {

    // ── Step record ───────────────────────────────────────────────────────────

    /**
     * Cada paso del tutorial.
     * {@code titleKey} y {@code bodyKey} son claves i18n resueltas vía {@link I18n#t(String)}.
     */
    public record Step(Node target, String icon, String titleKey, String bodyKey) {
        /** Paso sin nodo objetivo (bienvenida / fin). */
        public Step(String icon, String titleKey, String bodyKey) {
            this(null, icon, titleKey, bodyKey);
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String  PREFS_KEY     = "tutorial_seen_v1";
    private static final double  OVERLAY_ALPHA = 0.74;
    private static final double  SPOT_PAD      = 13.0;
    private static final double  TOOLTIP_W     = 400.0;
    private static final double  TOOLTIP_GAP   = 20.0;

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Escala HiDPI relativa a baseline 1920×1080 (clamped 1.0–2.5). */
    private final double         uiScale;

    private final BorderPane     rootPane;
    private final List<Step>     steps;
    private       int            currentStep = 0;
    private       Runnable       onFinish;

    // Overlay layer
    private final Pane           overlay      = new Pane();
    private final Canvas         canvas       = new Canvas();

    // Card widgets
    private final VBox           card         = new VBox(10);
    private final Label          stepCounter  = new Label();
    private final Label          iconLabel    = new Label();
    private final Label          title        = new Label();
    private final Label          body         = new Label();
    private final HBox           dotsRow      = new HBox(9);
    private final Rectangle      progressFill = new Rectangle(0, 5);
    private final Button         nextBtn      = new Button();
    private final Button         backBtn      = new Button();
    private final Button         skipBtn      = new Button();

    // Pulsing glow animation
    private final DoubleProperty pulsePhase   = new SimpleDoubleProperty(0.0);
    private Timeline             pulseTimeline;

    // Last computed spotlight bounds (canvas-space) for click-detection
    private double               spotX, spotY, spotW, spotH;

    // ── Constructor ───────────────────────────────────────────────────────────

    public TutorialOverlay(BorderPane rootPane, List<Step> steps) {
        this.rootPane = rootPane;
        this.steps    = List.copyOf(steps);

        javafx.geometry.Rectangle2D sb = Screen.getPrimary().getBounds();
        this.uiScale = Math.max(1.0, Math.min(2.5,
            Math.min(sb.getWidth() / 1920.0, sb.getHeight() / 1080.0)));

        buildCard();
        wireCanvas();
        buildPulseAnimation();

        overlay.setPickOnBounds(false);
        overlay.getChildren().addAll(canvas, card);
        overlay.prefWidthProperty().bind(rootPane.widthProperty());
        overlay.prefHeightProperty().bind(rootPane.heightProperty());

        // ── Keyboard navigation ───────────────────────────────────────────────
        overlay.setFocusTraversable(true);
        overlay.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE                  -> skip();
                case ENTER, RIGHT, PAGE_DOWN -> advance();
                case LEFT,  PAGE_UP          -> goBack();
                default -> { /* ignore */ }
            }
            e.consume();
        });

        I18n.addChangeListener(() -> Platform.runLater(this::applyI18n));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setOnFinish(Runnable r) { onFinish = r; }

    /** Returns true if the overlay is currently visible. */
    public boolean isShowing() {
        return overlay.isVisible() && overlay.getParent() != null;
    }

    /**
     * Actualiza todos los textos de la tarjeta al idioma activo.
     * Llamar desde el hilo de JavaFX.
     */
    public void applyI18n() {
        nextBtn.setText(currentStep == steps.size() - 1
            ? I18n.t("btn.finish") : I18n.t("btn.next") + " →");
        backBtn.setText("← " + I18n.t("btn.back"));
        skipBtn.setText(I18n.t("btn.skip"));
        if (isShowing()) renderStep(currentStep, true);
    }

    /** Muestra el tutorial desde el primer paso. */
    public void show() {
        if (!rootPane.getChildren().contains(overlay)) {
            rootPane.getChildren().add(overlay);
        }
        overlay.setOpacity(0);
        overlay.setVisible(true);
        currentStep = 0;
        progressFill.setWidth(0);

        Platform.runLater(() -> {
            renderStep(currentStep, true);
            FadeTransition ft = new FadeTransition(Duration.millis(300), overlay);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.setOnFinished(e -> {
                overlay.requestFocus();
                pulseTimeline.play();
            });
            ft.play();
        });
    }

    /** Oculta y elimina el overlay con fade-out. */
    public void hide() {
        pulseTimeline.stop();
        FadeTransition ft = new FadeTransition(Duration.millis(200), overlay);
        ft.setFromValue(overlay.getOpacity());
        ft.setToValue(0);
        ft.setOnFinished(e -> {
            overlay.setVisible(false);
            rootPane.getChildren().remove(overlay);
        });
        ft.play();
        markSeen();
        if (onFinish != null) onFinish.run();
    }

    // ── Preferences helpers ───────────────────────────────────────────────────

    public static boolean hasSeenTutorial() {
        try {
            return Preferences.userNodeForPackage(TutorialOverlay.class)
                              .getBoolean(PREFS_KEY, false);
        } catch (Exception e) { return false; }
    }

    public static void markSeen() {
        try {
            Preferences.userNodeForPackage(TutorialOverlay.class)
                       .putBoolean(PREFS_KEY, true);
        } catch (Exception ignored) {}
    }

    public static void resetSeen() {
        try {
            Preferences.userNodeForPackage(TutorialOverlay.class)
                       .remove(PREFS_KEY);
        } catch (Exception ignored) {}
    }

    // ── Step navigation ───────────────────────────────────────────────────────

    private void advance() {
        currentStep++;
        if (currentStep >= steps.size()) {
            hide();
        } else {
            renderStep(currentStep, true);
        }
    }

    private void goBack() {
        if (currentStep > 0) {
            currentStep--;
            renderStep(currentStep, false);
        }
    }

    private void skip() { hide(); }

    // ── Step rendering ────────────────────────────────────────────────────────

    private void renderStep(int index, boolean forward) {
        Step step  = steps.get(index);
        int  total = steps.size();

        // ── Update text content ───────────────────────────────────────────────
        stepCounter.setText((index + 1) + " / " + total);
        iconLabel.setText(step.icon());
        title.setText(I18n.t(step.titleKey()));
        body.setText(I18n.t(step.bodyKey()));
        nextBtn.setText(index == total - 1 ? I18n.t("btn.finish") : I18n.t("btn.next") + " →");
        backBtn.setText("← " + I18n.t("btn.back"));
        skipBtn.setText(I18n.t("btn.skip"));
        backBtn.setDisable(index == 0);
        backBtn.setVisible(index > 0);
        rebuildDots(index, total);

        // ── Spotlight & position ──────────────────────────────────────────────
        drawSpotlight(step.target());
        positionCard(step.target());

        // ── Progress fill (animado) ───────────────────────────────────────────
        animateProgress((double)(index + 1) / total);

        // ── Card: slide-in + fade ─────────────────────────────────────────────
        double dx = 36.0 * (forward ? 1 : -1);
        card.setOpacity(0);
        card.setTranslateX(dx);
        card.setTranslateY(0);

        // ── Icon bounce ───────────────────────────────────────────────────────
        iconLabel.setScaleX(0.60);
        iconLabel.setScaleY(0.60);

        Timeline bounce = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(iconLabel.scaleXProperty(), 0.60, Interpolator.EASE_OUT),
                new KeyValue(iconLabel.scaleYProperty(), 0.60, Interpolator.EASE_OUT)),
            new KeyFrame(Duration.millis(260),
                new KeyValue(iconLabel.scaleXProperty(), 1.15, Interpolator.EASE_OUT),
                new KeyValue(iconLabel.scaleYProperty(), 1.15, Interpolator.EASE_OUT)),
            new KeyFrame(Duration.millis(390),
                new KeyValue(iconLabel.scaleXProperty(), 1.0,  Interpolator.EASE_IN),
                new KeyValue(iconLabel.scaleYProperty(), 1.0,  Interpolator.EASE_IN))
        );

        Timeline slide = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(card.opacityProperty(),    0,   Interpolator.EASE_OUT),
                new KeyValue(card.translateXProperty(), dx,  Interpolator.EASE_OUT)),
            new KeyFrame(Duration.millis(240),
                new KeyValue(card.opacityProperty(),    1,   Interpolator.EASE_OUT),
                new KeyValue(card.translateXProperty(), 0,   Interpolator.EASE_OUT))
        );

        new ParallelTransition(bounce, slide).play();
    }

    /** Anima el fill de la barra de progreso hasta el porcentaje indicado. */
    private void animateProgress(double targetPct) {
        // Ancho máximo: TOOLTIP_W menos padding horizontal de la tarjeta (28px × 2)
        double maxW  = Math.max(50, TOOLTIP_W - 56);
        double fromW = progressFill.getWidth();
        double toW   = targetPct * maxW;
        new Timeline(
            new KeyFrame(Duration.ZERO,        new KeyValue(progressFill.widthProperty(), fromW)),
            new KeyFrame(Duration.millis(380),  new KeyValue(progressFill.widthProperty(), toW, Interpolator.EASE_BOTH))
        ).play();
    }

    private void redrawIfVisible() {
        if (overlay.isVisible() && currentStep < steps.size()) {
            drawSpotlight(steps.get(currentStep).target());
            positionCard(steps.get(currentStep).target());
        }
    }

    // ── Pulsing spotlight ─────────────────────────────────────────────────────

    private void buildPulseAnimation() {
        pulseTimeline = new Timeline(
            new KeyFrame(Duration.ZERO,          new KeyValue(pulsePhase, 0.0, Interpolator.EASE_BOTH)),
            new KeyFrame(Duration.millis(1400),   new KeyValue(pulsePhase, 1.0, Interpolator.EASE_BOTH)),
            new KeyFrame(Duration.millis(2800),   new KeyValue(pulsePhase, 0.0, Interpolator.EASE_BOTH))
        );
        pulseTimeline.setCycleCount(Animation.INDEFINITE);
        // Cada tick del pulso redibuja el spotlight
        pulsePhase.addListener((obs, o, n) -> {
            if (overlay.isVisible() && currentStep < steps.size()) {
                drawSpotlight(steps.get(currentStep).target());
            }
        });
    }

    private void drawSpotlight(Node target) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        // Oscurecer toda la pantalla
        gc.setFill(Color.rgb(0, 0, 0, OVERLAY_ALPHA));
        gc.fillRect(0, 0, w, h);

        if (target == null) { spotW = 0; return; }

        Bounds bScene = target.localToScene(target.getBoundsInLocal());
        Bounds bRoot  = rootPane.sceneToLocal(bScene);

        double pad = SPOT_PAD * uiScale;
        spotX = Math.max(0, bRoot.getMinX() - pad);
        spotY = Math.max(0, bRoot.getMinY() - pad);
        spotW = Math.min(bRoot.getWidth()  + pad * 2, w - spotX);
        spotH = Math.min(bRoot.getHeight() + pad * 2, h - spotY);

        // Recorte del spotlight (zona iluminada)
        gc.clearRect(spotX, spotY, spotW, spotH);

        double p  = pulsePhase.get(); // 0.0 → 1.0 → 0.0
        double lw = uiScale;

        // ── Anillo exterior: glow mauve pulsante ──────────────────────────────
        double glowAlpha = 0.12 + p * 0.30;
        double glowWidth = (10 + p * 10) * lw;
        gc.setStroke(Color.rgb(132, 84, 96, glowAlpha));
        gc.setLineWidth(glowWidth);
        gc.strokeRoundRect(spotX - 5*lw, spotY - 5*lw, spotW + 10*lw, spotH + 10*lw, 22*lw, 22*lw);

        // ── Anillo medio: teal pulsante ───────────────────────────────────────
        double midAlpha = 0.30 + p * 0.40;
        double midWidth = (3 + p * 2.5) * lw;
        gc.setStroke(Color.rgb(61, 104, 120, midAlpha));
        gc.setLineWidth(midWidth);
        gc.strokeRoundRect(spotX - 2*lw, spotY - 2*lw, spotW + 4*lw, spotH + 4*lw, 16*lw, 16*lw);

        // ── Borde nítido mauve claro ──────────────────────────────────────────
        double borderAlpha = 0.65 + p * 0.35;
        gc.setStroke(Color.rgb(184, 128, 144, borderAlpha));
        gc.setLineWidth(2 * lw);
        gc.strokeRoundRect(spotX - lw, spotY - lw, spotW + 2*lw, spotH + 2*lw, 14*lw, 14*lw);
    }

    /** Devuelve true si el punto (mx, my) está dentro del spotlight iluminado. */
    private boolean isInSpotlight(double mx, double my) {
        return spotW > 0
            && mx >= spotX && mx <= spotX + spotW
            && my >= spotY && my <= spotY + spotH;
    }

    // ── Card positioning ──────────────────────────────────────────────────────

    /**
     * Coloca la tarjeta de forma que no solape el spotlight.
     * Prioridad: abajo → arriba → derecha → izquierda.
     */
    private void positionCard(Node target) {
        double rw    = canvas.getWidth();
        double rh    = canvas.getHeight();
        double maxTw = rw > 0 ? Math.min(TOOLTIP_W, rw / Math.max(1.0, uiScale) * 0.88) : TOOLTIP_W;
        double tw    = Math.max(200, maxTw);
        card.setPrefWidth(tw);
        card.setMaxWidth(tw);

        card.applyCss();
        card.layout();

        double th     = Math.max(160, card.prefHeight(tw));
        double tw_vis = tw * uiScale;
        double th_vis = th * uiScale;
        double gap    = TOOLTIP_GAP * uiScale;
        double margin = 14 * uiScale;

        if (target == null) {
            // Paso de bienvenida/fin: centrar
            card.setLayoutX((rw - tw_vis) / 2.0);
            card.setLayoutY((rh - th_vis) / 2.0);
            return;
        }

        Bounds bRoot = rootPane.sceneToLocal(target.localToScene(target.getBoundsInLocal()));
        double pad = SPOT_PAD * uiScale;
        double sx  = bRoot.getMinX() - pad;
        double sy  = bRoot.getMinY() - pad;
        double sw  = bRoot.getWidth()  + pad * 2;
        double sh  = bRoot.getHeight() + pad * 2;
        double scx = sx + sw / 2.0;
        double scy = sy + sh / 2.0;

        double belowTX = clamp(scx - tw_vis/2.0, margin, rw - tw_vis - margin);
        double belowTY = sy + sh + gap;
        boolean belowOk = belowTY + th_vis <= rh - margin;

        double aboveTX = clamp(scx - tw_vis/2.0, margin, rw - tw_vis - margin);
        double aboveTY = sy - gap - th_vis;
        boolean aboveOk = aboveTY >= margin;

        double rightTX = sx + sw + gap;
        double rightTY = clamp(scy - th_vis/2.0, margin, rh - th_vis - margin);
        boolean rightOk = rightTX + tw_vis <= rw - margin;

        double leftTX = sx - gap - tw_vis;
        double leftTY = clamp(scy - th_vis/2.0, margin, rh - th_vis - margin);
        boolean leftOk = leftTX >= margin;

        double tx, ty;
        if      (belowOk) { tx = belowTX; ty = belowTY; }
        else if (aboveOk) { tx = aboveTX; ty = aboveTY; }
        else if (rightOk) { tx = rightTX; ty = rightTY; }
        else if (leftOk)  { tx = leftTX;  ty = leftTY;  }
        else {
            tx = clamp(scx - tw_vis/2.0, margin, rw - tw_vis - margin);
            ty = clamp(sy + sh + gap, margin, rh - th_vis - margin);
        }

        card.setLayoutX(tx);
        card.setLayoutY(ty);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildCard() {
        // Contador de paso ("2 / 8")
        stepCounter.getStyleClass().add("tutorial-step-counter");
        stepCounter.setMaxWidth(Double.MAX_VALUE);
        stepCounter.setAlignment(Pos.CENTER);

        // Icono
        iconLabel.getStyleClass().add("tutorial-icon");
        iconLabel.setMaxWidth(Double.MAX_VALUE);
        iconLabel.setAlignment(Pos.CENTER);

        // Título
        title.getStyleClass().add("tutorial-title");
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        // Cuerpo
        body.getStyleClass().add("tutorial-body");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.setAlignment(Pos.CENTER);

        // Dots de navegación
        dotsRow.setAlignment(Pos.CENTER);
        dotsRow.setMaxWidth(Double.MAX_VALUE);

        // ── Barra de progreso ─────────────────────────────────────────────────
        StackPane progressContainer = new StackPane();
        progressContainer.setAlignment(Pos.CENTER_LEFT);
        progressContainer.setMaxWidth(Double.MAX_VALUE);
        progressContainer.setMinHeight(5);
        progressContainer.setMaxHeight(5);

        Rectangle progressBg = new Rectangle(0, 5);
        progressBg.setFill(Color.web("#1c3344"));
        progressBg.setArcWidth(4);
        progressBg.setArcHeight(4);
        progressBg.widthProperty().bind(progressContainer.widthProperty());

        progressFill.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0.0, Color.web("#845460")),
            new Stop(1.0, Color.web("#b88090"))
        ));
        progressFill.setArcWidth(4);
        progressFill.setArcHeight(4);
        progressFill.setWidth(0);
        StackPane.setAlignment(progressFill, Pos.CENTER_LEFT);
        progressContainer.getChildren().addAll(progressBg, progressFill);

        // ── Botones ───────────────────────────────────────────────────────────
        nextBtn.setText(I18n.t("btn.next") + " →");
        backBtn.setText("← " + I18n.t("btn.back"));
        skipBtn.setText(I18n.t("btn.skip"));
        nextBtn.getStyleClass().add("tutorial-next-btn");
        backBtn.getStyleClass().add("tutorial-back-btn");
        skipBtn.getStyleClass().add("tutorial-skip-btn");
        nextBtn.setOnAction(e -> advance());
        backBtn.setOnAction(e -> goBack());
        skipBtn.setOnAction(e -> skip());
        nextBtn.setDefaultButton(true);
        backBtn.setVisible(false);
        backBtn.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox btnRow = new HBox(8, skipBtn, spacer, backBtn, nextBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        // ── Hint de teclado ───────────────────────────────────────────────────
        Label keyHint = new Label("← → Enter  ·  Esc");
        keyHint.getStyleClass().add("tutorial-key-hint");
        keyHint.setMaxWidth(Double.MAX_VALUE);
        keyHint.setAlignment(Pos.CENTER);

        // ── Montar tarjeta ────────────────────────────────────────────────────
        card.getStyleClass().add("tutorial-card");
        card.setAlignment(Pos.CENTER);
        card.getChildren().addAll(
            stepCounter, iconLabel, title, body,
            dotsRow, progressContainer, btnRow, keyHint
        );
        card.setPrefWidth(TOOLTIP_W);
        card.setMaxWidth(TOOLTIP_W);
        card.setMinWidth(200);

        if (uiScale > 1.01) {
            card.getTransforms().add(new Scale(uiScale, uiScale, 0, 0));
        }
    }

    private void rebuildDots(int currentIdx, int total) {
        dotsRow.getChildren().clear();
        for (int i = 0; i < total; i++) {
            boolean active = (i == currentIdx);
            Circle dot = new Circle(active ? 5.5 : 4.0);
            dot.setFill(active ? Color.web("#845460") : Color.web("#2B4F60"));
            if (active) {
                dot.setStroke(Color.web("#b88090"));
                dot.setStrokeWidth(1.5);
            }
            final int idx = i;
            dot.setCursor(Cursor.HAND);
            dot.setOnMouseClicked(e -> {
                if (idx != currentStep) {
                    boolean fwd = idx > currentStep;
                    currentStep = idx;
                    renderStep(currentStep, fwd);
                }
                e.consume();
            });
            dot.setOnMouseEntered(e -> { if (!active) dot.setFill(Color.web("#3D6878")); });
            dot.setOnMouseExited (e -> { if (!active) dot.setFill(Color.web("#2B4F60")); });
            dotsRow.getChildren().add(dot);
        }
    }

    private void wireCanvas() {
        canvas.widthProperty().bind(rootPane.widthProperty());
        canvas.heightProperty().bind(rootPane.heightProperty());
        canvas.widthProperty() .addListener(obs -> redrawIfVisible());
        canvas.heightProperty().addListener(obs -> redrawIfVisible());

        // Clic en el spotlight → avanzar; fuera → bloquear interacción con la app
        canvas.setOnMouseClicked(e -> {
            if (isInSpotlight(e.getX(), e.getY())) advance();
            e.consume();
        });

        // Cambiar cursor a HAND cuando se pasa sobre el spotlight
        canvas.setOnMouseMoved(e ->
            canvas.setCursor(isInSpotlight(e.getX(), e.getY()) ? Cursor.HAND : Cursor.DEFAULT)
        );

        canvas.setOnMousePressed (e -> e.consume());
        canvas.setOnMouseReleased(e -> e.consume());
        canvas.setOnScroll       (e -> e.consume());
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
