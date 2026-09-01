package j4f.red;

/**
 * Matriz de pesos cuantizada a ocho bits con una escala por fila.
 *
 * Cuantizar por fila y no para toda la matriz importa: las filas de una capa
 * entrenada tienen rangos muy distintos entre si, y una sola escala global
 * aplastaria las de menor amplitud hasta dejarlas en ruido.
 *
 * @author ballestas
 */
public final class Matriz {

    public final int filas;
    public final int columnas;
    private final byte[] datos;
    private final float[] escalas;

    public Matriz(int filas, int columnas, byte[] datos, float[] escalas) {
        this.filas = filas;
        this.columnas = columnas;
        this.datos = datos;
        this.escalas = escalas;
    }

    /**
     * salida += this * entrada. Acumula en vez de asignar para poder sumar
     * varias contribuciones (entrada, estado y sesgo) sin buferes temporales.
     */
    public void multiplicarAcumulando(float[] entrada, float[] salida) {
        int c = columnas;
        for (int f = 0; f < filas; f++) {
            int base = f * c;
            float suma = 0;
            for (int j = 0; j < c; j++) {
                suma += datos[base + j] * entrada[j];
            }
            salida[f] += suma * escalas[f];
        }
    }

    /** Suma la fila indicada, que es como se busca en una tabla de embebidos. */
    public void sumarFila(int fila, float[] salida, int desplazamiento) {
        int base = fila * columnas;
        float e = escalas[fila];
        for (int j = 0; j < columnas; j++) {
            salida[desplazamiento + j] = datos[base + j] * e;
        }
    }

    /** Vector de sesgos: se guarda como matriz de una fila. */
    public void sumarA(float[] salida) {
        float e = escalas[0];
        for (int j = 0; j < columnas; j++) {
            salida[j] += datos[j] * e;
        }
    }
}
