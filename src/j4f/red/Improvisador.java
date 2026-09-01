package j4f.red;

/**
 * Genera frases con la red, filtradas por las guardas y por un veto.
 *
 * El veto es la propiedad que impide que un mal momento de la red arruine una
 * seccion entera: se puntua la frase recien generada con heuristicas baratas
 * y, si no llega al umbral, se vuelve a intentar; agotados los intentos, quien
 * llama se queda con el generador de siempre. La red nunca puede dejar la
 * musica peor que antes de existir.
 *
 * Esta clase no depende de Musica: recibe el contexto ya montado y devuelve
 * arrays. Asi se puede probar sola.
 *
 * @author ballestas
 */
public final class Improvisador {

    /** Intentos antes de rendirse y dejar que decida el generador de reglas. */
    private static final int INTENTOS = 3;
    /** Cuanto se deja divagar al muestreo. Bajo suena repetitivo, alto disperso. */
    private static final double TEMPERATURA = 0.92;
    /** Puntuacion minima aceptable de una frase. */
    private static final double UMBRAL = 0.34;

    private final RedImprovisador red;
    private final java.util.Random azar;

    private final int[] grados = new int[GuardasArmonicas.MAX_NOTAS];
    private final int[] duraciones = new int[GuardasArmonicas.MAX_NOTAS];
    private final int[] huecos = new int[GuardasArmonicas.MAX_NOTAS];
    private final float[] probabilidades = new float[RedImprovisador.VOCABULARIO];
    private int longitud;

    private Improvisador(RedImprovisador red) {
        this.red = red;
        // Generador propio: no se toca j4f.Azar, que es un monitor compartido.
        this.azar = new java.util.Random();
    }

    /** @return el improvisador, o null si no hay pesos. */
    public static Improvisador crear() {
        RedImprovisador r = RedImprovisador.crear();
        return r == null ? null : new Improvisador(r);
    }

    public int getLongitud() {
        return longitud;
    }

    public int[] getGrados() {
        return grados;
    }

    public int[] getDuraciones() {
        return duraciones;
    }

    public int[] getHuecos() {
        return huecos;
    }

    /**
     * Genera una frase.
     *
     * @param contexto        vector de condicionamiento, largo CONTEXTO.
     * @param mascaraAcorde   12 bits con las notas del acorde.
     * @param semitonoDeGrado conversion de grado de escala a semitonos.
     * @return true si hay frase utilizable; false para usar el generador de reglas.
     */
    public boolean generar(float[] contexto, int mascaraAcorde, int[] semitonoDeGrado) {
        if (contexto == null || contexto.length != RedImprovisador.CONTEXTO) {
            return false;
        }
        double mejorPunto = -1;
        int mejorLargo = 0;
        int[] mg = null;
        for (int intento = 0; intento < INTENTOS; intento++) {
            if (!intentar(contexto, mascaraAcorde, semitonoDeGrado)) {
                continue;
            }
            double p = puntuar(mascaraAcorde, semitonoDeGrado);
            if (p >= UMBRAL) {
                return true;
            }
            // Se guarda la mejor por si ninguna pasa el corte.
            if (p > mejorPunto) {
                mejorPunto = p;
                mejorLargo = longitud;
                mg = grados.clone();
            }
        }
        if (mejorPunto > 0 && mg != null) {
            // Ninguna convence: se devuelve la menos mala solo si es decente.
            if (mejorPunto >= UMBRAL * 0.75) {
                System.arraycopy(mg, 0, grados, 0, mejorLargo);
                longitud = mejorLargo;
                return true;
            }
        }
        return false;
    }

    private boolean intentar(float[] contexto, int mascaraAcorde, int[] semitonoDeGrado) {
        red.reiniciar();
        longitud = 0;
        int ficha = -1;
        int gradoAnterior = Integer.MIN_VALUE;
        int saltoAnterior = 0;
        int unidad = 0;

        for (int paso = 0; paso < GuardasArmonicas.MAX_NOTAS; paso++) {
            float[] logits = red.paso(ficha, contexto);
            // Parte fuerte: las unidades pares del compas.
            boolean fuerte = (unidad % 2) == 0;
            GuardasArmonicas.aplicar(logits, mascaraAcorde, gradoAnterior, saltoAnterior,
                    longitud, fuerte, semitonoDeGrado);
            int elegida = muestrear(logits);
            if (elegida < 0) {
                return false;
            }
            if (elegida == RedImprovisador.FICHA_FIN) {
                break;
            }
            int grado = RedImprovisador.gradoDe(elegida);
            int duracion = RedImprovisador.duracionDe(elegida);
            int extra = RedImprovisador.huecoExtraDe(elegida);
            grados[longitud] = grado;
            duraciones[longitud] = duracion;
            huecos[longitud] = duracion + extra;
            if (gradoAnterior != Integer.MIN_VALUE) {
                saltoAnterior = grado - gradoAnterior;
            }
            gradoAnterior = grado;
            unidad += huecos[longitud];
            longitud++;
            ficha = elegida;
        }
        return longitud >= GuardasArmonicas.MIN_NOTAS;
    }

    /** Muestreo con temperatura sobre los logits ya enmascarados. */
    private int muestrear(float[] logits) {
        float maximo = -Float.MAX_VALUE;
        for (int i = 0; i < logits.length; i++) {
            if (logits[i] > maximo) {
                maximo = logits[i];
            }
        }
        if (maximo <= -1e8f) {
            return -1;
        }
        double suma = 0;
        for (int i = 0; i < logits.length; i++) {
            double p = logits[i] <= -1e8f ? 0
                    : Math.exp((logits[i] - maximo) / TEMPERATURA);
            probabilidades[i] = (float) p;
            suma += p;
        }
        if (suma <= 0) {
            return -1;
        }
        double corte = azar.nextDouble() * suma;
        double acumulado = 0;
        for (int i = 0; i < logits.length; i++) {
            acumulado += probabilidades[i];
            if (acumulado >= corte) {
                return i;
            }
        }
        return logits.length - 1;
    }

    /**
     * Puntua la frase de cero a uno. Mira lo que de verdad distingue una
     * frase con intencion de una sucesion de notas: que no sea plana, que no
     * se dispare de ambito, que apoye en notas del acorde y que resuelva
     * cerca de donde empezo.
     */
    private double puntuar(int mascaraAcorde, int[] semitonoDeGrado) {
        if (longitud < GuardasArmonicas.MIN_NOTAS) {
            return 0;
        }
        int min = grados[0];
        int max = grados[0];
        int saltos = 0;
        int repetidas = 0;
        for (int i = 0; i < longitud; i++) {
            if (grados[i] < min) {
                min = grados[i];
            }
            if (grados[i] > max) {
                max = grados[i];
            }
            if (i > 0) {
                int d = grados[i] - grados[i - 1];
                if (d == 0) {
                    repetidas++;
                }
                int a = d < 0 ? -d : d;
                if (a >= 3) {
                    saltos++;
                }
            }
        }
        int ambito = max - min;
        // Ni una linea plana ni un zigzag de dos octavas.
        double pAmbito = ambito >= 2 && ambito <= 10 ? 1.0 : 0.25;
        // Algun salto da interes; todo saltos suena a arpegio de maquina.
        double pSaltos = 1.0 - Math.abs(saltos - (longitud * 0.28)) / Math.max(1.0, longitud);
        if (pSaltos < 0) {
            pSaltos = 0;
        }
        double pRepes = 1.0 - (double) repetidas / longitud;
        double pCierre = 0.5;
        if (mascaraAcorde != 0 && semitonoDeGrado != null) {
            int clase = claseDe(grados[longitud - 1], semitonoDeGrado);
            pCierre = ((mascaraAcorde >> clase) & 1) != 0 ? 1.0 : 0.3;
        }
        return 0.28 * pAmbito + 0.24 * pSaltos + 0.20 * pRepes + 0.28 * pCierre;
    }

    private static int claseDe(int grado, int[] semitonoDeGrado) {
        int n = semitonoDeGrado.length;
        if (n == 0) {
            return 0;
        }
        int oct = Math.floorDiv(grado, n);
        int idx = grado - oct * n;
        int semitonos = semitonoDeGrado[idx] + oct * 12;
        int clase = semitonos % 12;
        return clase < 0 ? clase + 12 : clase;
    }
}
