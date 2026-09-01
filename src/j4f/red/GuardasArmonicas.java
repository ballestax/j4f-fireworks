package j4f.red;

/**
 * Restricciones armonicas sobre lo que propone la red.
 *
 * Se enmascaran los logits ANTES de muestrear, en vez de corregir la nota
 * despues. La diferencia no es de estilo: corregir a posteriori subiendo un
 * semitono amontona varios grados distintos sobre la misma altura y deforma
 * la distribucion que la red aprendio. Enmascarando, la red elige entre lo
 * que si vale.
 *
 * La distincion importante es entre prohibir y penalizar. Se prohibe lo que
 * nunca es aceptable (salirse del ambito, saltos imposibles) y solo se
 * penaliza la nota ajena al acorde en parte fuerte, porque prohibirla se
 * cargaria las notas de paso, que son justo lo que da naturalidad.
 *
 * @author ballestas
 */
public final class GuardasArmonicas {

    /** Castigo, en logits, de la nota ajena al acorde en parte fuerte. */
    private static final float CASTIGO_AJENA = 2.0f;
    /** Castigo de encadenar dos saltos grandes en la misma direccion. */
    private static final float CASTIGO_DOBLE_SALTO = 1.6f;
    /** Salto maximo entre grados consecutivos. */
    private static final int SALTO_MAX = 7;
    private static final float PROHIBIDO = -1e9f;

    public static final int MIN_NOTAS = 3;
    public static final int MAX_NOTAS = 12;

    /**
     * Aplica las guardas sobre los logits, en el sitio.
     *
     * @param logits       vector del vocabulario, se modifica.
     * @param mascaraAcorde 12 bits: notas del acorde por clase de altura.
     * @param gradoAnterior grado de la nota previa, o Integer.MIN_VALUE si es la primera.
     * @param saltoAnterior salto que llevo hasta la nota previa.
     * @param notasPuestas  cuantas notas lleva ya la frase.
     * @param parteFuerte   si la nota va a caer en tiempo fuerte.
     * @param semitonoDeGrado tabla que convierte grado de escala a clase de altura.
     */
    public static void aplicar(float[] logits, int mascaraAcorde,
            int gradoAnterior, int saltoAnterior, int notasPuestas,
            boolean parteFuerte, int[] semitonoDeGrado) {

        for (int f = 0; f < RedImprovisador.FICHA_FIN; f++) {
            int grado = RedImprovisador.gradoDe(f);

            if (gradoAnterior != Integer.MIN_VALUE) {
                int salto = grado - gradoAnterior;
                int abs = salto < 0 ? -salto : salto;
                if (abs > SALTO_MAX) {
                    logits[f] = PROHIBIDO;
                    continue;
                }
                // Dos saltos grandes seguidos en la misma direccion dejan la
                // frase disparada y sin sitio al que volver.
                if (abs >= 3 && saltoAnterior != 0
                        && (salto > 0) == (saltoAnterior > 0)
                        && (saltoAnterior > 2 || saltoAnterior < -2)) {
                    logits[f] -= CASTIGO_DOBLE_SALTO;
                }
            }

            if (parteFuerte && mascaraAcorde != 0 && semitonoDeGrado != null) {
                int clase = claseDe(grado, semitonoDeGrado);
                if (((mascaraAcorde >> clase) & 1) == 0) {
                    logits[f] -= CASTIGO_AJENA;
                }
            }
        }

        // Longitud de la frase: ni un fragmento suelto ni un discurso sin fin.
        if (notasPuestas < MIN_NOTAS) {
            logits[RedImprovisador.FICHA_FIN] = PROHIBIDO;
        } else if (notasPuestas >= MAX_NOTAS) {
            for (int f = 0; f < RedImprovisador.FICHA_FIN; f++) {
                logits[f] = PROHIBIDO;
            }
        }
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

    private GuardasArmonicas() {
    }
}
