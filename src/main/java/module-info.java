module org.refcolor.buscareferencias {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires java.logging;
    requires java.sql;
    requires java.net.http;
    requires org.xerial.sqlitejdbc;
    requires org.slf4j;

    requires org.json;
    // Playwright (com.microsoft.playwright) es una librería orientada a classpath.
    // En proyectos JPMS puede dar "module not found" si el IDE/build no la pone en el module-path.
    // Para mantener el módulo compilable, no lo declaramos aquí y lo usamos desde tests/classpath o bridge Python.
    // requires com.microsoft.playwright;

    opens org.refcolor.buscareferencias to javafx.fxml;
    opens org.refcolor.buscareferencias.controller to javafx.fxml;
    exports org.refcolor.buscareferencias;
    exports org.refcolor.buscareferencias.model;
    exports org.refcolor.buscareferencias.controller;
}