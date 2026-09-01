package j4f.sonido;

/**
 * Coro estereo.
 *
 * Cuatro lineas de retardo moduladas, dos por canal, con osciladores a
 * frecuencias distintas y en cuadratura entre izquierda y derecha. Eso ultimo
 * es lo que abre la imagen: si los dos canales se modulan igual, el efecto se
 * oye pero suena en el centro y no ensancha nada.
 *
 * Los retardos son de pocos milisegundos, en la zona donde el oido no separa
 * las copias sino que las funde en una sola voz mas ancha.
 *
 * @author ballestas
 */
public final class Coro {

    /** Retardo maximo en milisegundos. */
    private static final double RETARDO_MAX_MS = 32;
    private static final int VOCES = 2;
    /** Frecuencias de los osciladores, deliberadamente inconmensurables. */
    private static final float[] VELOCIDAD = {0.187f, 0.283f};
    /** Retardo base de cada voz, en milisegundos. */
    private static final float[] BASE_MS = {11.4f, 18.7f};
    /** Profundidad de la modulacion, en milisegundos. */
    private static final float[] PROFUNDIDAD_MS = {3.1f, 4.6f};

    private final float[] lineaIzq;
    private final float[] lineaDer;
    private final int largo;
    private int escritura;

    private final float[] fase = new float[VOCES];
    private final float[] incremento = new float[VOCES];
    private final float[] baseMuestras = new float[VOCES];
    private final float[] profundidadMuestras = new float[VOCES];

    private float mezcla = 0.22f;
    private float realimentacion = 0.16f;

    public Coro(double frecMuestreo) {
        largo = (int) (RETARDO_MAX_MS * 0.001 * frecMuestreo) + 4;
        lineaIzq = new float[largo];
        lineaDer = new float[largo];
        for (int v = 0; v < VOCES; v++) {
            incremento[v] = (float) (VELOCIDAD[v] / frecMuestreo);
            baseMuestras[v] = (float) (BASE_MS[v] * 0.001 * frecMuestreo);
            profundidadMuestras[v] = (float) (PROFUNDIDAD_MS[v] * 0.001 * frecMuestreo);
            fase[v] = v * 0.31f;
        }
    }

    /**
     * @param mezcla         cuanto efecto se suma a la senal seca, 0 a 1.
     * @param realimentacion cuerpo del efecto, 0 a 0,5.
     */
    public void ajustar(double mezcla, double realimentacion) {
        this.mezcla = (float) (mezcla < 0 ? 0 : (mezcla > 1 ? 1 : mezcla));
        this.realimentacion = (float) (realimentacion < 0 ? 0 : (realimentacion > 0.5 ? 0.5 : realimentacion));
    }

    /** Procesa en el sitio. Sin asignaciones: va en el hilo de audio. */
    public void procesar(float[] izq, float[] der, int n) {
        if (mezcla <= 0.001f) {
            return;
        }
        for (int i = 0; i < n; i++) {
            float seco1 = izq[i];
            float seco2 = der[i];
            float humedoI = 0;
            float humedoD = 0;

            for (int v = 0; v < VOCES; v++) {
                float f = fase[v];
                // Cuadratura entre canales: un cuarto de vuelta de desfase.
                float modI = Tablas.seno(f);
                float modD = Tablas.seno(f + 0.25f);
                humedoI += leer(lineaIzq, baseMuestras[v] + profundidadMuestras[v] * modI);
                humedoD += leer(lineaDer, baseMuestras[v] + profundidadMuestras[v] * modD);
                f += incremento[v];
                if (f >= 1f) {
                    f -= 1f;
                }
                fase[v] = f;
            }
            humedoI *= 0.5f;
            humedoD *= 0.5f;

            lineaIzq[escritura] = seco1 + humedoI * realimentacion;
            lineaDer[escritura] = seco2 + humedoD * realimentacion;
            escritura++;
            if (escritura >= largo) {
                escritura = 0;
            }

            izq[i] = seco1 + humedoI * mezcla;
            der[i] = seco2 + humedoD * mezcla;
        }
    }

    /** Lectura interpolada hacia atras desde el cabezal de escritura. */
    private float leer(float[] linea, float retardo) {
        float pos = escritura - retardo;
        while (pos < 0) {
            pos += largo;
        }
        int i = (int) pos;
        float frac = pos - i;
        // Si escritura y retardo casi coinciden, pos queda en un negativo
        // minusculo y sumarle largo en coma flotante de 32 bits redondea
        // justo a largo, con lo que el indice se sale. Falla una vez de cada
        // muchas, que es la peor clase de fallo.
        if (i >= largo) {
            i = 0;
            frac = 0;
        }
        int j = i + 1;
        if (j >= largo) {
            j = 0;
        }
        return linea[i] + (linea[j] - linea[i]) * frac;
    }

    public void limpiar() {
        for (int i = 0; i < largo; i++) {
            lineaIzq[i] = 0;
            lineaDer[i] = 0;
        }
    }
}
