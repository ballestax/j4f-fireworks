package j4f.sonido;

/**
 * Tablas precalculadas para el hilo de audio.
 *
 * En el bucle de render no se llama a Math.sin ni a Math.exp: a 48000 muestras
 * por segundo y varias voces, el coste de las trascendentes domina el
 * presupuesto. Todo se resuelve por consulta con interpolacion lineal.
 *
 * @author ballestas
 */
public final class Tablas {

    /** Potencia de dos: permite enmascarar en vez de dividir. */
    public static final int TAM_SENO = 4096;
    private static final int MASCARA_SENO = TAM_SENO - 1;
    private static final float[] SENO = new float[TAM_SENO + 1];

    static {
        for (int i = 0; i <= TAM_SENO; i++) {
            SENO[i] = (float) Math.sin(2 * Math.PI * i / TAM_SENO);
        }
    }

    private Tablas() {
    }

    /** Seno por consulta. La fase va en vueltas (0..1), no en radianes. */
    public static float seno(float vueltas) {
        float pos = vueltas * TAM_SENO;
        int i = (int) pos;
        float f = pos - i;
        i &= MASCARA_SENO;
        return SENO[i] + (SENO[i + 1] - SENO[i]) * f;
    }

    /** Consulta interpolada en una tabla de ciclo unico de tamano potencia de dos. */
    public static float leer(float[] tabla, int mascara, float pos) {
        int i = (int) pos;
        float f = pos - i;
        int a = i & mascara;
        int b = (i + 1) & mascara;
        return tabla[a] + (tabla[b] - tabla[a]) * f;
    }
}
