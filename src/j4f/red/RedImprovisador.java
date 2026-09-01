package j4f.red;

/**
 * Red recurrente que propone frases.
 *
 * Dos capas GRU de 128 unidades sobre un vocabulario plano de 157 fichas.
 * Cada ficha es una terna (grado, duracion, hueco): conjunta y no separada en
 * cabezas independientes, porque el acoplamiento entre altura y ritmo es
 * justo lo que hace que una frase suene intencionada y no ensamblada.
 *
 * Se eligio GRU y no un transformador. En computo cabrian los dos, pero a
 * este tamano un transformador necesita del orden de cinco a diez veces mas
 * datos para superar a la recurrente, y habria que escribir a mano la
 * atencion, el enmascarado causal y la cache de claves para no ganar nada.
 *
 * Unos 214.000 parametros, que a ocho bits son unos 215 KB.
 *
 * @author ballestas
 */
public final class RedImprovisador {

    // --- Topologia. El entrenador debe usar exactamente estos numeros. ---
    public static final int GRADO_MIN = -3;
    public static final int GRADO_MAX = 9;
    public static final int GRADOS = GRADO_MAX - GRADO_MIN + 1;
    public static final int DURACIONES = 4;
    public static final int HUECOS = 3;
    /** Fichas de nota mas la de fin de frase. */
    public static final int VOCABULARIO = GRADOS * DURACIONES * HUECOS + 1;
    public static final int FICHA_FIN = VOCABULARIO - 1;

    public static final int EMBEBIDO = 32;
    /**
     * Largo del vector de condicionamiento.
     *
     * Paso de 72 a 75 al llegar a seis generos: la casilla del genero era de
     * tres y CARIBENA, GUITARRA y VIOLIN acababan compartiendo hueco con
     * CLASICA, o sea condicionamiento equivocado.
     */
    public static final int CONTEXTO = 75;
    public static final int OCULTAS = 128;
    public static final int ENTRADA = EMBEBIDO + CONTEXTO;

    public static final String RECURSO = "/j4f/red/pesos.bin";

    // Nombres de los tensores en el fichero.
    private static final String[] NECESARIOS = {
        "emb", "g1_wz", "g1_wr", "g1_wh", "g1_uz", "g1_ur", "g1_uh",
        "g1_bz", "g1_br", "g1_bh",
        "g2_wz", "g2_wr", "g2_wh", "g2_uz", "g2_ur", "g2_uh",
        "g2_bz", "g2_br", "g2_bh", "sal_w", "sal_b"};

    private final Matriz emb;
    private final Matriz g1wz, g1wr, g1wh, g1uz, g1ur, g1uh, g1bz, g1br, g1bh;
    private final Matriz g2wz, g2wr, g2wh, g2uz, g2ur, g2uh, g2bz, g2br, g2bh;
    private final Matriz salW, salB;

    // Todo preasignado: la inferencia no crea objetos.
    private final float[] entrada = new float[ENTRADA];
    private final float[] h1 = new float[OCULTAS];
    private final float[] h2 = new float[OCULTAS];
    private final float[] z = new float[OCULTAS];
    private final float[] r = new float[OCULTAS];
    private final float[] c = new float[OCULTAS];
    private final float[] rh = new float[OCULTAS];
    private final float[] logits = new float[VOCABULARIO];

    private RedImprovisador(PesosNeuronales p) {
        emb = p.get("emb");
        g1wz = p.get("g1_wz");
        g1wr = p.get("g1_wr");
        g1wh = p.get("g1_wh");
        g1uz = p.get("g1_uz");
        g1ur = p.get("g1_ur");
        g1uh = p.get("g1_uh");
        g1bz = p.get("g1_bz");
        g1br = p.get("g1_br");
        g1bh = p.get("g1_bh");
        g2wz = p.get("g2_wz");
        g2wr = p.get("g2_wr");
        g2wh = p.get("g2_wh");
        g2uz = p.get("g2_uz");
        g2ur = p.get("g2_ur");
        g2uh = p.get("g2_uh");
        g2bz = p.get("g2_bz");
        g2br = p.get("g2_br");
        g2bh = p.get("g2_bh");
        salW = p.get("sal_w");
        salB = p.get("sal_b");
    }

    /** @return la red, o null si no hay pesos utilizables. */
    public static RedImprovisador crear() {
        PesosNeuronales p = PesosNeuronales.cargar(RECURSO);
        if (p == null || !p.tiene(NECESARIOS)) {
            return null;
        }
        RedImprovisador red = new RedImprovisador(p);
        return red.dimensionesValidas() ? red : null;
    }

    /** Comprueba que el fichero corresponde a esta topologia. */
    private boolean dimensionesValidas() {
        return emb.filas == VOCABULARIO && emb.columnas == EMBEBIDO
                && g1wz.filas == OCULTAS && g1wz.columnas == ENTRADA
                && g1uz.filas == OCULTAS && g1uz.columnas == OCULTAS
                && g2wz.filas == OCULTAS && g2wz.columnas == OCULTAS
                && salW.filas == VOCABULARIO && salW.columnas == OCULTAS;
    }

    /** Vuelve al estado inicial. Se llama al empezar cada frase. */
    public void reiniciar() {
        for (int i = 0; i < OCULTAS; i++) {
            h1[i] = 0;
            h2[i] = 0;
        }
    }

    // ------------------------------------------------------------------
    // Fichas
    // ------------------------------------------------------------------

    public static int ficha(int grado, int duracion, int huecoExtra) {
        int g = grado - GRADO_MIN;
        if (g < 0) {
            g = 0;
        } else if (g >= GRADOS) {
            g = GRADOS - 1;
        }
        int d = duracion - 1;
        if (d < 0) {
            d = 0;
        } else if (d >= DURACIONES) {
            d = DURACIONES - 1;
        }
        int h = huecoExtra;
        if (h < 0) {
            h = 0;
        } else if (h >= HUECOS) {
            h = HUECOS - 1;
        }
        return (g * DURACIONES + d) * HUECOS + h;
    }

    public static int gradoDe(int ficha) {
        return ficha / (DURACIONES * HUECOS) + GRADO_MIN;
    }

    public static int duracionDe(int ficha) {
        return (ficha / HUECOS) % DURACIONES + 1;
    }

    public static int huecoExtraDe(int ficha) {
        return ficha % HUECOS;
    }

    // ------------------------------------------------------------------
    // Inferencia
    // ------------------------------------------------------------------

    /**
     * Avanza un paso y devuelve los logits del vocabulario.
     *
     * @param ficha    la del paso anterior, o -1 para el arranque de frase.
     * @param contexto vector de condicionamiento, de largo CONTEXTO.
     */
    public float[] paso(int ficha, float[] contexto) {
        for (int i = 0; i < EMBEBIDO; i++) {
            entrada[i] = 0;
        }
        if (ficha >= 0 && ficha < VOCABULARIO) {
            emb.sumarFila(ficha, entrada, 0);
        }
        System.arraycopy(contexto, 0, entrada, EMBEBIDO, CONTEXTO);

        celda(entrada, h1, g1wz, g1wr, g1wh, g1uz, g1ur, g1uh, g1bz, g1br, g1bh);
        celda(h1, h2, g2wz, g2wr, g2wh, g2uz, g2ur, g2uh, g2bz, g2br, g2bh);

        for (int i = 0; i < VOCABULARIO; i++) {
            logits[i] = 0;
        }
        salW.multiplicarAcumulando(h2, logits);
        salB.sumarA(logits);
        return logits;
    }

    /**
     * Una celda GRU.
     *
     * z = sig(Wz x + Uz h + bz)
     * r = sig(Wr x + Ur h + br)
     * c = tanh(Wh x + Uh (r . h) + bh)
     * h = (1 - z) . h + z . c
     */
    private void celda(float[] x, float[] h,
            Matriz wz, Matriz wr, Matriz wh,
            Matriz uz, Matriz ur, Matriz uh,
            Matriz bz, Matriz br, Matriz bh) {
        for (int i = 0; i < OCULTAS; i++) {
            z[i] = 0;
            r[i] = 0;
        }
        wz.multiplicarAcumulando(x, z);
        uz.multiplicarAcumulando(h, z);
        bz.sumarA(z);
        wr.multiplicarAcumulando(x, r);
        ur.multiplicarAcumulando(h, r);
        br.sumarA(r);
        for (int i = 0; i < OCULTAS; i++) {
            z[i] = Activaciones.sigmoide(z[i]);
            r[i] = Activaciones.sigmoide(r[i]);
            rh[i] = r[i] * h[i];
        }
        for (int i = 0; i < OCULTAS; i++) {
            c[i] = 0;
        }
        wh.multiplicarAcumulando(x, c);
        uh.multiplicarAcumulando(rh, c);
        bh.sumarA(c);
        for (int i = 0; i < OCULTAS; i++) {
            h[i] = (1f - z[i]) * h[i] + z[i] * Activaciones.tanh(c[i]);
        }
    }
}
