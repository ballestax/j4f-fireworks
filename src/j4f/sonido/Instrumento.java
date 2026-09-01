package j4f.sonido;

/**
 * Receta de timbre de una capa.
 *
 * En esta fase los armonicos se calculan con unos pocos parametros en vez de
 * venir de una red: caida del espectro, equilibrio entre armonicos pares e
 * impares y una formante que realza una zona. Con eso se cubre desde un pad
 * suave hasta una campana. Cuando llegue la red de timbre, sustituira a
 * rellenarArmonicos() y el resto de la cadena no se entera.
 *
 * @author ballestas
 */
public final class Instrumento {

    public static final int MAX_ARMONICOS = 64;
    public static final int BANDAS_RUIDO = BancoRuido.BANDAS;
    /** Tamano de la tabla de ciclo unico. Potencia de dos. */
    public static final int TAM_TABLA = 512;
    public static final int MASCARA_TABLA = TAM_TABLA - 1;
    /** Una tabla por octava de MIDI: once cubren de la nota 0 a la 127. */
    private static final int OCTAVAS = 11;

    /**
     * Tablas precalculadas, una por octava.
     *
     * Reconstruirlas al disparar la nota parecia razonable, pero medido no lo
     * era: doce voces arrancando a la vez sumaban sesenta y cuatro armonicos
     * cada una dentro del bloque de audio y lo disparaban a 14 ms sobre un
     * presupuesto de 10,7, o sea un corte. Como el contenido solo depende del
     * instrumento y de cuantos armonicos caben bajo Nyquist, se cuecen todas
     * al construir y disparar una nota pasa a ser elegir un indice.
     */
    private final float[][] tablas = new float[OCTAVAS][TAM_TABLA];

    public final float[] armonicos = new float[MAX_ARMONICOS];
    public final float[] ruido = new float[BANDAS_RUIDO];

    public final double ataqueMs;
    public final double caidaMs;
    public final float sostenido;
    public final double soltadoMs;
    /** Ganancia de la capa antes de la mezcla. */
    public final float ganancia;
    /** -1 izquierda, 0 centro, 1 derecha. */
    public final float panoramica;
    /** Cuanto se manda a la reverberacion. */
    public final float envio;
    /** Desafinacion en semitonos de una segunda copia, para dar cuerpo. */
    public final float destemple;

    private Instrumento(double caida, float impares, float formante, float anchoFormante,
            float nivelRuido, float centroRuido,
            double ataqueMs, double caidaMs, float sostenido, double soltadoMs,
            float ganancia, float panoramica, float envio, float destemple) {
        this.ataqueMs = ataqueMs;
        this.caidaMs = caidaMs;
        this.sostenido = sostenido;
        this.soltadoMs = soltadoMs;
        this.ganancia = ganancia;
        this.panoramica = panoramica;
        this.envio = envio;
        this.destemple = destemple;
        rellenarArmonicos(caida, impares, formante, anchoFormante);
        rellenarRuido(nivelRuido, centroRuido);
        cocerTablas();
    }

    /**
     * Suma los armonicos en una tabla por octava. Se usa la nota mas aguda de
     * cada octava para decidir el limite, asi que ninguna nota de esa octava
     * puede producir aliasing.
     */
    private void cocerTablas() {
        for (int oct = 0; oct < OCTAVAS; oct++) {
            int notaMasAguda = oct * 12 + 11;
            double frec = 440.0 * Math.pow(2.0, (notaMasAguda - 69) / 12.0);
            int maximo = (int) (24000.0 / frec);
            if (maximo > MAX_ARMONICOS) {
                maximo = MAX_ARMONICOS;
            }
            if (maximo < 1) {
                maximo = 1;
            }
            float[] t = tablas[oct];
            for (int i = 0; i < TAM_TABLA; i++) {
                t[i] = 0;
            }
            for (int n = 1; n <= maximo; n++) {
                float a = armonicos[n - 1];
                if (a < 0.0005f) {
                    continue;
                }
                for (int i = 0; i < TAM_TABLA; i++) {
                    t[i] += a * Tablas.seno((float) i * n / TAM_TABLA);
                }
            }
            // Normalizado por pico: si no, las octavas graves con muchos
            // armonicos suenan mucho mas fuerte que las agudas.
            float pico = 0;
            for (int i = 0; i < TAM_TABLA; i++) {
                float v = t[i] < 0 ? -t[i] : t[i];
                if (v > pico) {
                    pico = v;
                }
            }
            if (pico > 0.0001f) {
                float k = 1f / pico;
                for (int i = 0; i < TAM_TABLA; i++) {
                    t[i] *= k;
                }
            }
        }
    }

    /** Tabla ya cocida para esa nota. Coste constante. */
    public float[] tabla(int nota) {
        int oct = nota / 12;
        if (oct < 0) {
            oct = 0;
        } else if (oct >= OCTAVAS) {
            oct = OCTAVAS - 1;
        }
        return tablas[oct];
    }

    /**
     * @param caida  exponente de caida del espectro: 1 es sierra, 2 mas dulce.
     * @param impares 1 solo impares (cuadrada, clarinete), 0 equilibrado.
     * @param formante armonico realzado, 0 para ninguno.
     */
    private void rellenarArmonicos(double caida, float impares, float formante, float ancho) {
        float suma = 0;
        for (int n = 1; n <= MAX_ARMONICOS; n++) {
            double a = 1.0 / Math.pow(n, caida);
            if (impares > 0 && (n % 2) == 0) {
                a *= (1 - impares);
            }
            if (formante > 0) {
                double d = (n - formante) / ancho;
                a *= 1 + 2.5 * Math.exp(-d * d);
            }
            armonicos[n - 1] = (float) a;
            suma += armonicos[n - 1];
        }
        // Normalizado: asi la ganancia de capa manda de verdad y no depende
        // de cuantos armonicos lleve la receta.
        if (suma > 0) {
            for (int i = 0; i < MAX_ARMONICOS; i++) {
                armonicos[i] /= suma;
            }
        }
    }

    private void rellenarRuido(float nivel, float centro) {
        for (int i = 0; i < BANDAS_RUIDO; i++) {
            float d = (i - centro) / 3.5f;
            ruido[i] = (float) (nivel * Math.exp(-d * d));
        }
    }

    // ------------------------------------------------------------------
    // Recetas por capa
    // ------------------------------------------------------------------

    /** Pad: espectro ancho y dulce, ataque lento, cola larga y desafinado. */
    public static Instrumento pad() {
        return new Instrumento(1.7, 0.15f, 3, 2.5f, 0.010f, 4,
                900, 2200, 0.75f, 2600, 0.34f, 0f, 0.55f, 0.10f);
    }

    /** Bajo: fundamental fuerte y pocos armonicos. */
    public static Instrumento bajo() {
        return new Instrumento(2.1, 0.25f, 0, 1, 0.004f, 2,
                18, 700, 0.62f, 320, 0.52f, 0f, 0.14f, 0.02f);
    }

    /** Melodia: campana, con impares marcados y un golpe de aire al ataque. */
    public static Instrumento motivo() {
        return new Instrumento(1.35, 0.45f, 5, 2.0f, 0.030f, 8,
                6, 1500, 0.30f, 900, 0.44f, -0.18f, 0.42f, 0f);
    }

    /** Contracanto: mas suave que la melodia y desplazado al otro lado. */
    public static Instrumento contra() {
        return new Instrumento(1.9, 0.30f, 2, 2.0f, 0.020f, 7,
                40, 1100, 0.45f, 800, 0.32f, 0.30f, 0.48f, 0.03f);
    }

    /** Textura: pulsada, brillante y de caida rapida, tipo arpa. */
    public static Instrumento textura() {
        return new Instrumento(1.25, 0.20f, 7, 3.0f, 0.045f, 10,
                3, 800, 0.10f, 700, 0.30f, 0.12f, 0.50f, 0f);
    }

    /** Percusion: casi todo ruido grave con un golpe corto de tono. */
    public static Instrumento percusion() {
        return new Instrumento(2.6, 0.10f, 0, 1, 0.55f, 1,
                2, 180, 0.02f, 260, 0.70f, 0f, 0.30f, 0f);
    }
}
