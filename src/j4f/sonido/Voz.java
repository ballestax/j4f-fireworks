package j4f.sonido;

/**
 * Una voz del sintetizador.
 *
 * Aqui esta la decision que hace viable todo el motor. Lo evidente seria un
 * oscilador por armonico y voz: con 64 armonicos y 24 voces salen mas de mil
 * osciladores a 48 kHz, que se come mas de medio nucleo. En su lugar, como
 * las amplitudes de los armonicos solo cambian a ritmo de bloque, se suman
 * una vez por bloque en una tabla de ciclo unico y en el bucle de audio corre
 * un unico oscilador interpolante por voz. El coste baja mas de un orden de
 * magnitud y el resultado suena igual.
 *
 * Los armonicos por encima de Nyquist se descartan al reconstruir, asi que no
 * hay aliasing por agudo que sea la nota.
 *
 * @author ballestas
 */
public final class Voz {

    private static final int TAM_TABLA = Instrumento.TAM_TABLA;
    private static final int MASCARA = Instrumento.MASCARA_TABLA;

    /** Apunta a la tabla precocida del instrumento; la voz no la posee. */
    private float[] tabla;

    private final Envolvente envolvente;
    private final double frecMuestreo;

    private Instrumento instrumento;
    private float fase;
    private float faseDestemple;
    private float incremento;
    private float incrementoDestemple;
    private float amplitud;
    private float ganIzq;
    private float ganDer;
    private float envio;

    // Timbre dinamico: filtro de un polo cuyo corte sigue la dinamica y
    // decae con la nota, mas una envolvente propia para el soplo del ataque.
    private float filtroEstado;
    private float brillo;
    private float brilloMinimo;
    private float factorCaidaBrillo;
    private float ruidoEnv;
    private float factorCaidaRuido;
    private double frecuenciaHz;

    // Modulacion de altura: vibrato con retardo de entrada y ligado.
    private float vibratoFase;
    private float vibratoIncr;
    private float vibratoProfundidad;
    private float vibratoEntrada;
    private float vibratoEntradaIncr;
    private float incrementoDestino;
    private float portamentoFactor;

    // Bandas de ruido que de verdad aportan, resueltas al disparar. La
    // campana de cada receta deja la mayoria de las dieciseis ganancias en
    // cero: probarlas todas por muestra eran cientos de miles de
    // comparaciones por bloque que no hacian nada.
    private final int[] bandasActivas = new int[Instrumento.BANDAS_RUIDO];
    private final float[] gananciasActivas = new float[Instrumento.BANDAS_RUIDO];
    private int numBandasActivas;

    private int capa = -1;
    private int nota = -1;
    private boolean enUso;
    private long finNs;
    private boolean duracionFijada;

    public Voz(double frecMuestreo) {
        this.frecMuestreo = frecMuestreo;
        this.envolvente = new Envolvente(frecMuestreo);
    }

    public boolean enUso() {
        return enUso;
    }

    public int getCapa() {
        return capa;
    }

    public int getNota() {
        return nota;
    }

    public long getFinNs() {
        return finNs;
    }

    /** Incremento de fase de la altura destino, para el ligado de la siguiente. */
    public float getIncrementoDestino() {
        return incrementoDestino;
    }

    public boolean sonando() {
        return enUso && envolvente.activa();
    }

    /** Arranca la voz. Se llama desde el hilo de audio, en un limite de bloque. */
    /**
     * Arranca la voz.
     *
     * @param incrementoPrevio incremento de fase de la nota anterior de esta
     *        misma capa, o 0 si no hay. Lo lleva el mezclador y no la voz
     *        porque las voces se reciclan entre capas: guardarlo aqui haria
     *        que un violin ligara desde la ultima nota de un bajo.
     */
    public void disparar(int capa, int nota, int velocidad, Instrumento ins, long finNs,
            float incrementoPrevio) {
        this.capa = capa;
        this.nota = nota;
        this.instrumento = ins;
        this.finNs = finNs;
        this.duracionFijada = true;
        this.enUso = true;

        double frecuencia = 440.0 * Math.pow(2.0, (nota - 69) / 12.0);
        incremento = (float) (frecuencia / frecMuestreo);
        double desaf = Math.pow(2.0, ins.destemple / 12.0);
        incrementoDestemple = (float) (frecuencia * desaf / frecMuestreo);
        fase = 0;
        // Segunda copia arrancada en otro punto: si empiezan juntas se suman
        // en fase y el destemple no se oye hasta que se separan.
        faseDestemple = 0.37f;

        float v = velocidad / 127f;
        amplitud = v * v * ins.ganancia;
        float pan = ins.panoramica;
        ganIzq = (float) Math.sqrt(0.5 * (1 - pan));
        ganDer = (float) Math.sqrt(0.5 * (1 + pan));
        envio = ins.envio;

        envolvente.configurar(ins.ataqueMs, ins.caidaMs, ins.sostenido, ins.soltadoMs);
        envolvente.disparar();

        // Ya esta cocida: disparar una nota no cuesta mas que un indice.
        tabla = ins.tabla(nota);

        // Timbre dinamico: mas fuerte se toca, mas abre el filtro; y desde
        // ahi el brillo cae hacia el minimo con su propio tiempo.
        frecuenciaHz = frecuencia;
        float dinamica = (float) Math.pow(v, 0.8);
        brilloMinimo = ins.brilloMin;
        brillo = ins.brilloMin + (ins.brilloMax - ins.brilloMin) * dinamica;
        factorCaidaBrillo = ins.brilloCaidaMs <= 0 ? 1f
                : (float) Math.exp(-(1000.0 * MotorAudio.BLOQUE / frecMuestreo) / ins.brilloCaidaMs);
        ruidoEnv = 1f;
        factorCaidaRuido = ins.ruidoCaidaMs <= 0 ? 1f
                : (float) Math.exp(-1000.0 / (ins.ruidoCaidaMs * frecMuestreo));
        filtroEstado = 0;

        // Vibrato: el retardo de entrada es lo que separa una nota expresiva
        // de un oscilador. Se hace subir la profundidad, no arrancarla de golpe.
        vibratoProfundidad = ins.vibratoSemitonos;
        vibratoIncr = (float) (ins.vibratoHz / frecMuestreo);
        vibratoFase = 0;
        vibratoEntrada = 0;
        vibratoEntradaIncr = ins.vibratoRetardoMs <= 0 ? 1f
                : (float) (1.0 / (ins.vibratoRetardoMs * 0.001 * frecMuestreo));

        // Portamento: si venimos de otra nota cercana, se llega deslizando.
        incrementoDestino = incremento;
        if (ins.portamentoMs > 0 && incrementoPrevio > 0) {
            double razon = incremento / incrementoPrevio;
            if (razon > 0.5 && razon < 2.0) {
                incremento = incrementoPrevio;
                portamentoFactor = (float) Math.exp(
                        -1.0 / (ins.portamentoMs * 0.001 * frecMuestreo));
            } else {
                portamentoFactor = 0;
            }
        } else {
            portamentoFactor = 0;
        }

        numBandasActivas = 0;
        for (int b = 0; b < Instrumento.BANDAS_RUIDO; b++) {
            if (ins.ruido[b] > 0.0002f) {
                bandasActivas[numBandasActivas] = b;
                gananciasActivas[numBandasActivas] = ins.ruido[b];
                numBandasActivas++;
            }
        }
    }

    public void soltar() {
        envolvente.soltar();
        duracionFijada = false;
    }

    public void cortar() {
        envolvente.cortar();
        enUso = false;
        capa = -1;
        nota = -1;
    }

    /**
     * Renderiza n muestras sumandolas a las mezclas seca y de reverberacion.
     * No asigna memoria: es el bucle critico.
     */
    public void render(float[] izq, float[] der, float[] envIzq, float[] envDer,
            int n, BancoRuido ruido) {
        if (!enUso) {
            return;
        }
        float[] onda = tabla;

        // Coeficiente del filtro para este bloque. El corte va en multiplos
        // de la fundamental; las constantes de tiempo del brillo son de
        // cientos de milisegundos, asi que actualizarlo por bloque basta.
        double corte = frecuenciaHz * brillo;
        if (corte > frecMuestreo * 0.45) {
            corte = frecMuestreo * 0.45;
        } else if (corte < 120) {
            corte = 120;
        }
        float k = (float) (1.0 - Math.exp(-6.2831853 * corte / frecMuestreo));
        brillo = brilloMinimo + (brillo - brilloMinimo) * factorCaidaBrillo;

        for (int i = 0; i < n; i++) {
            float e = envolvente.siguiente();
            if (e <= 0) {
                cortar();
                return;
            }
            // Ligado hacia la altura destino.
            if (portamentoFactor > 0) {
                incremento = incrementoDestino
                        + (incremento - incrementoDestino) * portamentoFactor;
            }
            float paso = incremento;
            if (vibratoProfundidad > 0) {
                if (vibratoEntrada < 1f) {
                    vibratoEntrada += vibratoEntradaIncr;
                    if (vibratoEntrada > 1f) {
                        vibratoEntrada = 1f;
                    }
                }
                // Semitonos a razon de frecuencia, linealizado: para
                // profundidades de decimas de semitono el error no se oye y
                // ahorra una exponencial por muestra.
                float desv = vibratoProfundidad * vibratoEntrada
                        * Tablas.seno(vibratoFase) * 0.0578f;
                paso = incremento * (1f + desv);
                vibratoFase += vibratoIncr;
                if (vibratoFase >= 1f) {
                    vibratoFase -= 1f;
                }
            }
            float pos = fase * TAM_TABLA;
            float s = Tablas.leer(onda, MASCARA, pos);
            // La copia desafinada da cuerpo sin doblar el coste de la tabla.
            if (incrementoDestemple != incremento) {
                s = 0.62f * s + 0.38f * Tablas.leer(onda, MASCARA, faseDestemple * TAM_TABLA);
                faseDestemple += incrementoDestemple;
                if (faseDestemple >= 1f) {
                    faseDestemple -= 1f;
                }
            }
            fase += paso;
            if (fase >= 1f) {
                fase -= 1f;
            }

            float v = s * e * amplitud;
            // El ruido son las bandas compartidas mezcladas con las ganancias
            // de esta voz: dieciseis multiplicar-sumar, no una convolucion.
            // Su envolvente propia es el soplo del ataque: en lo pulsado se
            // apaga enseguida aunque la nota siga.
            float re = e * ruidoEnv * amplitud;
            for (int b = 0; b < numBandasActivas; b++) {
                v += ruido.banda(bandasActivas[b])[i] * gananciasActivas[b] * re;
            }
            ruidoEnv *= factorCaidaRuido;

            // El filtro que da vida al timbre: un polo, cuatro operaciones.
            filtroEstado += k * (v - filtroEstado);
            v = filtroEstado;

            float l = v * ganIzq;
            float r = v * ganDer;
            izq[i] += l;
            der[i] += r;
            envIzq[i] += l * envio;
            envDer[i] += r * envio;
        }
    }
}
