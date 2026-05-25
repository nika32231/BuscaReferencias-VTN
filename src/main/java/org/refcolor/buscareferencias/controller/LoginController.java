package org.refcolor.buscareferencias.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.refcolor.buscareferencias.auth.UserManager;
import org.refcolor.buscareferencias.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controlador para la pantalla de Login / Registro.
 * Cambia dinámicamente entre modo login y modo registro.
 */
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    @FXML private Label        lblTitle;
    @FXML private Label        lblSubtitle;
    @FXML private TextField    txtUsername;
    @FXML private PasswordField txtPassword;
    @FXML private TextField    txtPasswordVisible;
    @FXML private CheckBox     chkShowPassword;
    @FXML private Label        lblConfirm;
    @FXML private StackPane    stkConfirm;
    @FXML private PasswordField txtConfirm;
    @FXML private TextField    txtConfirmVisible;
    @FXML private Label        lblMessage;
    @FXML private Button       btnAction;
    @FXML private Label        lblSwitchText;
    @FXML private Hyperlink    lnkSwitch;

    private Stage    stage;
    private boolean  registerMode = false;
    private Runnable onSuccess;
    private Runnable onBack;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        // Enviar con Enter en los campos
        txtPassword.setOnAction(e -> handleAction());
        txtPasswordVisible.setOnAction(e -> handleAction());
        txtConfirm.setOnAction(e -> handleAction());
        txtConfirmVisible.setOnAction(e -> handleAction());
        txtUsername.setOnAction(e -> txtPassword.requestFocus());

        // Sincronizar texto entre PasswordField y TextField para show/hide
        txtPasswordVisible.textProperty().addListener((obs, o, n) -> {
            if (!txtPassword.getText().equals(n)) txtPassword.setText(n);
        });
        txtPassword.textProperty().addListener((obs, o, n) -> {
            if (!txtPasswordVisible.getText().equals(n)) txtPasswordVisible.setText(n);
        });
        txtConfirmVisible.textProperty().addListener((obs, o, n) -> {
            if (!txtConfirm.getText().equals(n)) txtConfirm.setText(n);
        });
        txtConfirm.textProperty().addListener((obs, o, n) -> {
            if (!txtConfirmVisible.getText().equals(n)) txtConfirmVisible.setText(n);
        });

        // Traducir etiqueta del checkbox según idioma
        chkShowPassword.setText(I18n.isEnglish() ? "Show password" : "Mostrar contraseña");
    }

    @FXML
    private void handleShowPassword() {
        boolean show = chkShowPassword.isSelected();
        // Contraseña principal
        txtPassword.setVisible(!show);  txtPassword.setManaged(!show);
        txtPasswordVisible.setVisible(show); txtPasswordVisible.setManaged(show);
        if (show) txtPasswordVisible.requestFocus(); else txtPassword.requestFocus();
        // Confirmación (si está visible)
        if (stkConfirm.isVisible()) {
            txtConfirm.setVisible(!show);        txtConfirm.setManaged(!show);
            txtConfirmVisible.setVisible(show);  txtConfirmVisible.setManaged(show);
        }
    }

    public void setStage(Stage stage)         { this.stage = stage; }
    public void setOnSuccess(Runnable r)       { this.onSuccess = r; }
    public void setOnBack(Runnable r)          { this.onBack = r; }

    public void setRegisterMode(boolean reg) {
        this.registerMode = reg;
        updateUiForMode();
    }

    private void updateUiForMode() {
        boolean en = I18n.isEnglish();
        if (registerMode) {
            lblTitle.setText(en ? "Create account"                     : "Crear cuenta");
            lblSubtitle.setText(en ? "Create your profile to save your history"
                                   : "Crea tu perfil para guardar tu historial");
            btnAction.setText(en ? "Register"     : "Registrarse");
            lblSwitchText.setText(en ? "Already have an account?" : "¿Ya tienes cuenta?");
            lnkSwitch.setText(en ? "Log in"       : "Iniciar sesión");
            lblConfirm.setVisible(true);   lblConfirm.setManaged(true);
            stkConfirm.setVisible(true);   stkConfirm.setManaged(true);
        } else {
            lblTitle.setText(en ? "Log in"                           : "Iniciar sesión");
            lblSubtitle.setText(en ? "Access your history and personalized settings"
                                   : "Accede a tu historial y ajustes personalizados");
            btnAction.setText(en ? "Log in"       : "Iniciar sesión");
            lblSwitchText.setText(en ? "Don't have an account?" : "¿No tienes cuenta?");
            lnkSwitch.setText(en ? "Create account" : "Crear cuenta");
            lblConfirm.setVisible(false);  lblConfirm.setManaged(false);
            stkConfirm.setVisible(false);  stkConfirm.setManaged(false);
        }
        lblMessage.setText("");
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    @FXML
    private void handleAction() {
        String user = txtUsername.getText().trim();
        // Leer contraseña del campo activo (PasswordField o TextField visible)
        String pass = txtPasswordVisible.isVisible()
                      ? txtPasswordVisible.getText() : txtPassword.getText();

        boolean en = I18n.isEnglish();
        if (user.isBlank()) {
            showError(en ? "Username cannot be empty." : "El nombre de usuario no puede estar vacío.");
            txtUsername.requestFocus();
            return;
        }

        if (registerMode) {
            if (pass.length() < 4) {
                showError(en ? "Password must be at least 4 characters."
                             : "La contraseña debe tener al menos 4 caracteres.");
                txtPassword.requestFocus();
                return;
            }
            String confirm = txtConfirmVisible.isVisible()
                             ? txtConfirmVisible.getText() : txtConfirm.getText();
             if (!pass.equals(confirm)) {
                 showError(en ? "Passwords do not match." : "Las contraseñas no coinciden.");
                txtConfirm.requestFocus();
                return;
            }
            doRegister(user, pass);
        } else {
            doLogin(user, pass);
        }
    }

    @FXML
    private void handleSwitch() {
        registerMode = !registerMode;
        updateUiForMode();
        txtUsername.requestFocus();
    }

    @FXML
    private void handleBack() {
        if (onBack != null) Platform.runLater(onBack);
    }

    // ── Lógica ────────────────────────────────────────────────────────────────

    private void doLogin(String user, String pass) {
        btnAction.setDisable(true);
        showInfo("Verificando...");
        new Thread(() -> {
            UserManager.AuthResult result = UserManager.login(user, pass);
            Platform.runLater(() -> {
                btnAction.setDisable(false);
                boolean e2 = I18n.isEnglish();
                switch (result) {
                    case SUCCESS -> { if (onSuccess != null) onSuccess.run(); }
                    case WRONG_PASSWORD -> showError(e2 ? "Incorrect password." : "Contraseña incorrecta.");
                    case USER_NOT_FOUND -> showError(e2 ? "User not found." : "Usuario no encontrado.");
                    default -> showError(e2 ? "Login error. Please try again." : "Error al iniciar sesión. Inténtalo de nuevo.");
                }
            });
        }, "login-thread").start();
    }

    private void doRegister(String user, String pass) {
        btnAction.setDisable(true);
        showInfo("Creando cuenta...");
        new Thread(() -> {
            UserManager.AuthResult result = UserManager.register(user, pass);
            Platform.runLater(() -> {
                btnAction.setDisable(false);
                boolean e3 = I18n.isEnglish();
                switch (result) {
                    case SUCCESS -> { if (onSuccess != null) onSuccess.run(); }
                    case USER_EXISTS -> showError(e3 ? "That username is already in use." : "Ese nombre de usuario ya está en uso.");
                    case PASSWORD_TOO_SHORT -> showError(e3 ? "Password must be at least 4 characters." : "La contraseña debe tener al menos 4 caracteres.");
                    default -> showError(e3 ? "Account creation error. Please try again." : "Error al crear cuenta. Inténtalo de nuevo.");
                }
            });
        }, "register-thread").start();
    }

    private void showError(String msg) {
        lblMessage.setStyle("-fx-text-fill: #E07070; -fx-font-size: 12px;");
        lblMessage.setText(msg);
    }

    private void showInfo(String msg) {
        lblMessage.setStyle("-fx-text-fill: #BDC7C9; -fx-font-size: 12px;");
        lblMessage.setText(msg);
    }
}
