package j4f.red;

/**
 * Funciones de activacion por consulta.
 *
 * Una GRU llama a sigmoide y tangente hiperbolica seis veces por unidad
 * oculta y paso. Con 128 unidades y dos capas son miles de llamadas por nota;
 * resolverlas por tabla evita el coste de las trascendentes.
 *
 * @author ballestas
 */
public final class Activaciones {

    private static final int TAM = 2048;
    private static final float RANGO = 8f;
    private static final float ESCALA = TAM / (2 * RANGO);
    private static final float[] SIGMOIDE = new float[TAM + 1];
    private static final float[] TANH = new float[TAM + 1];

    static {
        for (int i = 0; i <= TAM; i++) {
            double x = -RANGO + i / ESCALA;
            SIGMOIDE[i] = (float) (1.0 / (1.0 + Math.exp(-x)));
            TANH[i] = (float) Math.tanh(x);
        }
    }

    private Activaciones() {
    }

    public static float sigmoide(float x) {
        if (x <= -RANGO) {
            return 0f;
        }
        if (x >= RANGO) {
            return 1f;
        }
        float p = (x + RANGO) * ESCALA;
        int i = (int) p;
        return SIGMOIDE[i] + (SIGMOIDE[i + 1] - SIGMOIDE[i]) * (p - i);
    }

    public static float tanh(float x) {
        if (x <= -RANGO) {
            return -1f;
        }
        if (x >= RANGO) {
            return 1f;
        }
        float p = (x + RANGO) * ESCALA;
        int i = (int) p;
        return TANH[i] + (TANH[i + 1] - TANH[i]) * (p - i);
    }
}
