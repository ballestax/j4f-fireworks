package j4f.sonido;

/**
 * Reverberacion estereo: red de retardos realimentada (FDN de 8 lineas con
 * matriz de Hadamard) precedida por cuatro all-pass de difusion.
 *
 * Es la pieza que da sensacion de espacio al sintetizador. Sin ella la suma
 * de voces aditivas suena a laboratorio y pierde la comparacion contra el
 * wavetable del sistema, que al menos trae su propio ambiente.
 *
 * Por que FDN y no ocho peines de Schroeder: con la misma cuenta de retardos
 * la matriz ortogonal reparte cada eco entre todas las lineas, asi que la
 * densidad de ecos crece exponencialmente en vez de linealmente y la cola no
 * deja oir los peines por separado. La transformada de Hadamard ademas se
 * resuelve con 24 sumas por muestra, sin una sola multiplicacion de matriz.
 *
 * Los largos de las lineas se afinan siempre a numeros primos (de ahi el
 * tamiz del constructor): si dos retardos comparten divisor sus ecos
 * coinciden periodicamente y aparece el flameo metalico del reverb mal
 * afinado.
 *
 * Regla del hilo de audio: ni una reserva de memoria en procesar(). Todos los
 * buffers salen del constructor.
 *
 * @author ballestas
 */
public final class Reverberacion {

    // ------------------------------------------------------------------
    // Constantes de ajuste
    // ------------------------------------------------------------------

    /** Lineas de la red. Con menos de 8 la cola suena granulada. */
    private static final int LINEAS = 8;
    /** All-pass de entrada. Reparten el ataque antes de entrar al bucle. */
    private static final int DIFUSORES = 4;

    /** Frecuencia para la que estan pensados los largos base. */
    private static final double FREC_REFERENCIA = 48000.0;

    /**
     * Largos base en muestras a 48 kHz (30 a 62 ms). Repartidos de forma
     * irregular: en progresion regular la cola cria resonancias.
     */
    private static final int[] LARGOS_BASE = {1447, 1637, 1811, 2053, 2251, 2467, 2711, 2999};

    /** Largos de los difusores, cortos y dispares (3 a 8 ms). */
    private static final int[] LARGOS_DIFUSOR = {149, 373, 211, 337};

    /** Coeficiente de los all-pass. Por encima de 0.75 se oye el arrastre. */
    private static final float G_DIFUSOR = 0.62f;

    /** Largo minimo admisible de una linea, por seguridad al reducir tamano. */
    private static final int LARGO_MINIMO = 61;

    /** Fraccion del largo base con tamano = 0 y con tamano = 1. */
    private static final double ESCALA_MINIMA = 0.30;
    private static final double ESCALA_MAXIMA = 1.00;

    /** RT60 en segundos con tamano = 0 y con tamano = 1. */
    private static final double RT60_MINIMO = 0.45;
    private static final double RT60_MAXIMO = 3.90;

    /** Corte del amortiguador del bucle, sin y con amortiguacion. */
    private static final double CORTE_MAXIMO = 16000.0;
    private static final double CORTE_MINIMO = 1400.0;

    /** Normalizacion de la Hadamard de 8 puntos: 1/sqrt(8). */
    private static final float NORMA_HADAMARD = 0.35355338f;

    /** Escala de las tomas de salida, para que la cola quepa en el rango. */
    private static final float ESCALA_SALIDA = 0.40f;

    /**
     * Sesgo minusculo sumado en el amortiguador. Cuando la cola se apaga los
     * estados caen en rango subnormal y cada operacion pasa a costar cientos
     * de ciclos; este offset los mantiene normalizados y queda 200 dB por
     * debajo de cualquier cosa audible.
     */
    private static final float ANTI_SUBNORMAL = 1.0e-20f;

    /** Ajuste por defecto: sala amplia pero no invasiva. */
    private static final double TAMANO_INICIAL = 0.70;
    private static final double AMORTIGUACION_INICIAL = 0.35;
    private static final double MEZCLA_INICIAL = 0.35;

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    private final double frecMuestreo;

    /** primoAbajo[i] = mayor primo menor o igual que i. Para afinar largos. */
    private final int[] primoAbajo;

    private final float[][] lineas;
    private final int[] largo;
    private final int[] indice;
    private final float[] realimentacion;
    private final float[] amortiguado;

    private final float[][] difusor;
    private final int[] largoDifusor;
    private final int[] indiceDifusor;

    /** Vector de trabajo de la red. Vive aqui para no crearlo por muestra. */
    private final float[] eco;

    private float coefAmortiguacion;
    private float mezcla;

    public Reverberacion(double frecMuestreo) {
        this.frecMuestreo = frecMuestreo > 0.0 ? frecMuestreo : FREC_REFERENCIA;
        double razon = this.frecMuestreo / FREC_REFERENCIA;

        lineas = new float[LINEAS][];
        largo = new int[LINEAS];
        indice = new int[LINEAS];
        realimentacion = new float[LINEAS];
        amortiguado = new float[LINEAS];
        eco = new float[LINEAS];

        difusor = new float[DIFUSORES][];
        largoDifusor = new int[DIFUSORES];
        indiceDifusor = new int[DIFUSORES];

        int mayor = 0;
        for (int k = 0; k < LINEAS; k++) {
            int n = (int) Math.ceil(LARGOS_BASE[k] * razon * ESCALA_MAXIMA) + 1;
            lineas[k] = new float[n];
            if (n > mayor) {
                mayor = n;
            }
        }
        for (int k = 0; k < DIFUSORES; k++) {
            int n = (int) Math.ceil(LARGOS_DIFUSOR[k] * razon) + 1;
            difusor[k] = new float[n];
            if (n > mayor) {
                mayor = n;
            }
        }

        primoAbajo = new int[mayor + 1];
        tamizar();

        for (int k = 0; k < DIFUSORES; k++) {
            largoDifusor[k] = afinar((int) (LARGOS_DIFUSOR[k] * razon), difusor[k].length);
        }

        ajustar(TAMANO_INICIAL, AMORTIGUACION_INICIAL, MEZCLA_INICIAL);
    }

    /**
     * tamano, amortiguacion y mezcla en 0..1. Es una llamada de control: no
     * reserva memoria, pero cambia largos de retardo y eso da un pequeno salto
     * en la cola, asi que no conviene automatizarla por fotograma.
     */
    public void ajustar(double tamano, double amortiguacion, double mezcla) {
        double t = acotar(tamano);
        double a = acotar(amortiguacion);
        this.mezcla = (float) acotar(mezcla);

        double escala = ESCALA_MINIMA + (ESCALA_MAXIMA - ESCALA_MINIMA) * t;
        double rt60 = RT60_MINIMO + (RT60_MAXIMO - RT60_MINIMO) * t;
        double razon = frecMuestreo / FREC_REFERENCIA;

        for (int k = 0; k < LINEAS; k++) {
            int n = afinar((int) (LARGOS_BASE[k] * razon * escala), lineas[k].length);
            largo[k] = n;
            if (indice[k] >= n) {
                indice[k] = 0;
            }
            // Ganancia que deja caer 60 dB en rt60 segundos para este largo.
            realimentacion[k] = (float) Math.pow(10.0, -3.0 * n / (frecMuestreo * rt60));
        }

        // Corte interpolado en escala logaritmica: el oido cuenta octavas.
        double corte = CORTE_MAXIMO * Math.pow(CORTE_MINIMO / CORTE_MAXIMO, a);
        double c = 1.0 - Math.exp(-2.0 * Math.PI * corte / frecMuestreo);
        coefAmortiguacion = (float) (c > 1.0 ? 1.0 : c);
    }

    /**
     * Suma la cola humeda sobre la senal seca, en sitio. Sin reservas, sin
     * funciones trascendentes y sin ramas que dependan de los datos.
     */
    public void procesar(float[] izq, float[] der, int n) {
        int total = n;
        if (izq.length < total) {
            total = izq.length;
        }
        if (der.length < total) {
            total = der.length;
        }

        final float g = G_DIFUSOR;
        final float c = coefAmortiguacion;
        final float m = mezcla * ESCALA_SALIDA;

        for (int i = 0; i < total; i++) {
            float secoIzq = izq[i];
            float secoDer = der[i];

            // Difusion: dos all-pass por canal, con largos distintos, de modo
            // que izquierda y derecha entran ya descorrelacionadas a la red.
            float entIzq = secoIzq;
            for (int k = 0; k < 2; k++) {
                int p = indiceDifusor[k];
                float retardado = difusor[k][p];
                float salida = retardado - g * entIzq;
                difusor[k][p] = entIzq + g * retardado;
                indiceDifusor[k] = p + 1 >= largoDifusor[k] ? 0 : p + 1;
                entIzq = salida;
            }
            float entDer = secoDer;
            for (int k = 2; k < DIFUSORES; k++) {
                int p = indiceDifusor[k];
                float retardado = difusor[k][p];
                float salida = retardado - g * entDer;
                difusor[k][p] = entDer + g * retardado;
                indiceDifusor[k] = p + 1 >= largoDifusor[k] ? 0 : p + 1;
                entDer = salida;
            }

            // Lectura de las ocho lineas. Las cuatro primeras alimentan la
            // toma izquierda y las otras cuatro la derecha: como sus largos
            // son primos distintos, los dos canales nunca se parecen.
            float humedoIzq = 0f;
            float humedoDer = 0f;
            for (int k = 0; k < LINEAS; k++) {
                float y = lineas[k][indice[k]];
                if ((k & 4) == 0) {
                    humedoIzq += (k & 1) == 0 ? y : -y;
                } else {
                    humedoDer += (k & 1) == 0 ? y : -y;
                }
                // Amortiguacion dentro del bucle: sin ella la cola brilla
                // igual del principio al final y suena a lata, no a sala.
                float d = amortiguado[k] + c * (y - amortiguado[k]) + ANTI_SUBNORMAL;
                amortiguado[k] = d;
                eco[k] = d * realimentacion[k];
            }

            // Hadamard de 8 puntos por mariposas. Es ortonormal, asi que la
            // caida de la cola depende solo de realimentacion[].
            float s0 = eco[0], s1 = eco[1], s2 = eco[2], s3 = eco[3];
            float s4 = eco[4], s5 = eco[5], s6 = eco[6], s7 = eco[7];
            float a0 = s0 + s1, a1 = s0 - s1, a2 = s2 + s3, a3 = s2 - s3;
            float a4 = s4 + s5, a5 = s4 - s5, a6 = s6 + s7, a7 = s6 - s7;
            float b0 = a0 + a2, b1 = a1 + a3, b2 = a0 - a2, b3 = a1 - a3;
            float b4 = a4 + a6, b5 = a5 + a7, b6 = a4 - a6, b7 = a5 - a7;
            eco[0] = (b0 + b4) * NORMA_HADAMARD;
            eco[1] = (b1 + b5) * NORMA_HADAMARD;
            eco[2] = (b2 + b6) * NORMA_HADAMARD;
            eco[3] = (b3 + b7) * NORMA_HADAMARD;
            eco[4] = (b0 - b4) * NORMA_HADAMARD;
            eco[5] = (b1 - b5) * NORMA_HADAMARD;
            eco[6] = (b2 - b6) * NORMA_HADAMARD;
            eco[7] = (b3 - b7) * NORMA_HADAMARD;

            // Inyeccion con signos alternos: meter la entrada en fase por las
            // ocho lineas daria un pico inicial y una cola hueca detras.
            for (int k = 0; k < LINEAS; k++) {
                float entrada = (k & 4) == 0 ? entIzq : entDer;
                if ((k & 2) != 0) {
                    entrada = -entrada;
                }
                int p = indice[k];
                lineas[k][p] = eco[k] + entrada;
                indice[k] = p + 1 >= largo[k] ? 0 : p + 1;
            }

            izq[i] = secoIzq + humedoIzq * m;
            der[i] = secoDer + humedoDer * m;
        }
    }

    /** Vacia lineas y difusores. Se llama al parar el espectaculo. */
    public void limpiar() {
        for (int k = 0; k < LINEAS; k++) {
            float[] b = lineas[k];
            for (int i = 0; i < b.length; i++) {
                b[i] = 0f;
            }
            amortiguado[k] = 0f;
            indice[k] = 0;
        }
        for (int k = 0; k < DIFUSORES; k++) {
            float[] b = difusor[k];
            for (int i = 0; i < b.length; i++) {
                b[i] = 0f;
            }
            indiceDifusor[k] = 0;
        }
    }

    // ------------------------------------------------------------------
    // Interno
    // ------------------------------------------------------------------

    /** Criba de Eratostenes; deja en primoAbajo el primo anterior a cada i. */
    private void tamizar() {
        boolean[] compuesto = new boolean[primoAbajo.length];
        for (int i = 2; (long) i * i < primoAbajo.length; i++) {
            if (!compuesto[i]) {
                for (int j = i * i; j < primoAbajo.length; j += i) {
                    compuesto[j] = true;
                }
            }
        }
        int ultimo = 2;
        for (int i = 0; i < primoAbajo.length; i++) {
            if (i >= 2 && !compuesto[i]) {
                ultimo = i;
            }
            primoAbajo[i] = ultimo;
        }
    }

    /** Mayor primo que cabe en el buffer y no baja del minimo. */
    private int afinar(int deseado, int capacidad) {
        int n = deseado;
        if (n >= capacidad) {
            n = capacidad - 1;
        }
        if (n >= primoAbajo.length) {
            n = primoAbajo.length - 1;
        }
        if (n < LARGO_MINIMO) {
            n = LARGO_MINIMO;
        }
        int p = primoAbajo[n];
        return p < 2 ? 2 : p;
    }

    private static double acotar(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
