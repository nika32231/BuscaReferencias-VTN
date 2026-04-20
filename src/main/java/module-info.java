module org.refcolor.buscareferencias {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.logging;
    requires java.sql;
    requires org.slf4j;

    opens org.refcolor.buscareferencias to javafx.fxml;
    opens org.refcolor.buscareferencias.controller to javafx.fxml;
    exports org.refcolor.buscareferencias;
    exports org.refcolor.buscareferencias.model;
    exports org.refcolor.buscareferencias.controller;
}