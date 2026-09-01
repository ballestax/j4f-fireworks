package j4f.sonido;

/**
 * Saturacion suave y anchura estereo del bus principal.
 *
 * Las dos cosas van juntas porque son el ultimo paso antes del limitador y
 * comparten el mismo recorrido por la mezcla.
 *
 * La saturacion no esta para distorsionar sino para pegar la mezcla: redondea
 * los picos antes de que llegue el limitador, de modo que este tenga que
 * actuar menos y no se le oiga trabajar. La curva es una racional en vez de
 * una tangente hiperbolica: mismo caracter y sin llamar a una trascendente
 * por muestra.
 *
 * La anchura trabaja en medio y lados. Ensanchar de mas suena espectacular en
 * auriculares y se desmorona al sumar a mono, asi que el valor por defecto es
 * contenido.
 *
 * @author ballestas
 */
public final class Saturacion {

    private float empuje = 1.35f;
    private float compensacion = 1f;
    private float anchura = 1.25f;

    public Saturacion() {
        recalcular();
    }

    /**
     * @param empuje  1 es transparente, 2 ya se nota. Por encima de 3 ensucia.
     * @param anchura 1 deja la imagen como esta, 1,5 la abre bastante.
     */
    public void ajustar(double empuje, double anchura) {
        this.empuje = (float) (empuje < 1 ? 1 : (empuje > 4 ? 4 : empuje));
        this.anchura = (float) (anchura < 0 ? 0 : (anchura > 2 ? 2 : anchura));
        recalcular();
    }

    /** Devuelve la ganancia que restituye el nivel perdido al saturar. */
    private void recalcular() {
        float prueba = 0.7f;
        float salida = curva(prueba * empuje);
        compensacion = salida > 0.0001f ? prueba / salida : 1f;
    }

    /**
     * Curva suave impar, monotona y acotada a mas menos uno.
     * x / (1 + |x|) es la mas barata que suena bien.
     */
    private static float curva(float x) {
        float a = x < 0 ? -x : x;
        return x / (1f + a);
    }

    /** Procesa en el sitio. Sin asignaciones. */
    public void procesar(float[] izq, float[] der, int n) {
        float e = empuje;
        float c = compensacion;
        float w = anchura;
        for (int i = 0; i < n; i++) {
            float l = izq[i];
            float r = der[i];

            // Medio y lados. El medio se deja intacto para que el bajo y la
            // percusion no se despeguen del centro al ensanchar.
            float medio = (l + r) * 0.5f;
            float lados = (l - r) * 0.5f * w;

            l = medio + lados;
            r = medio - lados;

            izq[i] = curva(l * e) * c;
            der[i] = curva(r * e) * c;
        }
    }
}
