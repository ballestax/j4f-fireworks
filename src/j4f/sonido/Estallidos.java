package j4f.sonido;

/**
 * Estallidos con posicion en el espacio.
 *
 * Los golpes de las explosiones salian por MIDI, y MIDI no sabe colocar un
 * sonido: el panorama es el CC10, que va por canal, y todos los estallidos
 * comparten el canal de percusion. Un fuego a la izquierda y otro a la derecha
 * sonaban en el mismo sitio, en el centro, uno encima de otro.
 *
 * Asi que los estallidos dejan de ser notas y pasan a sintetizarse aqui,
 * directamente en muestras. Eso da las tres cosas que MIDI no puede dar:
 *
 * - <b>Retardo de propagacion.</b> El sonido va a 343 metros por segundo y la
 *   luz llega en el acto. Ves el destello y el trueno tarda entre tres decimas
 *   y casi un segundo en llegar. Es, con diferencia, lo que mas hace que unos
 *   fuegos suenen a fuegos y no a percusion sincronizada.
 * - <b>Panorama por estallido.</b> Ley de potencia constante, para que al
 *   cruzar el estereo no se hunda el volumen por el centro.
 * - <b>Distancia.</b> Lo lejano llega mas flojo, mas sordo y con mas cola: el
 *   aire se come los agudos antes que los graves, y por eso un trueno lejano
 *   es un retumbe y uno cercano un chasquido.
 *
 * Tres partes por estallido, que es como suena uno de verdad: el chasquido de
 * la carga, el golpe grave del frente de presion y el retumbe que devuelve la
 * ciudad.
 *
 * El hilo de animacion programa y el de audio consume. Entre medias hay un
 * anillo de un solo productor y un solo consumidor, sin bloqueos y sin
 * asignar memoria, porque en el bucle de audio una pausa del recolector se
 * oye como un corte.
 */
public final class Estallidos {

    /** Velocidad del sonido en aire a temperatura templada, en m/s. */
    private static final double VELOCIDAD_SONIDO = 343.0;
    /**
     * Ancho de la escena en metros.
     *
     * No es un dato fisico del programa, es la escala que se le supone: una
     * panoramica de bahia con la ciudad enfrente. De aqui salen los retardos,
     * y es el numero con el que se afina cuanto se separa el trueno del
     * destello.
     */
    private static final double ANCHO_ESCENA_M = 320.0;

    private static final int VOCES = 10;
    private static final int PENDIENTES = 32;
    /** Marca de casilla ya disparada, a la espera de que avance la cabeza. */
    private static final long DISPARADO = Long.MIN_VALUE;

    private final float frec;

    // --- Anillo de pendientes: escribe animacion, lee audio ---
    private final long[] pCuando = new long[PENDIENTES];
    private final float[] pFuerza = new float[PENDIENTES];
    private final float[] pPan = new float[PENDIENTES];
    private final float[] pDistancia = new float[PENDIENTES];
    private volatile int escritura;
    private volatile int lectura;

    // --- Voces ---
    private final boolean[] viva = new boolean[VOCES];
    private final float[] ganIzq = new float[VOCES];
    private final float[] ganDer = new float[VOCES];
    private final float[] envio = new float[VOCES];

    /** Golpe grave: fase, incremento actual y al que cae. */
    private final float[] golpeFase = new float[VOCES];
    private final float[] golpeInc = new float[VOCES];
    private final float[] golpeIncFin = new float[VOCES];
    private final float[] golpeAmp = new float[VOCES];
    private final float[] golpeCaida = new float[VOCES];

    /** Chasquido: ruido por un paso bajo rapido. */
    private final float[] chasAmp = new float[VOCES];
    private final float[] chasCaida = new float[VOCES];
    private final float[] chasA = new float[VOCES];
    private final float[] chasB = new float[VOCES];
    private final float[] chasCorte = new float[VOCES];

    /** Retumbe: ruido por un paso bajo muy cerrado y cola larga. */
    private final float[] retAmp = new float[VOCES];
    private final float[] retCaida = new float[VOCES];
    private final float[] retA = new float[VOCES];
    private final float[] retB = new float[VOCES];
    private final float[] retCorte = new float[VOCES];

    private final AzarRapido azar = new AzarRapido(0x513A11D05L);

    /**
     * Cuanto vale un estallido a plena fuerza en la escala del bus que lo
     * acoge, antes del limitador.
     *
     * Hace falta porque los dos buses trabajan a escalas muy distintas: el
     * mezclador propio multiplica sus voces por 11 (son tenues de por si) y
     * la cadena de Gervill por 3,2. Un estallido sintetizado aqui sale a
     * amplitud cercana a 1, o sea escala completa, asi que sumado tal cual al
     * mezclador entraba al limitador quince veces por encima de su techo.
     * Eso no se oye como un estallido fuerte, se oye como distorsion: el
     * limitador se queda clavado y aplasta tambien la musica.
     */
    private volatile float nivel = 1f;

    /**
     * Volumen de los estallidos, de 0 a 1, el que maneja quien escucha.
     *
     * Aparte del nivel de arriba a proposito: ese es calibracion de la
     * instalacion, que se pone una vez y no se toca, y este es un mando. Si
     * fueran el mismo campo, subir el volumen de los estallidos descalibraria
     * la mezcla.
     */
    private volatile float volumen = 1f;

    public Estallidos(double frecMuestreo) {
        frec = (float) frecMuestreo;
    }

    public void setNivel(float v) {
        nivel = v;
    }

    public void setVolumen(float v) {
        volumen = v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public float getVolumen() {
        return volumen;
    }

    /**
     * Programa un estallido. Se llama desde el hilo de animacion.
     *
     * @param fuerza     de 0 a 1
     * @param pan        -1 izquierda, 0 centro, 1 derecha
     * @param distancia  de 0 (delante) a 1 (al fondo)
     * @param ahoraNs    instante del destello; el sonido se coloca despues
     */
    public void programar(double fuerza, double pan, double distancia, long ahoraNs) {
        int w = escritura;
        int siguiente = (w + 1) % PENDIENTES;
        if (siguiente == lectura) {
            // Anillo lleno: se pierde el estallido antes que bloquear el hilo
            // de animacion o pisar uno que el audio todavia no ha leido.
            return;
        }
        double d = distancia < 0 ? 0 : (distancia > 1 ? 1 : distancia);
        double f = fuerza < 0 ? 0 : (fuerza > 1 ? 1 : fuerza);
        double p = pan < -1 ? -1 : (pan > 1 ? 1 : pan);

        // Metros hasta el observador. La componente en profundidad pesa mas
        // que la lateral: la bahia es ancha pero la ciudad esta enfrente.
        double metros = 40 + d * ANCHO_ESCENA_M + Math.abs(p) * ANCHO_ESCENA_M * 0.25;
        long retardoNs = (long) (metros / VELOCIDAD_SONIDO * 1e9);

        pCuando[w] = ahoraNs + retardoNs;
        pFuerza[w] = (float) f;
        pPan[w] = (float) p;
        pDistancia[w] = (float) d;
        escritura = siguiente;
    }

    /**
     * Suma los estallidos vivos al bloque.
     *
     * Los buffers de envio pueden venir a null: la cadena de Gervill lleva la
     * reverberacion en linea y no tiene bus aparte. En ese caso el envio se
     * suma a la senal seca, que suena algo mas plano pero no se pierde.
     */
    public void render(float[] izq, float[] der, float[] envIzq, float[] envDer,
            int n, long ahoraNs) {
        atender(ahoraNs);
        for (int v = 0; v < VOCES; v++) {
            if (!viva[v]) {
                continue;
            }
            renderVoz(v, izq, der, envIzq, envDer, n);
        }
    }

    /**
     * Saca lo que ya toca sonar y le busca voz.
     *
     * Se recorre el anillo entero y no solo la cabeza, y esto no es una
     * optimizacion que sobra: el anillo va en orden de programacion, pero los
     * estallidos llegan en orden de distancia. Uno lejano programado antes que
     * uno cercano llega despues, y mirando solo la cabeza el cercano se quedaba
     * atascado detras del lejano. Medido: los dos sonaban juntos a 1,195 s en
     * vez de a 0,44 y 1,19.
     *
     * Los disparados se marcan y la cabeza avanza cuando puede. Escribe el
     * hilo de audio en las casillas ya publicadas y el de animacion solo en la
     * de escritura, que el otro nunca toca: no se pisan.
     */
    private void atender(long ahoraNs) {
        int w = escritura;
        for (int i = lectura; i != w; i = (i + 1) % PENDIENTES) {
            if (pCuando[i] == DISPARADO || pCuando[i] - ahoraNs > 0) {
                continue;
            }
            disparar(pFuerza[i], pPan[i], pDistancia[i]);
            pCuando[i] = DISPARADO;
        }
        int r = lectura;
        while (r != w && pCuando[r] == DISPARADO) {
            r = (r + 1) % PENDIENTES;
        }
        lectura = r;
    }

    private void disparar(float fuerza, float pan, float distancia) {
        int v = -1;
        for (int i = 0; i < VOCES; i++) {
            if (!viva[i]) {
                v = i;
                break;
            }
        }
        if (v < 0) {
            // Sin sitio: se roba la de retumbe mas apagado, que es la que
            // menos se echa de menos.
            float peor = Float.MAX_VALUE;
            for (int i = 0; i < VOCES; i++) {
                float e = golpeAmp[i] + retAmp[i];
                if (e < peor) {
                    peor = e;
                    v = i;
                }
            }
        }

        // Potencia constante: al barrer de un lado a otro el nivel no se hunde
        // por el centro, que es lo que pasa con un panorama lineal.
        double ang = (pan + 1) * 0.25 * Math.PI;
        // Lo lejano llega mas flojo. Cuadratico y no lineal: es como cae la
        // presion con la distancia y es lo que da la sensacion de fondo.
        float lejos = 1 - distancia;
        float atenuacion = 0.30f + 0.70f * lejos * lejos;
        float nivel = (0.25f + 0.75f * fuerza) * atenuacion;

        ganIzq[v] = (float) Math.cos(ang) * nivel;
        ganDer[v] = (float) Math.sin(ang) * nivel;
        // Lo lejano suena en un sitio mas grande: mas reverberacion.
        envio[v] = 0.10f + 0.55f * distancia;

        // Golpe grave. Cae de tono mientras suena, que es lo que separa un
        // estallido de una nota de bombo: el frente de presion se expande y
        // baja de frecuencia.
        float base = 78f - 22f * distancia + fuerza * 14f;
        golpeFase[v] = 0;
        golpeInc[v] = (float) (2 * Math.PI * base / frec);
        golpeIncFin[v] = (float) (2 * Math.PI * (base * 0.42) / frec);
        golpeAmp[v] = 0.85f;
        golpeCaida[v] = (float) Math.exp(-1.0 / (frec * (0.24 + 0.30 * distancia)));

        // Chasquido. Se lo come la distancia: el aire absorbe los agudos mucho
        // antes que los graves, y por eso lo lejano es sordo.
        chasAmp[v] = 0.55f * (1 - distancia) * (1 - distancia);
        chasCaida[v] = (float) Math.exp(-1.0 / (frec * 0.055));
        chasCorte[v] = corte(2600 - 1800 * distancia);
        chasA[v] = 0;
        chasB[v] = 0;

        // Retumbe: la cola que devuelven los edificios. Crece con la distancia.
        retAmp[v] = 0.20f + 0.38f * distancia;
        retCaida[v] = (float) Math.exp(-1.0 / (frec * (0.45 + 1.1 * distancia)));
        retCorte[v] = corte(320 - 180 * distancia);
        retA[v] = 0;
        retB[v] = 0;

        viva[v] = true;
    }

    /** Coeficiente de un paso bajo de un polo para la frecuencia dada. */
    private float corte(double hz) {
        if (hz < 40) {
            hz = 40;
        }
        double x = Math.exp(-2 * Math.PI * hz / frec);
        return (float) (1 - x);
    }

    private void renderVoz(int v, float[] izq, float[] der,
            float[] envIzq, float[] envDer, int n) {
        float n_ = nivel * volumen;
        float gi = ganIzq[v] * n_;
        float gd = ganDer[v] * n_;
        float env = envio[v];
        float fase = golpeFase[v];
        float inc = golpeInc[v];
        float incFin = golpeIncFin[v];
        float gAmp = golpeAmp[v];
        float gCae = golpeCaida[v];
        float cAmp = chasAmp[v];
        float cCae = chasCaida[v];
        float cA = chasA[v];
        float cB = chasB[v];
        float cK = chasCorte[v];
        float rAmp = retAmp[v];
        float rCae = retCaida[v];
        float rA = retA[v];
        float rB = retB[v];
        float rK = retCorte[v];

        for (int i = 0; i < n; i++) {
            float ruido = azar.bipolar();

            // Chasquido: dos polos, que uno solo deja pasar demasiado siseo.
            cA += cK * (ruido - cA);
            cB += cK * (cA - cB);
            float chas = cB * cAmp;

            // Retumbe: los mismos dos polos, mucho mas cerrados.
            rA += rK * (ruido - rA);
            rB += rK * (rA - rB);
            float ret = rB * rAmp * 2.4f;

            // Golpe: seno con caida de tono.
            float golpe = (float) Math.sin(fase) * gAmp;
            fase += inc;
            if (fase > 6.2831855f) {
                fase -= 6.2831855f;
            }
            inc += (incFin - inc) * 0.00016f;

            float m = golpe + chas + ret;
            izq[i] += m * gi;
            der[i] += m * gd;
            if (envIzq != null) {
                envIzq[i] += m * gi * env;
                envDer[i] += m * gd * env;
            }

            gAmp *= gCae;
            cAmp *= cCae;
            rAmp *= rCae;
        }

        golpeFase[v] = fase;
        golpeInc[v] = inc;
        golpeAmp[v] = gAmp;
        chasAmp[v] = cAmp;
        chasA[v] = cA;
        chasB[v] = cB;
        retAmp[v] = rAmp;
        retA[v] = rA;
        retB[v] = rB;

        // Se apaga cuando ya no aporta nada audible.
        if (gAmp + cAmp + rAmp < 0.0002f) {
            viva[v] = false;
        }
    }

    /** Corta todo. Para el silencio y el cambio de genero. */
    public void panico() {
        for (int v = 0; v < VOCES; v++) {
            viva[v] = false;
            golpeAmp[v] = 0;
            chasAmp[v] = 0;
            retAmp[v] = 0;
        }
        lectura = escritura;
    }
}
