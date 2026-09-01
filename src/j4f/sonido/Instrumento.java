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

    // --- Timbre dinamico -------------------------------------------------
    //
    // Las tablas de arriba son estaticas: el mismo espectro toque como se
    // toque. Lo que distingue a un instrumento vivo es que el brillo sigue a
    // la dinamica y decae a lo largo de la nota: los armonicos agudos mueren
    // antes que los graves, sobre todo en lo pulsado y lo golpeado. Eso se
    // anade con un filtro por voz cuyo corte depende de la velocidad y del
    // tiempo desde el ataque. Es proceso de senal directo y honesto, no una
    // red: la red de timbre de verdad necesita un corpus que aqui no hay.

    /** Corte minimo del filtro, en multiplos de la fundamental. */
    public final float brilloMin;
    /** Corte con la dinamica al maximo, en multiplos de la fundamental. */
    public final float brilloMax;
    /** Caida del brillo hacia el minimo. Cero = sin caida (sostenido). */
    public final double brilloCaidaMs;
    /** Caida propia del ruido: el soplo del ataque. Cero = sostenido. */
    public final double ruidoCaidaMs;

    // --- Expresion de altura ---------------------------------------------
    //
    // Un violin sin vibrato es un organo triste, y una cuerda frotada sin
    // ligado suena a teclado. Las dos son modulacion de altura, que el motor
    // no tenia: la nota arrancaba con un incremento de fase fijo y no se
    // movia nunca.

    /** Profundidad del vibrato, en semitonos. Cero = sin vibrato. */
    public final float vibratoSemitonos;
    /** Velocidad del vibrato, en hercios. */
    public final float vibratoHz;
    /** Retardo hasta que entra: el vibrato de verdad no empieza con la nota. */
    public final double vibratoRetardoMs;
    /** Tiempo de ligado hacia la altura destino. Cero = ataque seco. */
    public final double portamentoMs;

    private Instrumento(double caida, float impares, float formante, float anchoFormante,
            float nivelRuido, float centroRuido,
            double ataqueMs, double caidaMs, float sostenido, double soltadoMs,
            float ganancia, float panoramica, float envio, float destemple,
            float brilloMin, float brilloMax, double brilloCaidaMs, double ruidoCaidaMs,
            float vibratoSemitonos, float vibratoHz, double vibratoRetardoMs,
            double portamentoMs) {
        this.vibratoSemitonos = vibratoSemitonos;
        this.vibratoHz = vibratoHz;
        this.vibratoRetardoMs = vibratoRetardoMs;
        this.portamentoMs = portamentoMs;
        this.brilloMin = brilloMin;
        this.brilloMax = brilloMax;
        this.brilloCaidaMs = brilloCaidaMs;
        this.ruidoCaidaMs = ruidoCaidaMs;
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
                900, 2200, 0.75f, 2600, 0.34f, 0f, 0.55f, 0.10f,
                // El pad respira pero no decae: su brillo acompana la dinamica
                // y se queda ahi mientras dura el acorde.
                5f, 13f, 0, 0,
                0.04f, 4.2f, 900, 0);
    }

    /** Bajo: fundamental fuerte y pocos armonicos. */
    public static Instrumento bajo() {
        return new Instrumento(2.1, 0.25f, 0, 1, 0.004f, 2,
                18, 700, 0.62f, 320, 0.52f, 0f, 0.14f, 0.02f,
                2.5f, 7f, 900, 80,
                0f, 0f, 0, 0);
    }

    /** Melodia: campana, con impares marcados y un golpe de aire al ataque. */
    public static Instrumento motivo() {
        return new Instrumento(1.35, 0.45f, 5, 2.0f, 0.030f, 8,
                6, 1500, 0.30f, 900, 0.44f, -0.18f, 0.42f, 0f,
                // Campana: arranca brillante y se va apagando hacia arriba,
                // con un soplo de aire solo en el golpe.
                4f, 18f, 1200, 60,
                0f, 0f, 0, 0);
    }

    /** Contracanto: mas suave que la melodia y desplazado al otro lado. */
    public static Instrumento contra() {
        return new Instrumento(1.9, 0.30f, 2, 2.0f, 0.020f, 7,
                40, 1100, 0.45f, 800, 0.32f, 0.30f, 0.48f, 0.03f,
                3f, 10f, 1000, 90,
                0.03f, 5.0f, 700, 0);
    }

    /** Textura: pulsada, brillante y de caida rapida, tipo arpa. */
    public static Instrumento textura() {
        return new Instrumento(1.25, 0.20f, 7, 3.0f, 0.045f, 10,
                3, 800, 0.10f, 700, 0.30f, 0.12f, 0.50f, 0f,
                4f, 20f, 450, 40,
                0f, 0f, 0, 0);
    }

    /** Guitarra de nailon: ataque rapido, cuerpo medio, sin vibrato. */
    public static Instrumento guitarra() {
        return new Instrumento(1.55, 0.35f, 3, 2.2f, 0.055f, 9,
                4, 900, 0.22f, 700, 0.40f, -0.10f, 0.38f, 0.012f,
                // El pulsado nace brillante por el ataque de la una y se
                // apaga rapido hacia arriba; el ruido es el roce de la cuerda.
                3f, 16f, 700, 45,
                0f, 0f, 0, 0);
    }

    /** Violin: ataque lento, brillo sostenido, vibrato y ligado. */
    public static Instrumento violin() {
        return new Instrumento(1.30, 0.40f, 4, 2.8f, 0.028f, 9,
                140, 1400, 0.80f, 500, 0.38f, -0.06f, 0.52f, 0.05f,
                // Frotada: el brillo no cae porque el arco sigue metiendo
                // energia, a diferencia de todo lo pulsado.
                6f, 15f, 0, 260,
                0.16f, 5.6f, 320, 45);
    }

    /** Piano de montuno: percusivo y brillante, para la caribena. */
    public static Instrumento montuno() {
        return new Instrumento(1.45, 0.30f, 4, 2.4f, 0.020f, 8,
                3, 700, 0.18f, 500, 0.42f, 0.14f, 0.30f, 0f,
                3.5f, 15f, 600, 35,
                0f, 0f, 0, 0);
    }

    /** Steel drum: metalico y con muchos inarmonicos aparentes. */
    public static Instrumento steelDrum() {
        return new Instrumento(1.20, 0.55f, 6, 1.6f, 0.035f, 10,
                4, 1000, 0.20f, 800, 0.40f, 0.20f, 0.46f, 0f,
                5f, 22f, 900, 50,
                0f, 0f, 0, 0);
    }

    /** Claves: golpe seco de madera, corto y agudo. */
    public static Instrumento clave() {
        return new Instrumento(1.10, 0.50f, 3, 1.8f, 0.16f, 11,
                1, 90, 0.0f, 110, 0.46f, -0.32f, 0.28f, 0f,
                6f, 20f, 90, 40,
                0f, 0f, 0, 0);
    }

    /** Conga: membrana con poco tono y mucho cuerpo. */
    public static Instrumento conga() {
        return new Instrumento(2.2, 0.20f, 2, 1.6f, 0.28f, 6,
                2, 260, 0.05f, 300, 0.52f, 0.24f, 0.26f, 0f,
                2f, 9f, 220, 90,
                0f, 0f, 0, 0);
    }

    /**
     * Receta para un numero de programa General MIDI.
     *
     * El sintetizador propio descartaba los cambios de programa, asi que la
     * trompeta del jazz y el vibrafono del chill sonaban exactamente igual:
     * el genero cambiaba las notas pero no el timbre. Este mapa devuelve el
     * caracter por familias, que es lo que el motor sabe hacer; no pretende
     * ser un banco General MIDI completo.
     */
    public static Instrumento dePrograma(int programa, Instrumento porDefecto) {
        switch (programa) {
            case 24: case 25: case 26: case 27:
                return guitarra();                 // guitarras
            case 40: case 41: case 110:
                return violin();                   // violin, viola, fiddle
            case 42: case 43:
                return contra();                   // violonchelo y contrabajo
            case 0: case 1: case 2: case 3:
                return montuno();                  // pianos
            case 4: case 5:
                return montuno();                  // pianos electricos
            case 114:
                return steelDrum();
            case 11: case 9: case 12: case 13:
                return motivo();                   // vibrafono y laminas
            case 46:
                return textura();                  // arpa
            case 48: case 49: case 50: case 51:
                return pad();                      // cuerdas en conjunto
            case 56: case 57: case 59: case 60:
                return montuno();                  // metales con ataque
            case 65: case 66: case 67: case 68:
                return violin();                   // canas: sostenidas
            case 32: case 33: case 34: case 35:
            case 36: case 37: case 38: case 39:
                return bajo();
            case 88: case 89: case 90: case 91:
            case 92: case 93: case 94: case 95:
                return pad();                      // pads
            default:
                return porDefecto;
        }
    }

    /** Receta de una nota de percusion: no todo el canal 9 es un bombo. */
    public static Instrumento dePercusion(int nota) {
        switch (nota) {
            case 75: case 76: case 77:
                return clave();
            case 62: case 63: case 64:
                return conga();
            default:
                return percusion();
        }
    }

    /** Percusion: casi todo ruido grave con un golpe corto de tono. */
    public static Instrumento percusion() {
        return new Instrumento(2.6, 0.10f, 0, 1, 0.55f, 1,
                2, 180, 0.02f, 260, 0.70f, 0f, 0.30f, 0f,
                1.5f, 6f, 150, 180,
                0f, 0f, 0, 0);
    }
}
