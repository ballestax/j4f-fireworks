package j4f.sonido;

/**
 * Generador pseudoaleatorio para el hilo de audio.
 *
 * No se usa j4f.Azar a proposito: su gauss() acaba en Random.nextGaussian(),
 * que en Java 8 es un metodo synchronized sobre un unico Random estatico que
 * el hilo de animacion machaca sesenta veces por segundo. Tocarlo desde el
 * hilo de audio seria disputar un monitor con el que dibuja, y eso se oye.
 *
 * Cada instancia es de un solo hilo. xorshift64*: rapido, sin estado
 * compartido y de calidad mas que suficiente para ruido.
 *
 * @author ballestas
 */
public final class AzarRapido {

    private long estado;

    public AzarRapido(long semilla) {
        // El estado nunca puede ser cero o la secuencia se queda clavada.
        this.estado = semilla == 0 ? 0x9E3779B97F4A7C15L : semilla;
    }

    public long siguiente() {
        long x = estado;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        estado = x;
        return x * 0x2545F4914F6CDD1DL;
    }

    /** Ruido blanco en [-1, 1). */
    public float bipolar() {
        // 24 bits de mantisa bastan y evitan convertir a double.
        return (siguiente() >> 40) * (1f / 8388608f);
    }

    /** Real en [0, 1). */
    public float unipolar() {
        return (siguiente() >>> 40) * (1f / 16777216f);
    }

    public int entero(int n) {
        if (n <= 0) {
            return 0;
        }
        return (int) ((siguiente() >>> 33) % n);
    }
}
