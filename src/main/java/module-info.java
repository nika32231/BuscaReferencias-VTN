module org.refcolor.buscareferencias {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires java.logging;
    requires java.sql;
    requires org.xerial.sqlitejdbc;
    requires org.slf4j;

    requires org.json;
    requires java.prefs;

    opens org.refcolor.buscareferencias to javafx.fxml;
    opens org.refcolor.buscareferencias.tutorial to javafx.fxml;
    opens org.refcolor.buscareferencias.controller to javafx.fxml;
    exports org.refcolor.buscareferencias;
    exports org.refcolor.buscareferencias.model;
    exports org.refcolor.buscareferencias.controller;
}