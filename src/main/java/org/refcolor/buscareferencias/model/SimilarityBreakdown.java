package org.refcolor.buscareferencias.model;

/**
 * Desglose detallado de los componentes de similitud calculados por
 * {@link org.refcolor.buscareferencias.service.MediaPipeService}.
 *
 * <p>Se almacena en {@link ImageResult} para que la vista pueda mostrar
 * al usuario por qué una foto recibió cierto porcentaje de similitud.</p>
 */
public record SimilarityBreakdown(
        /** Similitud coseno del embedding de MediaPipe (0‥1; 0 si no hay embedding). */
        double cosine,
        /** Score combinado de scorers de ángulo (dirección de brazos/piernas). */
        double angles,
        /**
         * Score de posición 2D de extremidades (dónde están manos/pies respecto al
         * centro corporal normalizado). {@code -1.0} si el dibujo no incluye manos/pies.
         */
        double positions,
        /** Similitud de posición normalizada del conjunto de joints (esqueleto). */
        double skeleton,
        /** Similitud de proporciones del contorno (bounding box). */
        double contour,
        /** Factor de cobertura (penaliza si la foto muestra más partes que el dibujo). */
        double coverage,
        /** Score final combinado después de aplicar pesos y cobertura. */
        double finalScore,
        /** {@code true} si el embedding de IA estaba disponible para esta imagen. */
        boolean hasEmbeddings,
        /** Número de joints dibujados por el usuario. */
        int jointCount,
        /** Número de landmarks de MediaPipe detectados en la imagen. */
        int landmarkCount) {

    /** Instancia vacía para imágenes sin análisis de pose. */
    public static final SimilarityBreakdown EMPTY =
            new SimilarityBreakdown(0, 0, -1.0, 0, 0, 1.0, 0.0, false, 0, 0);

    /** {@code true} si hay datos reales de análisis de pose (la imagen fue analizada). */
    public boolean hasData() { return landmarkCount > 0; }

    /** {@code true} si se calculó el score de posición de extremidades. */
    public boolean hasPositions() { return positions >= 0; }
}
