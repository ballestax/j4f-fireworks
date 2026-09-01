package herramientas;

import java.util.Random;

/**
 * Generador del corpus de entrenamiento (destilacion del motor actual).
 *
 * Parte de las mismas reglas que Musica.generarMotivo() pero sesga el corpus
 * hacia frases musicalmente mejores y las condiciona al vector de contexto de
 * 72 dimensiones, para que la red aprenda a usar el condicionamiento en vez
 * de ignorarlo.
 *
 * Herramienta de desarrollo. No se empaqueta.
 */
public final class Corpus {

    // --- Topologia de fichas, igual que RedImprovisador ---
    public static final int GRADO_MIN = -3;
    public static final int GRADO_MAX = 9;
    public static final int GRADOS = 13;
    public static final int DURACIONES = 4;
    public static final int HUECOS = 3;
    public static final int VOCABULARIO = 157;
    public static final int FICHA_FIN = 156;
    public static final int CONTEXTO = 72;

    // --- Reglas del maestro (Musica.java) ---
    private static final int MOTIVO_MIN_NOTAS = 3;
    private static final int MOTIVO_MAX_NOTAS = 6;
    private static final int[] MOTIVO_DURACIONES = {1, 1, 2, 2, 3, 4};
    private static final double PROB_GRADO_CONJUNTO = 0.72;
    private static final double PROB_HUECO_EXTRA = 0.30;

    /** Seis modos, intervalos en semitonos sobre la tonica. */
    private static final int[][] MODOS = {
        {0, 2, 4, 5, 7, 9, 11}, // jonico
        {0, 2, 3, 5, 7, 9, 10}, // dorico
        {0, 1, 3, 5, 7, 8, 10}, // frigio
        {0, 2, 4, 6, 7, 9, 11}, // lidio
        {0, 2, 4, 5, 7, 9, 10}, // mixolidio
        {0, 2, 3, 5, 7, 8, 10}  // eolio
    };

    /** Ocho calidades de acorde. */
    private static final int[][] ACORDES = {
        {0, 4, 7}, {0, 3, 7}, {0, 3, 6}, {0, 4, 8},
        {0, 4, 7, 11}, {0, 3, 7, 10}, {0, 4, 7, 10}, {0, 5, 7}
    };

    /** Una frase lista para entrenar. */
    public static final class Frase {

        public float[] contexto;
        public int[] fichas;   // tokens de nota mas FICHA_FIN al final
        public int[] grados;
        public int[] duraciones;
        public int[] huecosExtra;
    }

    private Corpus() {
    }

    public static int ficha(int grado, int duracion, int huecoExtra) {
        int g = grado - GRADO_MIN;
        if (g < 0) {
            g = 0;
        } else if (g >= GRADOS) {
            g = GRADOS - 1;
        }
        int d = duracion - 1;
        if (d < 0) {
            d = 0;
        } else if (d >= DURACIONES) {
            d = DURACIONES - 1;
        }
        int h = huecoExtra;
        if (h < 0) {
            h = 0;
        } else if (h >= HUECOS) {
            h = HUECOS - 1;
        }
        return (g * DURACIONES + d) * HUECOS + h;
    }

    private static int modulo(int a, int m) {
        int r = a % m;
        return r < 0 ? r + m : r;
    }

    /** Clase de altura (0..11) del grado dentro de la escala dada. */
    private static int alturaDe(int grado, int[] escala) {
        return escala[modulo(grado, 7)] % 12;
    }

    private static boolean enMascara(int mascara, int altura) {
        return ((mascara >> altura) & 1) != 0;
    }

    private static int limitar(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------------
    // Generacion
    // ------------------------------------------------------------------
    public static Frase[] generar(int cuantas, long semilla) {
        Random rnd = new Random(semilla);
        Frase[] fr = new Frase[cuantas];
        for (int i = 0; i < cuantas; i++) {
            fr[i] = unaFraseAceptada(rnd);
        }
        return fr;
    }

    /** Muestreo por rechazo: pesa el corpus hacia las frases mejor puntuadas. */
    private static Frase unaFraseAceptada(Random rnd) {
        Frase mejor = null;
        double mejorNota = -1;
        for (int intento = 0; intento < 12; intento++) {
            Frase f = unaFrase(rnd);
            double nota = puntuar(f);
            if (nota > mejorNota) {
                mejorNota = nota;
                mejor = f;
            }
            if (rnd.nextDouble() < nota) {
                return f;
            }
        }
        return mejor;
    }

    private static Frase unaFrase(Random rnd) {
        // --- Campos del contexto ---
        int genero = rnd.nextInt(3);
        int seccion = rnd.nextInt(5);
        int calidad = rnd.nextInt(8);
        int modo = rnd.nextInt(6);
        int[] escala = MODOS[modo];

        int mascaraEscala = 0;
        for (int i = 0; i < escala.length; i++) {
            mascaraEscala |= 1 << (escala[i] % 12);
        }
        // La raiz del acorde suele ser un grado de la escala.
        int raiz = rnd.nextDouble() < 0.80
                ? escala[rnd.nextInt(escala.length)] % 12 : rnd.nextInt(12);
        int mascaraAcorde = 0;
        int[] acorde = ACORDES[calidad];
        for (int i = 0; i < acorde.length; i++) {
            mascaraAcorde |= 1 << ((raiz + acorde[i]) % 12);
        }

        int compas = rnd.nextInt(8);
        float densidad = rnd.nextFloat();
        float desvioRegistro = rnd.nextFloat() * 2f - 1f;
        float desvioVelocidad = rnd.nextFloat() * 2f - 1f;
        float reexposicion = rnd.nextInt(4) / 3f;
        int ultimaDireccion = rnd.nextInt(3) - 1;
        float energia = rnd.nextFloat();

        // --- Longitud, condicionada por la seccion ---
        int n = MOTIVO_MIN_NOTAS + rnd.nextInt(MOTIVO_MAX_NOTAS - MOTIVO_MIN_NOTAS + 1);
        int otra = MOTIVO_MIN_NOTAS + rnd.nextInt(MOTIVO_MAX_NOTAS - MOTIVO_MIN_NOTAS + 1);
        if (seccion == 3) {          // climax: frases mas largas
            n = Math.max(n, otra);
        } else if (seccion == 0) {   // entrada: frases mas cortas
            n = Math.min(n, otra);
        }

        // --- Primer grado: nota del acorde cerca del registro medio ---
        int primero = elegirDeAcorde(rnd, mascaraAcorde, escala,
                new int[]{0, 1, 2}, new int[]{-1, 0, 1, 2, 3});

        // --- Saltos, con las reglas del maestro ---
        int[] saltos = new int[Math.max(1, n - 1)];
        for (int i = 0; i < n - 1; i++) {
            int mag = rnd.nextDouble() < PROB_GRADO_CONJUNTO
                    ? 1 + rnd.nextInt(2) : 3 + rnd.nextInt(2);
            boolean negativo = rnd.nextDouble() < 0.45;
            if (i == 0 && ultimaDireccion != 0 && rnd.nextDouble() < 0.60) {
                negativo = ultimaDireccion > 0;   // compensa la direccion previa
            }
            saltos[i] = negativo ? -mag : mag;
        }
        // Mejora 1: nada de dos saltos grandes seguidos en el mismo sentido.
        for (int i = 1; i < n - 1; i++) {
            if (Math.abs(saltos[i]) >= 3 && Math.abs(saltos[i - 1]) >= 3
                    && (saltos[i] > 0) == (saltos[i - 1] > 0)) {
                saltos[i] = -saltos[i];
            }
        }
        // Mejora 2: pasos, con un solo salto expresivo.
        int grandes = 0;
        for (int i = 0; i < n - 1; i++) {
            if (Math.abs(saltos[i]) >= 3) {
                grandes++;
                if (grandes > 1 && rnd.nextDouble() < 0.70) {
                    int signo = saltos[i] > 0 ? 1 : -1;
                    saltos[i] = signo * (1 + rnd.nextInt(2));
                    grandes--;
                }
            }
        }

        int[] grados = new int[n];
        grados[0] = primero;
        for (int i = 1; i < n; i++) {
            grados[i] = limitar(grados[i - 1] + saltos[i - 1], GRADO_MIN, GRADO_MAX);
        }

        // Mejora 3: la frase resuelve, sobre nota del acorde y cerca del inicio.
        grados[n - 1] = resolver(rnd, grados[n - 1], primero, mascaraAcorde, escala);
        if (n >= 3 && grados[n - 1] == grados[n - 2]) {
            grados[n - 2] = limitar(grados[n - 2] + (rnd.nextBoolean() ? 1 : -1),
                    GRADO_MIN, GRADO_MAX);
        }

        // --- Ritmo, condicionado por densidad y posicion de compas ---
        int[] duraciones = new int[n];
        int[] huecos = new int[n];
        double probHueco = PROB_HUECO_EXTRA * (1.5 - 0.9 * densidad);
        if (probHueco < 0.08) {
            probHueco = 0.08;
        } else if (probHueco > 0.50) {
            probHueco = 0.50;
        }
        for (int i = 0; i < n; i++) {
            int d = MOTIVO_DURACIONES[rnd.nextInt(MOTIVO_DURACIONES.length)];
            int d2 = MOTIVO_DURACIONES[rnd.nextInt(MOTIVO_DURACIONES.length)];
            if (densidad > 0.60) {
                d = Math.min(d, d2);
            } else if (densidad < 0.35) {
                d = Math.max(d, d2);
            }
            if (i == 0 && compas == 0 && rnd.nextDouble() < 0.5) {
                d = Math.min(4, d + 1);     // apoyo en el primer tiempo
            }
            duraciones[i] = d;
            int he = 0;
            if (rnd.nextDouble() < probHueco) {
                he = 1 + rnd.nextInt(2);
            }
            huecos[i] = he;
        }

        Frase f = new Frase();
        f.grados = grados;
        f.duraciones = duraciones;
        f.huecosExtra = huecos;
        f.fichas = new int[n + 1];
        for (int i = 0; i < n; i++) {
            f.fichas[i] = ficha(grados[i], duraciones[i], huecos[i]);
        }
        f.fichas[n] = FICHA_FIN;
        f.contexto = armarContexto(genero, seccion, calidad, raiz, modo,
                mascaraAcorde, mascaraEscala, compas, densidad, desvioRegistro,
                desvioVelocidad, reexposicion, ultimaDireccion, energia);
        return f;
    }

    /** Elige un grado que sea nota del acorde entre los candidatos dados. */
    private static int elegirDeAcorde(Random rnd, int mascaraAcorde, int[] escala,
            int[] preferidos, int[] alternativos) {
        int[] validos = new int[preferidos.length];
        int k = 0;
        for (int i = 0; i < preferidos.length; i++) {
            if (enMascara(mascaraAcorde, alturaDe(preferidos[i], escala))) {
                validos[k++] = preferidos[i];
            }
        }
        if (k > 0) {
            return validos[rnd.nextInt(k)];
        }
        validos = new int[alternativos.length];
        k = 0;
        for (int i = 0; i < alternativos.length; i++) {
            if (enMascara(mascaraAcorde, alturaDe(alternativos[i], escala))) {
                validos[k++] = alternativos[i];
            }
        }
        if (k > 0) {
            return validos[rnd.nextInt(k)];
        }
        return preferidos[rnd.nextInt(preferidos.length)];
    }

    /** Lleva la ultima nota a un tono del acorde cercano al inicio. */
    private static int resolver(Random rnd, int actual, int primero,
            int mascaraAcorde, int[] escala) {
        int mejor = actual;
        int mejorCoste = Integer.MAX_VALUE;
        for (int g = GRADO_MIN; g <= GRADO_MAX; g++) {
            if (!enMascara(mascaraAcorde, alturaDe(g, escala))) {
                continue;
            }
            if (Math.abs(g - primero) > 4) {
                continue;
            }
            int coste = Math.abs(g - actual) * 2 + Math.abs(g - primero);
            if (coste < mejorCoste) {
                mejorCoste = coste;
                mejor = g;
            }
        }
        // Algo de variedad: a veces resuelve en la tercera o la quinta de arriba.
        if (rnd.nextDouble() < 0.15) {
            int alto = mejor + (rnd.nextBoolean() ? 2 : 4);
            if (alto <= GRADO_MAX && enMascara(mascaraAcorde, alturaDe(alto, escala))
                    && Math.abs(alto - primero) <= 4) {
                mejor = alto;
            }
        }
        return mejor;
    }

    /** Nota de calidad musical de la frase, en (0, 1]. */
    private static double puntuar(Frase f) {
        int[] g = f.grados;
        int n = g.length;
        double s = 0.50;
        int prim = g[0], ult = g[n - 1];
        if (Math.abs(ult - prim) <= 2) {
            s += 0.14;
        } else if (Math.abs(ult - prim) <= 4) {
            s += 0.05;
        }
        int grandes = 0, seguidosMismoSentido = 0, subidas = 0, bajadas = 0;
        int minG = g[0], maxG = g[0];
        for (int i = 1; i < n; i++) {
            int d = g[i] - g[i - 1];
            if (Math.abs(d) >= 3) {
                grandes++;
                if (i >= 2 && Math.abs(g[i - 1] - g[i - 2]) >= 3
                        && ((g[i - 1] - g[i - 2]) > 0) == (d > 0)) {
                    seguidosMismoSentido++;
                }
            }
            if (d > 0) {
                subidas++;
            } else if (d < 0) {
                bajadas++;
            }
            if (g[i] < minG) {
                minG = g[i];
            }
            if (g[i] > maxG) {
                maxG = g[i];
            }
        }
        if (grandes == 1) {
            s += 0.14;      // un salto expresivo entre pasos: lo que se busca
        } else if (grandes == 0) {
            s += 0.04;
        } else {
            s -= 0.09 * (grandes - 1);
        }
        s -= 0.25 * seguidosMismoSentido;
        int rango = maxG - minG;
        if (rango >= 3 && rango <= 8) {
            s += 0.09;
        } else {
            s -= 0.07;
        }
        if (subidas > 0 && bajadas > 0) {
            s += 0.07;      // hay cima o valle, no una rampa
        } else if (n >= 5) {
            s -= 0.10;
        }
        int repetidas = 0;
        for (int i = 1; i < n; i++) {
            if (g[i] == g[i - 1]) {
                repetidas++;
            }
        }
        s -= 0.08 * repetidas;
        if (s < 0.08) {
            s = 0.08;
        } else if (s > 1.0) {
            s = 1.0;
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Vector de contexto de 72 dimensiones
    // ------------------------------------------------------------------
    /**
     * Disposicion: genero 3 | seccion 5 | calidad 8 | raiz relativa 12 |
     * modo 6 | mascara de acorde 12 | mascara de escala 12 | compas 8 |
     * densidad, desvioRegistro, desvioVelocidad 3 |
     * reexposicion, ultimaDireccion 2 | energiaVisual 1 = 72
     */
    public static float[] armarContexto(int genero, int seccion, int calidad,
            int raizRelativa, int modo, int mascaraAcorde, int mascaraEscala,
            int compas, float densidad, float desvioRegistro, float desvioVelocidad,
            float reexposicion, int ultimaDireccion, float energiaVisual) {
        float[] c = new float[CONTEXTO];
        int p = 0;
        c[p + genero] = 1;
        p += 3;
        c[p + seccion] = 1;
        p += 5;
        c[p + calidad] = 1;
        p += 8;
        c[p + raizRelativa] = 1;
        p += 12;
        c[p + modo] = 1;
        p += 6;
        for (int i = 0; i < 12; i++) {
            c[p + i] = ((mascaraAcorde >> i) & 1);
        }
        p += 12;
        for (int i = 0; i < 12; i++) {
            c[p + i] = ((mascaraEscala >> i) & 1);
        }
        p += 12;
        c[p + compas] = 1;
        p += 8;
        c[p++] = densidad;
        c[p++] = desvioRegistro;
        c[p++] = desvioVelocidad;
        c[p++] = reexposicion;
        c[p++] = ultimaDireccion;
        c[p++] = energiaVisual;
        return c;
    }

    /**
     * Lee el contexto y dice si ese grado cae sobre una nota del acorde.
     * Sirve para comprobar que la red usa de verdad el condicionamiento.
     */
    public static boolean esNotaDeAcorde(float[] ctx, int grado) {
        int modo = 0;
        for (int i = 1; i < 6; i++) {
            if (ctx[28 + i] > ctx[28 + modo]) {
                modo = i;
            }
        }
        int altura = alturaDe(grado, MODOS[modo]);
        return ctx[34 + altura] > 0.5f;
    }

    /** Contexto de ejemplo, reproducible, para las pruebas doradas. */
    public static float[] contextoDeMuestra(int indice) {
        Random r = new Random(9000 + indice);
        int modo = r.nextInt(6);
        int[] escala = MODOS[modo];
        int mascaraEscala = 0;
        for (int i = 0; i < escala.length; i++) {
            mascaraEscala |= 1 << (escala[i] % 12);
        }
        int raiz = escala[r.nextInt(escala.length)] % 12;
        int calidad = r.nextInt(8);
        int mascaraAcorde = 0;
        int[] ac = ACORDES[calidad];
        for (int i = 0; i < ac.length; i++) {
            mascaraAcorde |= 1 << ((raiz + ac[i]) % 12);
        }
        return armarContexto(r.nextInt(3), r.nextInt(5), calidad, raiz, modo,
                mascaraAcorde, mascaraEscala, r.nextInt(8), r.nextFloat(),
                r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1,
                r.nextInt(4) / 3f, r.nextInt(3) - 1, r.nextFloat());
    }
}
