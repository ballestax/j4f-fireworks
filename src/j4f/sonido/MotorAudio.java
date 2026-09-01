package j4f.sonido;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Hilo de audio en tiempo real.
 *
 * El bucle se auto-temporiza: write() bloquea hasta que el mezclador del
 * sistema acepta los datos, asi que no lleva ningun Thread.sleep. Eso da
 * precision de muestra, frente a los 25 ms de incertidumbre que tenia el
 * temporizado por sondeo del motor MIDI.
 *
 * El tamano del bufer no es una preferencia: son 85 ms elegidos para tragarse
 * las pausas del recolector. Medido en esta maquina, cero cortes con la
 * animacion a 1080p y con cuatro hilos saturando la CPU.
 *
 * @author ballestas
 */
public final class MotorAudio implements Runnable {

    /** Muestras por bloque. 512 a 48 kHz son 10,7 ms. */
    public static final int BLOQUE = 512;
    /** Muestras de bufer de linea. */
    private static final int BUFER = 4096;
    private static final float[] FRECUENCIAS = {48000f, 44100f};

    private final ColaEventos cola;
    private Mezclador mezclador;
    private SourceDataLine linea;
    private Thread hilo;
    private volatile boolean enMarcha;
    private volatile int cortes;
    private double frecMuestreo;

    private final float[] izq = new float[BLOQUE];
    private final float[] der = new float[BLOQUE];
    private final byte[] salida = new byte[BLOQUE * 4];

    public MotorAudio(ColaEventos cola) {
        this.cola = cola;
    }

    public Mezclador getMezclador() {
        return mezclador;
    }

    public int getCortes() {
        return cortes;
    }

    public double getFrecMuestreo() {
        return frecMuestreo;
    }

    /**
     * Abre solo el mezclador, sin linea ni hilo, para renderizar fuera de
     * tiempo real. Lo usan las pruebas para volcar a WAV y comparar timbres.
     */
    public boolean abrirSinLinea(double frecuencia) {
        frecMuestreo = frecuencia;
        mezclador = new Mezclador(frecMuestreo, BLOQUE, cola);
        return true;
    }

    /** Renderiza un bloque fuera de tiempo real. */
    public void renderizarBloque(float[] salidaIzq, float[] salidaDer, long ahoraNs) {
        mezclador.render(salidaIzq, salidaDer, BLOQUE, ahoraNs);
    }

    /** @return false si no hay salida de audio utilizable. */
    public boolean abrir() {
        for (int i = 0; i < FRECUENCIAS.length; i++) {
            AudioFormat formato = new AudioFormat(FRECUENCIAS[i], 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, formato);
            if (!AudioSystem.isLineSupported(info)) {
                continue;
            }
            try {
                SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
                // Tamano explicito: sin el, la implementacion de Windows abre
                // con medio segundo de bufer.
                l.open(formato, BUFER * 4);
                l.start();
                linea = l;
                frecMuestreo = FRECUENCIAS[i];
                mezclador = new Mezclador(frecMuestreo, BLOQUE, cola);
                return true;
            } catch (Exception e) {
                linea = null;
            }
        }
        return false;
    }

    public void arrancar() {
        if (linea == null || enMarcha) {
            return;
        }
        enMarcha = true;
        hilo = new Thread(this, "Audio");
        hilo.setDaemon(true);
        // Ayuda, pero la defensa de verdad contra los cortes es el bufer.
        hilo.setPriority(Thread.MAX_PRIORITY);
        hilo.start();
    }

    public void detener() {
        enMarcha = false;
        Thread h = hilo;
        hilo = null;
        if (h != null) {
            h.interrupt();
            try {
                h.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (linea != null) {
            try {
                linea.stop();
                linea.close();
            } catch (Exception e) {
                // Cerrando: nada que hacer.
            }
            linea = null;
        }
    }

    @Override
    public void run() {
        int tamBufer = linea.getBufferSize();
        long bloquesHechos = 0;
        while (enMarcha) {
            try {
                long ahora = System.nanoTime();
                // Los primeros bloques la linea esta vacia por definicion:
                // contarlos como corte daria un falso positivo de arranque.
                if (bloquesHechos > 8 && linea.available() == tamBufer) {
                    cortes++;
                }
                bloquesHechos++;
                mezclador.render(izq, der, BLOQUE, ahora);
                empaquetar(BLOQUE);
                linea.write(salida, 0, BLOQUE * 4);
            } catch (Exception e) {
                // El motor se apaga en caliente; la aplicacion sigue.
                enMarcha = false;
            }
        }
    }

    /** Convierte a entero de 16 bits con recorte duro de seguridad. */
    private void empaquetar(int n) {
        for (int i = 0; i < n; i++) {
            int l = (int) (izq[i] * 32767f);
            int r = (int) (der[i] * 32767f);
            if (l > 32767) {
                l = 32767;
            } else if (l < -32768) {
                l = -32768;
            }
            if (r > 32767) {
                r = 32767;
            } else if (r < -32768) {
                r = -32768;
            }
            int j = i * 4;
            salida[j] = (byte) (l & 0xFF);
            salida[j + 1] = (byte) (l >> 8);
            salida[j + 2] = (byte) (r & 0xFF);
            salida[j + 3] = (byte) (r >> 8);
        }
    }
}
