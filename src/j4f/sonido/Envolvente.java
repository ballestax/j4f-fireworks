package j4f.sonido;

/**
 * Envolvente ADSR por voz.
 *
 * Los tramos son exponenciales, no lineales: el oido percibe la amplitud de
 * forma logaritmica y una rampa recta suena artificial, sobre todo al soltar.
 * Se implementa con un coeficiente por muestra y un objetivo, que es un
 * multiplicar-sumar por muestra y ninguna trascendente.
 *
 * @author ballestas
 */
public final class Envolvente {

    /** Por debajo de esto la voz se considera apagada y se libera. */
    private static final float SILENCIO = 0.0001f;
    /** Cuanto se pasa el objetivo para que el tramo termine de verdad. */
    private static final float EXCESO = 0.06f;

    public static final int APAGADA = 0;
    public static final int ATAQUE = 1;
    public static final int CAIDA = 2;
    public static final int SOSTENIDO = 3;
    public static final int SOLTADO = 4;

    private int etapa = APAGADA;
    private float nivel;
    private float objetivo;
    private float coeficiente;

    private float coefAtaque;
    private float coefCaida;
    private float coefSoltado;
    private float nivelSostenido;

    private final double frecMuestreo;

    public Envolvente(double frecMuestreo) {
        this.frecMuestreo = frecMuestreo;
        configurar(5, 200, 0.7f, 400);
    }

    /**
     * Ajusta los tiempos. Se llama al disparar la nota, nunca por muestra,
     * asi que aqui si se puede usar Math.exp.
     */
    public void configurar(double ataqueMs, double caidaMs, float sostenido, double soltadoMs) {
        coefAtaque = coef(ataqueMs);
        coefCaida = coef(caidaMs);
        coefSoltado = coef(soltadoMs);
        nivelSostenido = sostenido < 0 ? 0 : (sostenido > 1 ? 1 : sostenido);
    }

    private float coef(double ms) {
        double muestras = ms * 0.001 * frecMuestreo;
        if (muestras < 1) {
            muestras = 1;
        }
        return (float) Math.exp(-1.0 / muestras);
    }

    public void disparar() {
        etapa = ATAQUE;
        coeficiente = coefAtaque;
        // Se apunta por encima de 1 para que el tramo cruce el umbral y no
        // se quede asintotico sin llegar nunca.
        objetivo = 1f + EXCESO;
    }

    public void soltar() {
        if (etapa == APAGADA) {
            return;
        }
        etapa = SOLTADO;
        coeficiente = coefSoltado;
        objetivo = -EXCESO;
    }

    /** Corta de golpe: solo para robar la voz. */
    public void cortar() {
        etapa = APAGADA;
        nivel = 0;
    }

    public boolean activa() {
        return etapa != APAGADA;
    }

    public int getEtapa() {
        return etapa;
    }

    public float siguiente() {
        if (etapa == APAGADA) {
            return 0;
        }
        nivel = objetivo + (nivel - objetivo) * coeficiente;
        switch (etapa) {
            case ATAQUE:
                if (nivel >= 1f) {
                    nivel = 1f;
                    etapa = CAIDA;
                    coeficiente = coefCaida;
                    objetivo = nivelSostenido;
                }
                break;
            case CAIDA:
                if (nivel <= nivelSostenido + SILENCIO) {
                    nivel = nivelSostenido;
                    etapa = SOSTENIDO;
                    objetivo = nivelSostenido;
                    coeficiente = 1f;
                }
                break;
            case SOLTADO:
                if (nivel <= SILENCIO) {
                    nivel = 0;
                    etapa = APAGADA;
                }
                break;
            default:
                break;
        }
        return nivel;
    }
}
