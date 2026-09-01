package j4f.sonido;

/**
 * Banco compartido de ruido filtrado por bandas.
 *
 * Compartido, no una copia por voz. Darle a cada una de las veinticuatro
 * voces su propio banco es justo lo que hace inviable el diseno evidente, y
 * ademas correlaciona las voces entre si y hunde la imagen estereo. Aqui se
 * filtra una sola vez y cada voz mezcla las bandas con sus propias
 * ganancias: dieciseis multiplicar-sumar por muestra en vez de una
 * convolucion.
 *
 * Son filtros de variable de estado, que dan pasabanda con dos integradores
 * y ninguna trascendente en el bucle.
 *
 * @author ballestas
 */
public final class BancoRuido {

    public static final int BANDAS = 16;
    /** Reparto logaritmico de las bandas. */
    private static final double FREC_MIN = 80.0;
    private static final double FREC_MAX = 16000.0;
    private static final double Q = 2.4;
    private static final int MAX_BLOQUE = 2048;

    private final float[][] bandas = new float[BANDAS][MAX_BLOQUE];
    private final float[] f = new float[BANDAS];
    private final float[] q = new float[BANDAS];
    private final float[] paso = new float[BANDAS];
    private final float[] banda = new float[BANDAS];
    private final float[] compensacion = new float[BANDAS];

    private final AzarRapido azar;

    public BancoRuido(double frecMuestreo, long semilla) {
        azar = new AzarRapido(semilla);
        double razon = Math.pow(FREC_MAX / FREC_MIN, 1.0 / (BANDAS - 1));
        double frec = FREC_MIN;
        for (int i = 0; i < BANDAS; i++) {
            // Coeficiente del filtro de variable de estado. Se acota por
            // debajo de la zona inestable cerca de Nyquist.
            double w = 2 * Math.sin(Math.PI * Math.min(frec / frecMuestreo, 0.24));
            f[i] = (float) w;
            q[i] = (float) (1.0 / Q);
            // Las bandas estrechas salen mas flojas: se iguala su nivel para
            // que las ganancias de cada voz signifiquen lo mismo en todas.
            compensacion[i] = (float) (1.6 / Math.sqrt(w + 0.02));
            frec *= razon;
        }
    }

    /**
     * Renderiza n muestras en los buferes internos. Sin asignaciones: es el
     * bucle critico del hilo de audio.
     */
    public void procesar(int n) {
        int cuantas = n > MAX_BLOQUE ? MAX_BLOQUE : n;
        for (int i = 0; i < cuantas; i++) {
            float blanco = azar.bipolar();
            for (int b = 0; b < BANDAS; b++) {
                float pb = paso[b];
                float bp = banda[b];
                float alto = blanco - pb - q[b] * bp;
                bp += f[b] * alto;
                pb += f[b] * bp;
                banda[b] = bp;
                paso[b] = pb;
                bandas[b][i] = bp * compensacion[b];
            }
        }
    }

    /** Bufer de una banda, valido hasta el siguiente procesar(). */
    public float[] banda(int indice) {
        return bandas[indice];
    }

    public void limpiar() {
        for (int b = 0; b < BANDAS; b++) {
            paso[b] = 0;
            banda[b] = 0;
        }
    }
}
