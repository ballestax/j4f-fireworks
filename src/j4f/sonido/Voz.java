package j4f.sonido;

/**
 * Una voz del sintetizador.
 *
 * Aqui esta la decision que hace viable todo el motor. Lo evidente seria un
 * oscilador por armonico y voz: con 64 armonicos y 24 voces salen mas de mil
 * osciladores a 48 kHz, que se come mas de medio nucleo. En su lugar, como
 * las amplitudes de los armonicos solo cambian a ritmo de bloque, se suman
 * una vez por bloque en una tabla de ciclo unico y en el bucle de audio corre
 * un unico oscilador interpolante por voz. El coste baja mas de un orden de
 * magnitud y el resultado suena igual.
 *
 * Los armonicos por encima de Nyquist se descartan al reconstruir, asi que no
 * hay aliasing por agudo que sea la nota.
 *
 * @author ballestas
 */
public final class Voz {

    private static final int TAM_TABLA = Instrumento.TAM_TABLA;
    private static final int MASCARA = Instrumento.MASCARA_TABLA;

    /** Apunta a la tabla precocida del instrumento; la voz no la posee. */
    private float[] tabla;

    private final Envolvente envolvente;
    private final double frecMuestreo;

    private Instrumento instrumento;
    private float fase;
    private float faseDestemple;
    private float incremento;
    private float incrementoDestemple;
    private float amplitud;
    private float ganIzq;
    private float ganDer;
    private float envio;

    private int capa = -1;
    private int nota = -1;
    private boolean enUso;
    private long finNs;
    private boolean duracionFijada;

    public Voz(double frecMuestreo) {
        this.frecMuestreo = frecMuestreo;
        this.envolvente = new Envolvente(frecMuestreo);
    }

    public boolean enUso() {
        return enUso;
    }

    public int getCapa() {
        return capa;
    }

    public int getNota() {
        return nota;
    }

    public long getFinNs() {
        return finNs;
    }

    public boolean sonando() {
        return enUso && envolvente.activa();
    }

    /** Arranca la voz. Se llama desde el hilo de audio, en un limite de bloque. */
    public void disparar(int capa, int nota, int velocidad, Instrumento ins, long finNs) {
        this.capa = capa;
        this.nota = nota;
        this.instrumento = ins;
        this.finNs = finNs;
        this.duracionFijada = true;
        this.enUso = true;

        double frecuencia = 440.0 * Math.pow(2.0, (nota - 69) / 12.0);
        incremento = (float) (frecuencia / frecMuestreo);
        double desaf = Math.pow(2.0, ins.destemple / 12.0);
        incrementoDestemple = (float) (frecuencia * desaf / frecMuestreo);
        fase = 0;
        // Segunda copia arrancada en otro punto: si empiezan juntas se suman
        // en fase y el destemple no se oye hasta que se separan.
        faseDestemple = 0.37f;

        float v = velocidad / 127f;
        amplitud = v * v * ins.ganancia;
        float pan = ins.panoramica;
        ganIzq = (float) Math.sqrt(0.5 * (1 - pan));
        ganDer = (float) Math.sqrt(0.5 * (1 + pan));
        envio = ins.envio;

        envolvente.configurar(ins.ataqueMs, ins.caidaMs, ins.sostenido, ins.soltadoMs);
        envolvente.disparar();

        // Ya esta cocida: disparar una nota no cuesta mas que un indice.
        tabla = ins.tabla(nota);
    }

    public void soltar() {
        envolvente.soltar();
        duracionFijada = false;
    }

    public void cortar() {
        envolvente.cortar();
        enUso = false;
        capa = -1;
        nota = -1;
    }

    /**
     * Renderiza n muestras sumandolas a las mezclas seca y de reverberacion.
     * No asigna memoria: es el bucle critico.
     */
    public void render(float[] izq, float[] der, float[] envIzq, float[] envDer,
            int n, BancoRuido ruido) {
        if (!enUso) {
            return;
        }
        float[] onda = tabla;
        float[] gananciasRuido = instrumento.ruido;

        for (int i = 0; i < n; i++) {
            float e = envolvente.siguiente();
            if (e <= 0) {
                cortar();
                return;
            }
            float pos = fase * TAM_TABLA;
            float s = Tablas.leer(onda, MASCARA, pos);
            // La copia desafinada da cuerpo sin doblar el coste de la tabla.
            if (incrementoDestemple != incremento) {
                s = 0.62f * s + 0.38f * Tablas.leer(onda, MASCARA, faseDestemple * TAM_TABLA);
                faseDestemple += incrementoDestemple;
                if (faseDestemple >= 1f) {
                    faseDestemple -= 1f;
                }
            }
            fase += incremento;
            if (fase >= 1f) {
                fase -= 1f;
            }

            float v = s * e * amplitud;
            // El ruido son las bandas compartidas mezcladas con las ganancias
            // de esta voz: dieciseis multiplicar-sumar, no una convolucion.
            for (int b = 0; b < Instrumento.BANDAS_RUIDO; b++) {
                float g = gananciasRuido[b];
                if (g > 0.0002f) {
                    v += ruido.banda(b)[i] * g * e * amplitud;
                }
            }

            float l = v * ganIzq;
            float r = v * ganDer;
            izq[i] += l;
            der[i] += r;
            envIzq[i] += l * envio;
            envDer[i] += r * envio;
        }
    }
}
