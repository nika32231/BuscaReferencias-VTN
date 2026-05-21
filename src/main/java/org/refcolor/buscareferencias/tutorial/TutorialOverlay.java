package org.refcolor.buscareferencias.tutorial;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
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
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.util.Duration;

import java.util.List;
import java.util.prefs.Preferences;
import org.refcolor.buscareferencias.i18n.I18n;

/**
 * Overlay de tutorial interactivo estilo "spotlight".
 *
 * Oscurece toda la pantalla menos el nodo objetivo, dibuja un borde
 * violeta a su alrededor y muestra una tarjeta con título, descripción
 * y botones Siguiente / Saltar.
 *
 * La primera vez que se muestra se guarda en Preferences para no
 * repetirse; el botón "?" del toolbar puede forzar la reaparición.
 */
public class TutorialOverlay {

    // ── Record for each step ──────────────────────────────────────────────────

    /**
     * Cada paso del tutorial.
     * {@code titleKey} y {@code bodyKey} son claves i18n resueltas vía {@link I18n#t(String)}.
     */
    public record Step(Node target, String icon, String titleKey, String bodyKey) {
        /** Step without a specific node (welcome / finish screens). */
        public Step(String icon, String titleKey, String bodyKey) { this(null, icon, titleKey, bodyKey); }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String  PREFS_KEY     = "tutorial_seen_v1";
    private static final double  OVERLAY_ALPHA = 0.74;
    private static final double  SPOT_PAD      = 13.0;
    private static final double  TOOLTIP_W     = 400.0;
    private static final double  TOOLTIP_GAP   = 20.0;

    // ── Fields ────────────────────────────────────────────────────────────────

    /** HiDPI scale factor relative to 1920×1080 baseline (clamped 1.0–2.5). */
    private final double         uiScale;

    private final BorderPane     rootPane;
    private final List<Step>     steps;
    private       int            currentStep = 0;
    private       Runnable       onFinish;

    // Overlay container (added on top of rootPane children)
    private final Pane    overlay   = new Pane();
    private final Canvas  canvas    = new Canvas();

    // Tooltip card
    private final VBox    card      = new VBox(12);
    private final Label   iconLabel = new Label();
    private final Label   title     = new Label();
    private final Label   body      = new Label();
    private final HBox    dotsRow   = new HBox(7);
    private final Button  nextBtn   = new Button("Siguiente →");
    private final Button  backBtn   = new Button("← Atrás");
    private final Button  skipBtn   = new Button("Saltar");

    // ── Constructor ───────────────────────────────────────────────────────────

    public TutorialOverlay(BorderPane rootPane, List<Step> steps) {
        this.rootPane = rootPane;
        this.steps    = List.copyOf(steps);

        // Compute HiDPI scale relative to 1920×1080 baseline
        javafx.geometry.Rectangle2D sb = Screen.getPrimary().getBounds();
        this.uiScale = Math.max(1.0, Math.min(2.5,
            Math.min(sb.getWidth() / 1920.0, sb.getHeight() / 1080.0)));

        buildCard();
        wireCanvas();

        overlay.setPickOnBounds(false);
        overlay.getChildren().addAll(canvas, card);
        overlay.prefWidthProperty().bind(rootPane.widthProperty());
        overlay.prefHeightProperty().bind(rootPane.heightProperty());

        // Actualizar textos cuando cambia el idioma
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
        nextBtn.setText(currentStep == steps.size() - 1 ? I18n.t("btn.finish") : I18n.t("btn.next"));
        backBtn.setText(I18n.t("btn.back"));
        skipBtn.setText(I18n.t("btn.skip"));
        if (isShowing()) {
            renderStep(currentStep);
        }
    }

    /** Shows the tutorial from the first step. */
    public void show() {
        if (!rootPane.getChildren().contains(overlay)) {
            rootPane.getChildren().add(overlay);
        }
        overlay.setOpacity(0);
        overlay.setVisible(true);
        currentStep = 0;

        // Defer first render until layout is available
        Platform.runLater(() -> {
            renderStep(currentStep);
            FadeTransition ft = new FadeTransition(Duration.millis(280), overlay);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();
        });
    }

    /** Hides and removes the overlay with a fade-out. */
    public void hide() {
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

    // ── Step logic ────────────────────────────────────────────────────────────

    private void advance() {
        currentStep++;
        if (currentStep >= steps.size()) {
            hide();
        } else {
            renderStep(currentStep);
        }
    }

    private void goBack() {
        if (currentStep > 0) {
            currentStep--;
            renderStep(currentStep);
        }
    }

    private void skip() { hide(); }

    private void renderStep(int index) {
        Step step = steps.get(index);
        int  total = steps.size();

        iconLabel.setText(step.icon());
        title.setText(I18n.t(step.titleKey()));
        body.setText(I18n.t(step.bodyKey()));
        nextBtn.setText(index == total - 1 ? I18n.t("btn.finish") : I18n.t("btn.next"));
        backBtn.setText(I18n.t("btn.back"));
        skipBtn.setText(I18n.t("btn.skip"));
        backBtn.setDisable(index == 0);
        backBtn.setVisible(index > 0);
        rebuildDots(index, total);

        // Animate card fade + slight slide
        card.setOpacity(0);
        card.setTranslateY(8);

        drawSpotlight(step.target());
        positionCard(step.target());

        Timeline anim = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(card.opacityProperty(),    0),
                new KeyValue(card.translateYProperty(), 8)
            ),
            new KeyFrame(Duration.millis(220),
                new KeyValue(card.opacityProperty(),    1),
                new KeyValue(card.translateYProperty(), 0)
            )
        );
        anim.play();
    }

    private void redrawIfVisible() {
        if (overlay.isVisible() && currentStep < steps.size()) {
            drawSpotlight(steps.get(currentStep).target());
            positionCard(steps.get(currentStep).target());
        }
    }

    // ── Spotlight drawing ─────────────────────────────────────────────────────

    private void drawSpotlight(Node target) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        // Dark overlay
        gc.setFill(Color.rgb(0, 0, 0, OVERLAY_ALPHA));
        gc.fillRect(0, 0, w, h);

        if (target == null) return; // welcome / finish: full dark

        Bounds bScene = target.localToScene(target.getBoundsInLocal());
        Bounds bRoot  = rootPane.sceneToLocal(bScene);

        double pad = SPOT_PAD * uiScale;
        double sx = bRoot.getMinX() - pad;
        double sy = bRoot.getMinY() - pad;
        double sw = bRoot.getWidth()  + pad * 2;
        double sh = bRoot.getHeight() + pad * 2;

        // Clamp
        sx = Math.max(0, sx);  sy = Math.max(0, sy);
        sw = Math.min(sw, w - sx);  sh = Math.min(sh, h - sy);

        // Transparent spotlight cutout
        gc.clearRect(sx, sy, sw, sh);

        double lw = uiScale; // line-width scale

        // Outer soft glow ring (on the dark overlay, just outside cutout)
        gc.setStroke(Color.rgb(167, 139, 250, 0.22));
        gc.setLineWidth(10 * lw);
        gc.strokeRoundRect(sx - 3*lw, sy - 3*lw, sw + 6*lw, sh + 6*lw, 18*lw, 18*lw);

        // Medium glow
        gc.setStroke(Color.rgb(167, 139, 250, 0.50));
        gc.setLineWidth(4 * lw);
        gc.strokeRoundRect(sx - 2*lw, sy - 2*lw, sw + 4*lw, sh + 4*lw, 16*lw, 16*lw);

        // Sharp violet border
        gc.setStroke(Color.rgb(185, 160, 255, 0.98));
        gc.setLineWidth(2 * lw);
        gc.strokeRoundRect(sx - lw, sy - lw, sw + 2*lw, sh + 2*lw, 14*lw, 14*lw);
    }

    // ── Card positioning ──────────────────────────────────────────────────────

    /**
     * Places the card so it never overlaps the spotlight rectangle.
     * Tries 4 positions in priority order: below → above → right → left.
     * Fit checks use visual (scaled) dimensions; layoutX/Y match the visual
     * top-left because the Scale pivot is at the card's (0, 0).
     */
    private void positionCard(Node target) {
        card.applyCss();
        card.layout();

        double rw     = canvas.getWidth();
        double rh     = canvas.getHeight();
        double tw     = TOOLTIP_W;                          // layout width (unscaled)
        double th     = Math.max(160, card.prefHeight(tw)); // layout height (unscaled)
        double tw_vis = tw * uiScale;                       // visual width
        double th_vis = th * uiScale;                       // visual height
        double gap    = TOOLTIP_GAP * uiScale;
        double margin = 14 * uiScale;

        if (target == null) {
            // Welcome / finish step — centred, no spotlight to avoid
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

        double spotCX = sx + sw / 2.0;
        double spotCY = sy + sh / 2.0;

        // ── Candidate positions ───────────────────────────────────────────────

        // Below spotlight, horizontally centred on it
        double belowTY = sy + sh + gap;
        double belowTX = clamp(spotCX - tw_vis / 2.0, margin, rw - tw_vis - margin);
        boolean belowOk = belowTY + th_vis <= rh - margin;

        // Above spotlight, horizontally centred on it
        double aboveTY = sy - gap - th_vis;
        double aboveTX = clamp(spotCX - tw_vis / 2.0, margin, rw - tw_vis - margin);
        boolean aboveOk = aboveTY >= margin;

        // Right of spotlight, vertically centred on it
        double rightTX = sx + sw + gap;
        double rightTY = clamp(spotCY - th_vis / 2.0, margin, rh - th_vis - margin);
        boolean rightOk = rightTX + tw_vis <= rw - margin;

        // Left of spotlight, vertically centred on it
        double leftTX = sx - gap - tw_vis;
        double leftTY = clamp(spotCY - th_vis / 2.0, margin, rh - th_vis - margin);
        boolean leftOk = leftTX >= margin;

        // ── Pick first fitting candidate ──────────────────────────────────────
        double tx, ty;
        if      (belowOk) { tx = belowTX; ty = belowTY; }
        else if (aboveOk) { tx = aboveTX; ty = aboveTY; }
        else if (rightOk) { tx = rightTX; ty = rightTY; }
        else if (leftOk)  { tx = leftTX;  ty = leftTY;  }
        else {
            // Last resort: as far below as possible, at least partially visible
            tx = clamp(spotCX - tw_vis / 2.0, margin, rw - tw_vis - margin);
            ty = clamp(sy + sh + gap, margin, rh - th_vis - margin);
        }

        card.setLayoutX(tx);
        card.setLayoutY(ty);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildCard() {
        // Icon
        iconLabel.getStyleClass().add("tutorial-icon");
        iconLabel.setMaxWidth(Double.MAX_VALUE);
        iconLabel.setAlignment(Pos.CENTER);

        // Title
        title.getStyleClass().add("tutorial-title");
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        // Body
        body.getStyleClass().add("tutorial-body");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.setAlignment(Pos.CENTER);

        // Dots
        dotsRow.setAlignment(Pos.CENTER);
        dotsRow.setMaxWidth(Double.MAX_VALUE);

        // Buttons (initial texts from current locale)
        nextBtn.setText(I18n.t("btn.next"));
        backBtn.setText(I18n.t("btn.back"));
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

        card.getStyleClass().add("tutorial-card");
        card.setAlignment(Pos.CENTER);
        card.getChildren().addAll(iconLabel, title, body, dotsRow, btnRow);
        card.setPrefWidth(TOOLTIP_W);
        card.setMaxWidth(TOOLTIP_W);
        card.setMinWidth(TOOLTIP_W);

        // Scale card for HiDPI screens (pivot at top-left so layoutX/Y stay as visual origin)
        if (uiScale > 1.01) {
            card.getTransforms().add(new Scale(uiScale, uiScale, 0, 0));
        }
    }

    private void rebuildDots(int currentIdx, int total) {
        dotsRow.getChildren().clear();
        for (int i = 0; i < total; i++) {
            boolean active = (i == currentIdx);
            Circle dot = new Circle(active ? 5.5 : 4);
            dot.setFill(active ? Color.web("#a78bfa") : Color.web("#2e2850"));
            if (active) {
                dot.setStroke(Color.web("#c4b0ff"));
                dot.setStrokeWidth(1.5);
            }
            dotsRow.getChildren().add(dot);
        }
    }

    private void wireCanvas() {
        canvas.widthProperty().bind(rootPane.widthProperty());
        canvas.heightProperty().bind(rootPane.heightProperty());
        canvas.widthProperty() .addListener(obs -> redrawIfVisible());
        canvas.heightProperty().addListener(obs -> redrawIfVisible());

        // Block all mouse events reaching the app while tutorial is open
        canvas.setOnMouseClicked(e -> e.consume());
        canvas.setOnMousePressed(e -> e.consume());
        canvas.setOnMouseReleased(e -> e.consume());
        canvas.setOnScroll(e -> e.consume());
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
