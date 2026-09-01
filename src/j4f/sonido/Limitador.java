package j4f.sonido;

/**
 * Limitador de pico del bus maestro.
 *
 * Con dieciseis voces sumandose sin control el bus se sale del rango y la
 * tarjeta recorta a lo bruto, que es el ruido mas feo que puede hacer un
 * sintetizador. Este limitador va delante y garantiza que ninguna muestra
 * supera el techo.
 *
 * Tres decisiones que importan:
 *
 * - Ataque instantaneo: la ganancia se calcula con el pico de la muestra que
 *   se esta escribiendo, no con el de la anterior, asi que no hay rebase
 *   posible y no hace falta linea de anticipacion (que costaria latencia).
 * - Liberacion suavizada con un polo: si la ganancia volviera de golpe cada
 *   pad sostenido respiraria al ritmo del bombeo.
 * - Rodilla blanda cuadratica: la reduccion empieza antes del techo y de
 *   forma gradual, de modo que el limitador se nota como compresion y no
 *   como distorsion. Es una parabola, no hay logaritmos por muestra.
 *
 * Los dos canales comparten ganancia. Si cada uno se limitara por su cuenta,
 * un golpe fuerte a un lado moveria la imagen estereo entera.
 *
 * Regla del hilo de audio: procesar() no reserva memoria ni llama a funciones
 * trascendentes.
 *
 * @author ballestas
 */
public final class Limitador {

    // ------------------------------------------------------------------
    // Constantes de ajuste
    // ------------------------------------------------------------------

    /** Techo por defecto: deja margen para el redondeo a entero de 16 bits. */
    private static final double TECHO_INICIAL = 0.95;
    /** Liberacion por defecto en ms: rapida para percusion, sin bombeo. */
    private static final double LIBERACION_INICIAL = 120.0;

    /** Techo minimo admisible; por debajo la division no tiene sentido. */
    private static final double TECHO_MINIMO = 0.001;
    /** Liberacion minima en ms; mas corta suena a distorsion. */
    private static final double LIBERACION_MINIMA = 1.0;

    /**
     * Donde arranca la rodilla, en fraccion del techo. A 0.70 la reduccion
     * empieza unos 3 dB antes de tocar el limite.
     */
    private static final float INICIO_RODILLA = 0.70f;

    private static final double MS_POR_SEGUNDO = 1000.0;

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    private final double frecMuestreo;

    private float techo;
    private float umbral;
    /** 1 / (4 * (techo - umbral)): el coeficiente de la parabola. */
    private float curvatura;
    /** Pico a partir del cual la rodilla ya es plana. */
    private float finRodilla;
    private float coefLiberacion;

    /** Ganancia aplicada ahora mismo. 1 = sin reduccion. */
    private float ganancia;

    public Limitador(double frecMuestreo) {
        this.frecMuestreo = frecMuestreo > 0.0 ? frecMuestreo : 48000.0;
        this.ganancia = 1f;
        ajustar(TECHO_INICIAL, LIBERACION_INICIAL);
    }

    /** techo lineal en 0..1; liberacionMs, tiempo de recuperacion. */
    public void ajustar(double techo, double liberacionMs) {
        double t = techo;
        if (t < TECHO_MINIMO) {
            t = TECHO_MINIMO;
        } else if (t > 1.0) {
            t = 1.0;
        }
        double lib = liberacionMs < LIBERACION_MINIMA ? LIBERACION_MINIMA : liberacionMs;

        this.techo = (float) t;
        this.umbral = (float) (t * INICIO_RODILLA);
        this.finRodilla = (float) (2.0 * t - t * INICIO_RODILLA);
        this.curvatura = (float) (1.0 / (4.0 * (t - t * INICIO_RODILLA)));
        // Polo de un solo coeficiente: la ganancia recupera el 63 % en lib ms.
        this.coefLiberacion = (float) Math.exp(-MS_POR_SEGUNDO / (lib * frecMuestreo));
        if (this.ganancia > 1f) {
            this.ganancia = 1f;
        }
    }

    /** Limita en sitio, con la misma ganancia para los dos canales. */
    public void procesar(float[] izq, float[] der, int n) {
        int total = n;
        if (izq.length < total) {
            total = izq.length;
        }
        if (der.length < total) {
            total = der.length;
        }

        final float lim = techo;
        final float umb = umbral;
        final float fin = finRodilla;
        final float k = curvatura;
        final float rel = coefLiberacion;
        float g = ganancia;

        for (int i = 0; i < total; i++) {
            float a = izq[i];
            float b = der[i];
            float pa = a < 0f ? -a : a;
            float pb = b < 0f ? -b : b;
            float pico = pa > pb ? pa : pb;

            float objetivo;
            if (pico <= umb) {
                objetivo = 1f;
            } else {
                float permitido;
                if (pico < fin) {
                    // Parabola tangente a la identidad en el umbral y plana en
                    // el techo: continua en valor y en pendiente, o sea sin
                    // el chasquido que da una rodilla dura.
                    float e = pico - umb;
                    permitido = pico - e * e * k;
                } else {
                    permitido = lim;
                }
                objetivo = permitido / pico;
            }

            // Baja de golpe, sube despacio.
            if (objetivo < g) {
                g = objetivo;
            } else {
                g = objetivo + (g - objetivo) * rel;
            }

            izq[i] = a * g;
            der[i] = b * g;
        }

        ganancia = g;
    }

    /** Reduccion aplicada ahora: 0 = ninguna, 0.5 = mitad de ganancia. */
    public float reduccionActual() {
        return 1f - ganancia;
    }
}
