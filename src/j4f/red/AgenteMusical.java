package j4f.red;

/**
 * Capa de politica: decide, observa el resultado y se ajusta.
 *
 * Esto es lo que separa a un agente de un modelo. La red de frases predice;
 * esta capa percibe el espectaculo, decide sobre la forma de la pieza y mide
 * si le salio bien.
 *
 * No es una red neuronal y no lo finge. Un perceptron de quinientos
 * parametros sin senal de recompensa fuera de linea no es entrenable fuera de
 * linea, asi que lo defendible es una politica lineal con los pesos puestos a
 * mano y adaptacion en linea tipo bandido contextual. Llamarla "la tercera
 * red" seria vender humo.
 *
 * Propiedad de seguridad: las caracteristicas se centran en un punto neutro,
 * asi que con energia visual a 0,5 y pesos a cero el ajuste es exactamente
 * cero y el motor se comporta igual que antes de existir esta clase.
 *
 * @author ballestas
 */
public final class AgenteMusical {

    /** Decisiones sobre las que manda. */
    public static final int MODULAR = 0;
    public static final int RECORDAR = 1;
    public static final int INTENSIDAD = 2;
    private static final int DECISIONES = 3;

    /** energia, densidad, avance de seccion, sesgo. */
    private static final int CARACTERISTICAS = 4;

    /** Punto neutro de cada caracteristica. */
    private static final double[] NEUTRO = {0.5, 0.5, 0.5, 0.0};

    /** Los pesos no pueden irse de aqui, por mucho que adapte. */
    private static final double TOPE = 1.2;
    /** Cuanto se mueve por cada seccion observada. */
    private static final double APRENDIZAJE = 0.04;
    /** Inercia de la referencia, solo para diagnostico. */
    private static final double INERCIA = 0.15;

    /**
     * Prior puesto a mano: mas pirotecnia empuja a modular, a recuperar un
     * motivo conocido (que es lo que se reconoce en un climax) y a subir la
     * intensidad. Son las tendencias que uno escribiria a mano; el bandido
     * solo ajusta cuanto.
     */
    private static final double[] PRIOR_ENERGIA = {0.55, 0.40, 0.95};

    private final double[][] pesos = new double[DECISIONES][CARACTERISTICAS];
    private final double[] caracteristicas = new double[CARACTERISTICAS];
    private final double[] ultimaActivacion = new double[DECISIONES];
    private final double[] ultimasCaracteristicas = new double[CARACTERISTICAS];

    private double referencia = 0.5;
    private double energia;
    private double densidad;
    private double avanceSeccion;
    private int seccionesObservadas;
    private boolean hayPendiente;

    public AgenteMusical() {
        reiniciar();
    }

    /** Vuelve al prior. Se llama al cambiar de genero, para que no arrastre. */
    public void reiniciar() {
        for (int d = 0; d < DECISIONES; d++) {
            for (int c = 0; c < CARACTERISTICAS; c++) {
                pesos[d][c] = 0;
            }
            pesos[d][0] = PRIOR_ENERGIA[d];
        }
        referencia = 0.5;
        hayPendiente = false;
    }

    /** Estado del mundo, antes de decidir. */
    public void observar(double energiaVisual, double densidadActual, double avance) {
        this.energia = acotar(energiaVisual);
        this.densidad = acotar(densidadActual);
        this.avanceSeccion = acotar(avance);
    }

    /**
     * Ajusta una probabilidad base segun la situacion.
     *
     * @param decision cual de las tres.
     * @param base     la probabilidad que usaria el motor sin agente.
     * @return la probabilidad corregida, siempre dentro de un margen sensato.
     */
    public double ajustar(int decision, double base) {
        if (decision < 0 || decision >= DECISIONES) {
            return base;
        }
        montar();
        double z = 0;
        for (int c = 0; c < CARACTERISTICAS; c++) {
            z += pesos[decision][c] * (caracteristicas[c] - NEUTRO[c]);
        }
        ultimaActivacion[decision] = z;
        System.arraycopy(caracteristicas, 0, ultimasCaracteristicas, 0, CARACTERISTICAS);
        hayPendiente = true;
        // Se mueve la probabilidad, no se sustituye: el criterio del motor
        // sigue mandando y el agente solo lo inclina.
        double p = base + 0.35 * Math.tanh(z);
        if (p < 0.02) {
            p = 0.02;
        } else if (p > 0.98) {
            p = 0.98;
        }
        return p;
    }

    /** Cuanta intensidad pide ahora, de 0 a 1. Sirve para elegir seccion. */
    public double intensidadDeseada() {
        montar();
        double z = 0;
        for (int c = 0; c < CARACTERISTICAS; c++) {
            z += pesos[INTENSIDAD][c] * (caracteristicas[c] - NEUTRO[c]);
        }
        return acotar(0.5 + 0.5 * Math.tanh(z));
    }

    private void montar() {
        caracteristicas[0] = energia;
        caracteristicas[1] = densidad;
        caracteristicas[2] = avanceSeccion;
        caracteristicas[3] = 1;
    }

    /**
     * Cierra el lazo: compara lo que se pedia con lo que de verdad paso.
     *
     * Recompensa alta cuando la actividad de la musica acompana a la de la
     * pantalla. La actualizacion es del tipo REINFORCE con una referencia
     * movil, aplicada una vez por seccion, o sea cada medio minuto largo: el
     * coste es nulo y a lo largo de un espectaculo se nota que acompaña.
     */
    public void recompensar(double energiaVisual, double actividadObservada) {
        if (!hayPendiente) {
            return;
        }
        hayPendiente = false;
        double deseada = acotar(energiaVisual);
        double lograda = acotar(actividadObservada);
        // Error CON SIGNO. Con el valor absoluto la politica solo sabia que lo
        // habia hecho mal, no hacia que lado corregir, y acababa bajando la
        // respuesta justo cuando el espectaculo pedia mas.
        double error = deseada - lograda;
        referencia += INERCIA * ((1.0 - Math.abs(error)) - referencia);

        for (int d = 0; d < DECISIONES; d++) {
            // El sesgo NO se adapta. Si lo hiciera, derivaria y el agente
            // dejaria de reducirse a la conducta de siempre en el punto
            // neutro, que es justo la propiedad de seguridad que se prometio.
            for (int c = 0; c < CARACTERISTICAS - 1; c++) {
                double paso = APRENDIZAJE * error * (ultimasCaracteristicas[c] - NEUTRO[c]);
                double w = pesos[d][c] + paso;
                // Acotar impide que una mala racha deje la politica en un
                // estado del que no vuelve.
                if (w > TOPE) {
                    w = TOPE;
                } else if (w < -TOPE) {
                    w = -TOPE;
                }
                pesos[d][c] = w;
            }
            pesos[d][CARACTERISTICAS - 1] = 0;
        }
        seccionesObservadas++;
    }

    public int getSeccionesObservadas() {
        return seccionesObservadas;
    }

    public double getReferencia() {
        return referencia;
    }

    /** Resumen legible, para diagnostico. */
    public String estado() {
        return String.format("secciones %d | referencia %.2f | pesos energia %.2f/%.2f/%.2f",
                seccionesObservadas, referencia,
                pesos[MODULAR][0], pesos[RECORDAR][0], pesos[INTENSIDAD][0]);
    }

    private static double acotar(double v) {
        if (v < 0) {
            return 0;
        }
        return v > 1 ? 1 : v;
    }
}
