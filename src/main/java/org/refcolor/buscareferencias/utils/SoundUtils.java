package org.refcolor.buscareferencias.utils;

import org.refcolor.buscareferencias.settings.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;

/**
 * Utilidades de sonido — reproduce un tono de finalización cuando AppSettings.isSoundEnabled().
 * No depende de AWT ni JavaFX; usa javax.sound.sampled del JDK.
 */
public final class SoundUtils {

    private static final Logger logger = LoggerFactory.getLogger(SoundUtils.class);

    private SoundUtils() {}

    /**
     * Reproduce un "ding" de análisis completado.
     * Si el sonido está desactivado en ajustes, no hace nada.
     * Se ejecuta en un hilo daemon para no bloquear la UI.
     */
    public static void playSearchDing() {
        if (!AppSettings.isSoundEnabled()) return;
        Thread t = new Thread(SoundUtils::playTone, "sound-ding");
        t.setDaemon(true);
        t.start();
    }

    // ── Generación de tono ────────────────────────────────────────────────────

    private static void playTone() {
        try {
            // Dos notas: 880 Hz (A5) seguida de 1047 Hz (C6) — sonido tipo "ding"
            writeTone(880,  180, 0.55f);
            writeTone(1047, 250, 0.45f);
        } catch (Exception e) {
            logger.debug("[SOUND] Imposible reproducir tono: {}", e.getMessage());
        }
    }

    private static void writeTone(double freqHz, int durationMs, float volume)
            throws LineUnavailableException {
        float sampleRate = 44100f;
        int numSamples   = (int)(sampleRate * durationMs / 1000);
        byte[] buf       = new byte[numSamples * 2];          // 16-bit PCM, mono

        for (int i = 0; i < numSamples; i++) {
            double angle     = 2.0 * Math.PI * i * freqHz / sampleRate;
            // Fade-out suave en el último 30 % de la nota
            double envelope  = i < numSamples * 0.7
                                ? 1.0
                                : 1.0 - (i - numSamples * 0.7) / (numSamples * 0.3);
            short  sample    = (short)(Short.MAX_VALUE * volume * envelope * Math.sin(angle));
            buf[2 * i]     = (byte)(sample & 0xFF);
            buf[2 * i + 1] = (byte)((sample >> 8) & 0xFF);
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info  info = new DataLine.Info(SourceDataLine.class, format);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            line.open(format);
            line.start();
            line.write(buf, 0, buf.length);
            line.drain();
        }
    }
}
