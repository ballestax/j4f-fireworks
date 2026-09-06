package j4f.sonido;

import java.io.File;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Salida por muestras reales, con la cadena de efectos propia.
 *
 * Gervill, el sintetizador del JDK, carga un banco SoundFont y entrega audio
 * por AudioSynthesizer.openStream() sin tocar la tarjeta de sonido. Eso
 * permite lo interesante: coger ese PCM de muestras reales y pasarlo por
 * NUESTRA reverberacion, coro, saturacion y limitador, en vez de conformarse
 * con los efectos internos del sintetizador. Muestras de instrumentos de
 * verdad con la firma sonora del proyecto.
 *
 * Aqui los cambios de programa por genero que Musica ya enviaba funcionan sin
 * trabajo extra: Gervill los entiende de forma nativa.
 *
 * Sin fichero de banco esta salida sencillamente no se ofrece, y la
 * aplicacion sigue con el motor propio.
 *
 * @author ballestas
 */
public final class SalidaGervill implements Salida {

    /** Donde se buscan los bancos, relativo al directorio de trabajo. */
    private static final String CARPETA = "sonido";
    /** Permite fijar un banco concreto desde la linea de ordenes. */
    private static final String PROPIEDAD = "j4f.soundfont";

    private static final int BLOQUE = MotorAudio.BLOQUE;
    private static final int BUFER = 4096;
    private static final float FRECUENCIA = 48000f;
    /**
     * Ganancia de compensacion. El banco entrega bastante menos que fondo de
     * escala y la cadena de efectos espera niveles de trabajo.
     */
    private static final float GANANCIA = 3.2f;

    private Synthesizer sintetizador;
    private AudioInputStream flujo;
    private Receiver receptor;
    private SourceDataLine linea;
    private Thread hilo;
    private volatile boolean enMarcha;
    private volatile boolean listo;
    private volatile int cortes;
    private String nombreBanco = "";

    private Reverberacion reverberacion;
    private Coro coro;
    private Saturacion saturacion;
    private Limitador limitador;

    // Todo preasignado: el bucle de audio no crea objetos.
    private final byte[] crudo = new byte[BLOQUE * 4];
    private final byte[] salida = new byte[BLOQUE * 4];
    private final float[] izq = new float[BLOQUE];
    private final float[] der = new float[BLOQUE];
    /**
     * Los estallidos no vienen del SoundFont: se sintetizan aqui.
     *
     * Su nivel es mucho mas alto que en el mezclador propio porque este bus
     * trabaja a otra escala: aqui las muestras ya llegan multiplicadas por
     * GANANCIA y los estallidos se suman despues, sin ganancia detras.
     */
    private final Estallidos estallidos = crearEstallidos();

    private static Estallidos crearEstallidos() {
        Estallidos e = new Estallidos(FRECUENCIA);
        e.setNivel(0.42f);
        return e;
    }

    /** @return el fichero de banco a usar, o null si no hay ninguno. */
    public static File buscarBanco() {
        String fijado = System.getProperty(PROPIEDAD);
        if (fijado != null && fijado.length() > 0) {
            File f = new File(fijado);
            return f.isFile() ? f : null;
        }
        File dir = new File(CARPETA);
        if (!dir.isDirectory()) {
            return null;
        }
        File[] hijos = dir.listFiles();
        if (hijos == null) {
            return null;
        }
        // El mayor primero: entre varios bancos, el grande suele ser el bueno.
        File mejor = null;
        for (int i = 0; i < hijos.length; i++) {
            String n = hijos[i].getName().toLowerCase();
            if (hijos[i].isFile() && (n.endsWith(".sf2") || n.endsWith(".dls"))) {
                if (mejor == null || hijos[i].length() > mejor.length()) {
                    mejor = hijos[i];
                }
            }
        }
        return mejor;
    }

    @Override
    public boolean abrir() {
        File banco = buscarBanco();
        if (banco == null) {
            return false;
        }
        try {
            Soundbank sb = MidiSystem.getSoundbank(banco);
            if (sb == null) {
                return false;
            }
            Synthesizer syn = MidiSystem.getSynthesizer();
            if (!(syn instanceof com.sun.media.sound.AudioSynthesizer)) {
                return false;
            }
            com.sun.media.sound.AudioSynthesizer as = (com.sun.media.sound.AudioSynthesizer) syn;

            AudioFormat fmt = new AudioFormat(FRECUENCIA, 16, 2, true, false);
            java.util.Map<String, Object> opciones = new java.util.HashMap<String, Object>();
            // La reverberacion y el coro se apagan aqui: los pone la cadena
            // propia, que es justo lo que distingue esta salida de enchufar el
            // sintetizador del sistema y ya.
            opciones.put("reverb", Boolean.FALSE);
            opciones.put("chorus", Boolean.FALSE);
            opciones.put("load default soundbank", Boolean.FALSE);
            opciones.put("interpolation", "linear");
            opciones.put("max polyphony", "64");

            flujo = as.openStream(fmt, opciones);
            syn.loadAllInstruments(sb);
            receptor = syn.getReceiver();
            sintetizador = syn;
            nombreBanco = banco.getName();

            AudioFormat salidaFmt = new AudioFormat(FRECUENCIA, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, salidaFmt);
            if (!AudioSystem.isLineSupported(info)) {
                cerrar();
                return false;
            }
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            l.open(salidaFmt, BUFER * 4);
            l.start();
            linea = l;

            reverberacion = new Reverberacion(FRECUENCIA);
            coro = new Coro(FRECUENCIA);
            saturacion = new Saturacion();
            limitador = new Limitador(FRECUENCIA);
            reverberacion.ajustar(0.68, 0.35, 0.24);
            coro.ajustar(0.14, 0.12);
            saturacion.ajustar(1.20, 1.18);
            limitador.ajustar(0.92, 90);

            enMarcha = true;
            hilo = new Thread(new Bucle(), "AudioGervill");
            hilo.setDaemon(true);
            hilo.setPriority(Thread.MAX_PRIORITY);
            hilo.start();
            listo = true;
            return true;
        } catch (Throwable t) {
            cerrar();
            return false;
        }
    }

    @Override
    public void cerrar() {
        listo = false;
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
        cerrarSilencioso();
    }

    private void cerrarSilencioso() {
        try {
            if (linea != null) {
                linea.stop();
                linea.close();
            }
        } catch (Exception e) {
            // Cerrando.
        }
        linea = null;
        try {
            if (flujo != null) {
                flujo.close();
            }
        } catch (Exception e) {
            // Cerrando.
        }
        flujo = null;
        try {
            if (sintetizador != null) {
                sintetizador.close();
            }
        } catch (Exception e) {
            // Cerrando.
        }
        sintetizador = null;
        receptor = null;
    }

    @Override
    public boolean disponible() {
        return listo;
    }

    @Override
    public String nombre() {
        return "muestras reales (" + nombreBanco + ")";
    }

    @Override
    public Estallidos estallidos() {
        return estallidos;
    }

    public int getCortes() {
        return cortes;
    }

    @Override
    public void enviar(int comando, int canal, int dato1, int dato2) {
        Receiver r = receptor;
        if (!listo || r == null) {
            return;
        }
        try {
            ShortMessage m = new ShortMessage();
            m.setMessage(comando, canal, dato1, dato2);
            r.send(m, -1L);
        } catch (Exception e) {
            // Mensaje mal formado o dispositivo caido: se ignora, igual que
            // hace el resto del motor.
        }
    }

    @Override
    public void panico() {
        for (int canal = 0; canal < 16; canal++) {
            enviar(ShortMessage.CONTROL_CHANGE, canal, 120, 0);
            enviar(ShortMessage.CONTROL_CHANGE, canal, 123, 0);
        }
        // Tambien los estallidos: no salen del SoundFont, asi que los mensajes
        // de arriba no los tocan. Sin esto, silenciar dejaria sonando truenos
        // ya programados durante casi un segundo.
        estallidos.panico();
    }

    /** Lee de Gervill, pasa por la cadena propia y vuelca a la tarjeta. */
    private final class Bucle implements Runnable {

        @Override
        public void run() {
            int tamBufer = linea.getBufferSize();
            long bloques = 0;
            while (enMarcha) {
                try {
                    if (bloques > 8 && linea.available() == tamBufer) {
                        cortes++;
                    }
                    bloques++;

                    int leidos = flujo.read(crudo, 0, crudo.length);
                    if (leidos <= 0) {
                        continue;
                    }
                    int muestras = leidos / 4;
                    for (int i = 0; i < muestras; i++) {
                        int j = i * 4;
                        izq[i] = (short) ((crudo[j + 1] << 8) | (crudo[j] & 0xFF)) / 32768f * GANANCIA;
                        der[i] = (short) ((crudo[j + 3] << 8) | (crudo[j + 2] & 0xFF)) / 32768f * GANANCIA;
                    }

                    // Los estallidos se suman antes de los efectos, para que
                    // pasen por la misma reverberacion y el mismo limitador
                    // que la musica: si fueran por fuera sonarian pegados a la
                    // cara, en otra sala que el resto.
                    //
                    // Sin buses de envio: aqui la reverberacion va en linea y
                    // no hay bus aparte que alimentar.
                    estallidos.render(izq, der, null, null, muestras, System.nanoTime());

                    coro.procesar(izq, der, muestras);
                    reverberacion.procesar(izq, der, muestras);
                    saturacion.procesar(izq, der, muestras);
                    limitador.procesar(izq, der, muestras);

                    for (int i = 0; i < muestras; i++) {
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
                    linea.write(salida, 0, muestras * 4);
                } catch (Exception e) {
                    enMarcha = false;
                }
            }
        }
    }
}
