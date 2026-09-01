package j4f.sonido;

import javax.sound.midi.ShortMessage;

/**
 * Salida por el sintetizador propio.
 *
 * Traduce los mensajes cortos de MIDI a eventos de la cola del hilo de audio.
 * Es un interprete muy pequeno: notas, volumen y panico. Los cambios de
 * programa se ignoran a proposito, porque el timbre lo decide la receta de la
 * capa y no un numero de instrumento General MIDI.
 *
 * @author ballestas
 */
public final class SalidaSintetizador implements Salida {

    private static final int CC_VOLUMEN = 7;
    private static final int CC_TODAS_NOTAS_OFF = 123;
    private static final int CC_TODO_SONIDO_OFF = 120;
    /** Canal de percusion de General MIDI. */
    private static final int CANAL_PERCUSION = 9;
    /** La percusion es la ultima capa del mezclador. */
    private static final int CAPA_PERCUSION = 5;

    private final ColaEventos cola = new ColaEventos(2048);
    private final MotorAudio motor = new MotorAudio(cola);
    private volatile boolean listo;
    private volatile int generacion;

    @Override
    public boolean abrir() {
        if (!motor.abrir()) {
            return false;
        }
        motor.getMezclador().setGeneracion(generacion);
        motor.arrancar();
        listo = true;
        return true;
    }

    @Override
    public void cerrar() {
        listo = false;
        motor.detener();
    }

    @Override
    public boolean disponible() {
        return listo;
    }

    @Override
    public String nombre() {
        return "sintetizador propio";
    }

    public int getCortes() {
        return motor.getCortes();
    }

    /** Abre en modo volcado: sin tarjeta de sonido, para renderizar a fichero. */
    public boolean abrirSinLinea(double frecuencia) {
        if (!motor.abrirSinLinea(frecuencia)) {
            return false;
        }
        motor.getMezclador().setGeneracion(generacion);
        listo = true;
        return true;
    }

    public MotorAudio getMotor() {
        return motor;
    }

    public int vocesActivas() {
        Mezclador m = motor.getMezclador();
        return m == null ? 0 : m.vocesActivas();
    }

    /** Traduce el canal de Musica a capa del mezclador. */
    private static int capaDe(int canal) {
        if (canal == CANAL_PERCUSION) {
            return CAPA_PERCUSION;
        }
        return canal >= 0 && canal < CAPA_PERCUSION ? canal : -1;
    }

    @Override
    public void enviar(int comando, int canal, int dato1, int dato2) {
        if (!listo) {
            return;
        }
        int capa = capaDe(canal);
        if (capa < 0) {
            return;
        }
        long ahora = System.nanoTime();
        if (comando == ShortMessage.NOTE_ON) {
            if (dato2 <= 0) {
                cola.ofrecer(ColaEventos.TIPO_APAGAR, capa, dato1, 0, 0, ahora, generacion);
            } else {
                // Duracion 0: el apagado llega como NOTE_OFF desde el motor,
                // que ya lleva su propia tabla de voces.
                cola.ofrecer(ColaEventos.TIPO_NOTA, capa, dato1, dato2, 0, ahora, generacion);
            }
        } else if (comando == ShortMessage.NOTE_OFF) {
            cola.ofrecer(ColaEventos.TIPO_APAGAR, capa, dato1, 0, 0, ahora, generacion);
        } else if (comando == ShortMessage.CONTROL_CHANGE) {
            if (dato1 == CC_VOLUMEN) {
                Mezclador m = motor.getMezclador();
                if (m != null) {
                    m.setVolumenCapa(capa, dato2 / 127f);
                }
            } else if (dato1 == CC_TODAS_NOTAS_OFF || dato1 == CC_TODO_SONIDO_OFF) {
                panico();
            }
        }
    }

    @Override
    public void panico() {
        if (!listo) {
            return;
        }
        // Se sube la generacion para que lo ya encolado se descarte, y se
        // manda el corte. Asi un cambio de genero no deja notas colgando.
        generacion++;
        Mezclador m = motor.getMezclador();
        if (m != null) {
            m.setGeneracion(generacion);
        }
        cola.ofrecer(ColaEventos.TIPO_PANICO, 0, 0, 0, 0, System.nanoTime(), generacion);
    }
}
