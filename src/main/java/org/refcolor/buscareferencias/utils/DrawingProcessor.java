package org.refcolor.buscareferencias.utils;

import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.refcolor.buscareferencias.model.AnatomyColors;
import org.refcolor.buscareferencias.model.PoseData;

public class DrawingProcessor {

    public static PoseData processCanvas(Canvas canvas) {
        PoseData pose = new PoseData();
        int width = (int) canvas.getWidth();
        int height = (int) canvas.getHeight();
        
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage snapshot = canvas.snapshot(params, null);
        PixelReader reader = snapshot.getPixelReader();

        for (Color targetColor : AnatomyColors.getAll()) {
            double sumX = 0;
            double sumY = 0;
            int count = 0;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    Color pixelColor = reader.getColor(x, y);
                    if (pixelColor.getOpacity() > 0.5 && isSimilar(pixelColor, targetColor)) {
                        sumX += x;
                        sumY += y;
                        count++;
                    }
                }
            }

            if (count > 0) {
                pose.addJoint(AnatomyColors.getName(targetColor), sumX / count, sumY / count);
            }
        }

        return pose;
    }

    private static boolean isSimilar(Color c1, Color c2) {
        double threshold = 0.1;
        return Math.abs(c1.getRed() - c2.getRed()) < threshold &&
               Math.abs(c1.getGreen() - c2.getGreen()) < threshold &&
               Math.abs(c1.getBlue() - c2.getBlue()) < threshold;
    }
}
