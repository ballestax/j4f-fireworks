package j4f.sonido;

/**
 * Cola de eventos del hilo generador al hilo de audio.
 *
 * Anillo de un solo productor y un solo consumidor, con arrays primitivos
 * paralelos y dos cursores volatiles. No se usa ConcurrentLinkedQueue a
 * proposito: encolar un objeto por nota significa asignar memoria en el
 * camino que alimenta al audio, y las pausas del recolector se oyen como
 * chasquidos. Aqui no se asigna nada despues del constructor.
 *
 * El campo generacion permite descartar eventos obsoletos cuando cambia el
 * genero o se silencia: se sube el contador y el mezclador ignora lo viejo.
 *
 * @author ballestas
 */
public final class ColaEventos {

    public static final int TIPO_NOTA = 0;
    public static final int TIPO_APAGAR = 1;
    public static final int TIPO_PANICO = 2;

    private final int mascara;
    private final long[] cuandoNs;
    private final int[] tipo;
    private final int[] capa;
    private final int[] nota;
    private final int[] velocidad;
    private final int[] duracionMs;
    private final int[] generacion;

    /** Solo los toca el productor / el consumidor respectivamente. */
    private volatile int escritura;
    private volatile int lectura;

    /** @param capacidad debe ser potencia de dos. */
    public ColaEventos(int capacidad) {
        int c = 1;
        while (c < capacidad) {
            c <<= 1;
        }
        mascara = c - 1;
        cuandoNs = new long[c];
        tipo = new int[c];
        capa = new int[c];
        nota = new int[c];
        velocidad = new int[c];
        duracionMs = new int[c];
        generacion = new int[c];
    }

    /**
     * Encola un evento. Lo llama el hilo generador.
     *
     * @return false si la cola esta llena; el evento se pierde antes que
     *         bloquear al productor o hacer esperar al audio.
     */
    public boolean ofrecer(int t, int cap, int not, int vel, int durMs, long cuando, int gen) {
        int e = escritura;
        int siguiente = (e + 1) & mascara;
        if (siguiente == lectura) {
            return false;
        }
        tipo[e] = t;
        capa[e] = cap;
        nota[e] = not;
        velocidad[e] = vel;
        duracionMs[e] = durMs;
        cuandoNs[e] = cuando;
        generacion[e] = gen;
        escritura = siguiente;
        return true;
    }

    /** @return indice del evento mas antiguo, o -1 si esta vacia. */
    public int siguiente() {
        int l = lectura;
        if (l == escritura) {
            return -1;
        }
        return l;
    }

    /** Consume el evento devuelto por siguiente(). */
    public void avanzar() {
        lectura = (lectura + 1) & mascara;
    }

    public int getTipo(int i) {
        return tipo[i];
    }

    public int getCapa(int i) {
        return capa[i];
    }

    public int getNota(int i) {
        return nota[i];
    }

    public int getVelocidad(int i) {
        return velocidad[i];
    }

    public int getDuracionMs(int i) {
        return duracionMs[i];
    }

    public long getCuandoNs(int i) {
        return cuandoNs[i];
    }

    public int getGeneracion(int i) {
        return generacion[i];
    }

    public void vaciar() {
        lectura = escritura;
    }
}
