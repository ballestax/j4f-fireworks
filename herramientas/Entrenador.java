package herramientas;

import j4f.red.RedImprovisador;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Entrena la red improvisadora y exporta src/j4f/red/pesos.bin.
 *
 * Solo herramienta de desarrollo: vive fuera de src/ y nunca entra en el jar.
 *
 * Uso: java -cp build/classes;herramientas/salida herramientas.Entrenador
 *      [frases] [epocas]
 */
public final class Entrenador {

    private static final int LOTE = 96;
    private static final double LR_INICIAL = 0.004;
    private static final double LR_FINAL = 0.0004;
    private static final double RECORTE = 5.0;
    private static final double DECAIMIENTO = 1e-5;
    /** Ultimas epocas con cuantizacion simulada, para que el int8 no duela. */
    private static final int EPOCAS_CUANTIZADAS = 3;

    private static final String DESTINO = "src/j4f/red/pesos.bin";
    private static final String COPIA_CLASES = "build/classes/j4f/red/pesos.bin";

    public static void main(String[] args) throws Exception {
        int nFrases = args.length > 0 ? Integer.parseInt(args[0]) : 30000;
        int epocas = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        File raiz = new File(".").getAbsoluteFile().getParentFile();

        System.out.println("== Entrenador de la red improvisadora ==");
        System.out.println("raiz            : " + raiz);
        System.out.println("frases          : " + nFrases);
        System.out.println("epocas          : " + epocas);

        Modelo modelo = new Modelo(20260901L);
        System.out.println("parametros      : " + modelo.parametros());

        // --- Comprobacion numerica del gradiente ---
        comprobarGradiente(modelo);

        // --- Corpus de muestra, solo para estadisticas y pruebas ---
        long t0 = System.currentTimeMillis();
        Corpus.Frase[] corpus = Corpus.generar(nFrases, 12345L);
        System.out.println("corpus generado : " + corpus.length + " frases en "
                + (System.currentTimeMillis() - t0) + " ms");
        estadisticasCorpus(corpus);

        // --- Entrenamiento sobre corpus fresco en cada epoca ---
        entrenar(modelo, nFrases, epocas);

        // --- Evaluacion final ---
        Corpus.Frase[] validacion = Corpus.generar(4000, 987654L);
        double entFlot = evaluar(modelo, validacion, false);
        double entInt8 = evaluar(modelo, validacion, true);
        double entrena = evaluar(modelo, java.util.Arrays.copyOf(corpus,
                Math.min(4000, corpus.length)), false);
        System.out.println();
        System.out.println(String.format(
                "perdida final entrenamiento : %.4f  (perplejidad %.2f)",
                entrena, Math.exp(entrena)));
        System.out.println(String.format(
                "perdida validacion flotante : %.4f  (perplejidad %.2f)",
                entFlot, Math.exp(entFlot)));
        System.out.println(String.format(
                "perdida validacion int8     : %.4f  (perplejidad %.2f)",
                entInt8, Math.exp(entInt8)));

        // --- Exportacion ---
        File destino = new File(raiz, DESTINO);
        long bytes = modelo.exportar(destino);
        System.out.println();
        System.out.println("pesos.bin       : " + destino.getPath() + "  "
                + bytes + " bytes (" + String.format("%.1f", bytes / 1024.0) + " KB)");
        File copia = new File(raiz, COPIA_CLASES);
        copiar(destino, copia);
        System.out.println("copia de prueba : " + copia.getPath());

        // --- Verificacion ---
        RedImprovisador red = RedImprovisador.crear();
        if (red == null) {
            System.out.println("ERROR: RedImprovisador.crear() devolvio null.");
            System.out.println("       El recurso /j4f/red/pesos.bin no esta en el classpath.");
            return;
        }
        pruebaDorada(modelo, red, corpus);
        muestrear(red, 20);
    }

    // ------------------------------------------------------------------
    // Entrenamiento
    // ------------------------------------------------------------------
    private static void entrenar(final Modelo modelo, int nFrases, int epocas)
            throws Exception {
        int hilos = Math.min(16, Runtime.getRuntime().availableProcessors());
        System.out.println("hilos           : " + hilos);
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        final Modelo.Estado[] estados = new Modelo.Estado[hilos];
        for (int i = 0; i < hilos; i++) {
            estados[i] = new Modelo.Estado();
        }
        int lotesTotales = epocas * ((nFrases + LOTE - 1) / LOTE);
        int loteGlobal = 0;

        System.out.println();
        System.out.println("Cada epoca ve frases nuevas, asi que la perdida de la");
        System.out.println("tabla ya es perdida sobre datos no vistos.");
        System.out.println();
        System.out.println("epoca  perdida  perplejidad   lr      norma_grad  seg");
        for (int ep = 0; ep < epocas; ep++) {
            final Corpus.Frase[] corpus = Corpus.generar(nFrases, 1000L + ep * 7919L);
            boolean cuantizada = ep >= epocas - EPOCAS_CUANTIZADAS;
            long te = System.currentTimeMillis();
            double sumaPerdida = 0;
            long sumaFichas = 0;
            double ultimaNorma = 0;

            for (int inicio = 0; inicio < corpus.length; inicio += LOTE) {
                final int fin = Math.min(inicio + LOTE, corpus.length);
                final int desde = inicio;
                modelo.copiarPesos(cuantizada);
                final float[][] w = modelo.w;
                List<Callable<Object>> tareas = new ArrayList<Callable<Object>>();
                int total = fin - desde;
                int porHilo = (total + hilos - 1) / hilos;
                for (int h = 0; h < hilos; h++) {
                    final int a = desde + h * porHilo;
                    final int b = Math.min(fin, a + porHilo);
                    final Modelo.Estado e = estados[h];
                    e.limpiarGradientes();
                    if (a >= b) {
                        continue;
                    }
                    tareas.add(new Callable<Object>() {
                        public Object call() {
                            for (int k = a; k < b; k++) {
                                Corpus.Frase f = corpus[k];
                                modelo.fraseAdelanteAtras(w, f.fichas, f.contexto, e);
                            }
                            return null;
                        }
                    });
                }
                List<Future<Object>> fs = pool.invokeAll(tareas);
                for (int i = 0; i < fs.size(); i++) {
                    fs.get(i).get();
                }
                // Reduccion de gradientes sobre el estado 0.
                float[][] g = estados[0].gr;
                int fichasLote = estados[0].fichasVistas;
                double perdidaLote = estados[0].perdida;
                for (int h = 1; h < hilos; h++) {
                    if (estados[h].fichasVistas == 0) {
                        continue;
                    }
                    fichasLote += estados[h].fichasVistas;
                    perdidaLote += estados[h].perdida;
                    float[][] gh = estados[h].gr;
                    for (int t = 0; t < Modelo.TENSORES; t++) {
                        float[] a = g[t], b = gh[t];
                        for (int i = 0; i < a.length; i++) {
                            a[i] += b[i];
                        }
                    }
                }
                double prog = lotesTotales <= 1 ? 1.0 : (double) loteGlobal / (lotesTotales - 1);
                double lr = LR_FINAL + 0.5 * (LR_INICIAL - LR_FINAL)
                        * (1 + Math.cos(Math.PI * prog));
                ultimaNorma = modelo.actualizar(g, fichasLote, lr, RECORTE, DECAIMIENTO);
                sumaPerdida += perdidaLote;
                sumaFichas += fichasLote;
                loteGlobal++;
            }
            double media = sumaPerdida / sumaFichas;
            double lrAhora = LR_FINAL + 0.5 * (LR_INICIAL - LR_FINAL)
                    * (1 + Math.cos(Math.PI * ((double) loteGlobal / (lotesTotales - 1))));
            System.out.println(String.format("%4d   %7.4f  %9.3f   %.5f  %8.3f  %5.1f%s",
                    ep + 1, media, Math.exp(media), lrAhora, ultimaNorma,
                    (System.currentTimeMillis() - te) / 1000.0,
                    cuantizada ? "   (int8 simulado)" : ""));
        }
        pool.shutdown();
        // Deja los pesos de trabajo sincronizados con los maestros.
        modelo.copiarPesos(false);
    }

    // ------------------------------------------------------------------
    // Comprobacion numerica del gradiente
    // ------------------------------------------------------------------
    private static double perdidaDe(Modelo mo, float[][] w, int[] fichas,
            float[] ctx, Modelo.Estado e) {
        int T = mo.adelante(w, fichas, ctx, e);
        double total = 0;
        for (int t = 0; t < T; t++) {
            float[] lg = e.lg[t];
            double max = lg[0];
            for (int i = 1; i < Modelo.V; i++) {
                if (lg[i] > max) {
                    max = lg[i];
                }
            }
            double suma = 0;
            for (int i = 0; i < Modelo.V; i++) {
                suma += Math.exp(lg[i] - max);
            }
            total += -(lg[fichas[t]] - max - Math.log(suma));
        }
        return total;
    }

    /** Entropia cruzada media por ficha sobre un conjunto de frases. */
    private static double evaluar(Modelo mo, Corpus.Frase[] frases, boolean cuantizada) {
        mo.copiarPesos(cuantizada);
        Modelo.Estado e = new Modelo.Estado();
        double total = 0;
        long fichas = 0;
        for (int i = 0; i < frases.length; i++) {
            total += perdidaDe(mo, mo.w, frases[i].fichas, frases[i].contexto, e);
            fichas += frases[i].fichas.length;
        }
        mo.copiarPesos(false);
        return total / fichas;
    }

    private static double perdidaLote(Modelo mo, Corpus.Frase[] f, Modelo.Estado e) {
        double total = 0;
        for (int i = 0; i < f.length; i++) {
            total += perdidaDe(mo, mo.w, f[i].fichas, f[i].contexto, e);
        }
        return total;
    }

    /**
     * Comprueba la propagacion hacia atras contra diferencias finitas.
     *
     * En flotante de 32 bits una diferencia finita por parametro suelto es
     * puro ruido, asi que se comprueba la derivada direccional a lo largo de
     * la propia direccion del gradiente (senal grande, ruido pequeno) y ademas
     * los parametros de mayor gradiente uno a uno.
     */
    private static void comprobarGradiente(Modelo mo) {
        Corpus.Frase[] f = Corpus.generar(24, 4242L);
        Modelo.Estado e = new Modelo.Estado();
        e.limpiarGradientes();
        mo.copiarPesos(false);
        for (int i = 0; i < f.length; i++) {
            mo.fraseAdelanteAtras(mo.w, f[i].fichas, f[i].contexto, e);
        }

        // --- (a) derivada direccional a lo largo del gradiente ---
        double norma2 = 0;
        for (int t = 0; t < Modelo.TENSORES; t++) {
            for (int i = 0; i < e.gr[t].length; i++) {
                norma2 += (double) e.gr[t][i] * e.gr[t][i];
            }
        }
        double norma = Math.sqrt(norma2);
        double eps = 1e-3;
        float[][] copia = new float[Modelo.TENSORES][];
        for (int t = 0; t < Modelo.TENSORES; t++) {
            copia[t] = mo.p[t].clone();
        }
        for (int signo = 0; signo < 2; signo++) {
            double paso = (signo == 0 ? eps : -eps) / norma;
            for (int t = 0; t < Modelo.TENSORES; t++) {
                for (int i = 0; i < mo.p[t].length; i++) {
                    mo.p[t][i] = (float) (copia[t][i] + paso * e.gr[t][i]);
                }
            }
            mo.copiarPesos(false);
            double l = perdidaLote(mo, f, e);
            if (signo == 0) {
                direccionalMas = l;
            } else {
                direccionalMenos = l;
            }
        }
        for (int t = 0; t < Modelo.TENSORES; t++) {
            System.arraycopy(copia[t], 0, mo.p[t], 0, copia[t].length);
        }
        mo.copiarPesos(false);
        double numDir = (direccionalMas - direccionalMenos) / (2 * eps);
        double relDir = Math.abs(numDir - norma) / Math.max(1e-9, norma);

        // --- (b) parametros sueltos, los de mayor gradiente ---
        int[] mejorT = new int[10];
        int[] mejorI = new int[10];
        double[] mejorG = new double[10];
        for (int t = 0; t < Modelo.TENSORES; t++) {
            for (int i = 0; i < e.gr[t].length; i++) {
                double a = Math.abs(e.gr[t][i]);
                if (a > mejorG[9]) {
                    int k = 9;
                    while (k > 0 && mejorG[k - 1] < a) {
                        mejorG[k] = mejorG[k - 1];
                        mejorT[k] = mejorT[k - 1];
                        mejorI[k] = mejorI[k - 1];
                        k--;
                    }
                    mejorG[k] = a;
                    mejorT[k] = t;
                    mejorI[k] = i;
                }
            }
        }
        double peorRel = 0;
        double h = 3e-3;
        for (int k = 0; k < 10; k++) {
            int t = mejorT[k], i = mejorI[k];
            float orig = mo.p[t][i];
            mo.p[t][i] = (float) (orig + h);
            mo.copiarPesos(false);
            double mas = perdidaLote(mo, f, e);
            mo.p[t][i] = (float) (orig - h);
            mo.copiarPesos(false);
            double menos = perdidaLote(mo, f, e);
            mo.p[t][i] = orig;
            mo.copiarPesos(false);
            double num = (mas - menos) / (2 * h);
            double ana = e.gr[t][i];
            double rel = Math.abs(num - ana) / Math.max(1e-6, Math.abs(ana));
            if (rel > peorRel) {
                peorRel = rel;
            }
        }
        boolean ok = relDir < 5e-3 && peorRel < 5e-2;
        System.out.println(String.format(
                "gradiente       : direccional %.3e vs %.3e (rel %.2e); "
                + "10 mayores, peor rel %.2e  %s",
                numDir, norma, relDir, peorRel, ok ? "(OK)" : "(REVISAR)"));
    }

    private static double direccionalMas;
    private static double direccionalMenos;

    // ------------------------------------------------------------------
    // Prueba dorada: entrenador en flotante contra RedImprovisador en int8
    // ------------------------------------------------------------------
    private static void pruebaDorada(Modelo mo, RedImprovisador red,
            Corpus.Frase[] corpus) {
        int casos = 24;
        System.out.println();
        System.out.println("== Prueba dorada (logits del entrenador vs RedImprovisador) ==");

        for (int ronda = 0; ronda < 2; ronda++) {
            boolean simulandoInt8 = (ronda == 1);
            mo.copiarPesos(simulandoInt8);
            Modelo.Estado e = new Modelo.Estado();
            double maxAbs = 0, sumaAbs = 0, peorEmpate = -1;
            long comparados = 0, argmaxIgual = 0, vectores = 0;
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;

            for (int c = 0; c < casos; c++) {
                Corpus.Frase f = corpus[c * 977 % corpus.length];
                float[] ctx = f.contexto;
                int[] prefijo = f.fichas;
                int T = mo.adelante(mo.w, prefijo, ctx, e);
                red.reiniciar();
                for (int t = 0; t < T; t++) {
                    int ficha = (t == 0) ? -1 : prefijo[t - 1];
                    float[] lr = red.paso(ficha, ctx);
                    float[] lm = e.lg[t];
                    int amR = 0, amM = 0;
                    for (int i = 0; i < Modelo.V; i++) {
                        double a = lm[i], b = lr[i];
                        double d = Math.abs(a - b);
                        if (d > maxAbs) {
                            maxAbs = d;
                        }
                        sumaAbs += d;
                        sx += a;
                        sy += b;
                        sxx += a * a;
                        syy += b * b;
                        sxy += a * b;
                        comparados++;
                        if (lr[i] > lr[amR]) {
                            amR = i;
                        }
                        if (lm[i] > lm[amM]) {
                            amM = i;
                        }
                    }
                    vectores++;
                    if (amR == amM) {
                        argmaxIgual++;
                    } else {
                        // Un desacuerdo solo importa si no es un empate tecnico.
                        double hueco = Math.abs(lr[amR] - lr[amM]);
                        if (peorEmpate < 0 || hueco > peorEmpate) {
                            peorEmpate = hueco;
                        }
                    }
                }
            }
            double n = comparados;
            double cov = sxy / n - (sx / n) * (sy / n);
            double vx = sxx / n - (sx / n) * (sx / n);
            double vy = syy / n - (sy / n) * (sy / n);
            double corr = cov / Math.sqrt(vx * vy);
            System.out.println("  " + (simulandoInt8
                    ? "int8 simulado en el entrenador vs fichero cargado:"
                    : "flotante del entrenador vs fichero cargado (coste real del int8):"));
            System.out.println(String.format(
                    "    max|dif| = %.6f   media|dif| = %.6f   corr = %.8f",
                    maxAbs, sumaAbs / n, corr));
            System.out.println(String.format(
                    "    argmax igual = %d/%d   logits comparados = %d%s",
                    argmaxIgual, vectores, comparados,
                    peorEmpate < 0 ? ""
                            : String.format("   (mayor hueco top-1 en desacuerdo: %.6f)",
                                    peorEmpate)));
        }
        mo.copiarPesos(false);
    }

    // ------------------------------------------------------------------
    // Muestreo desde la red cargada
    // ------------------------------------------------------------------
    private static void muestrear(RedImprovisador red, int cuantas) {
        System.out.println();
        System.out.println("== Frases muestreadas desde RedImprovisador (T=0.85) ==");
        Random rnd = new Random(2024);
        float[] probs = new float[RedImprovisador.VOCABULARIO];
        int terminadas = 0, sumaLargo = 0, fueraDeRango = 0;
        for (int c = 0; c < cuantas; c++) {
            float[] ctx = Corpus.contextoDeMuestra(c);
            red.reiniciar();
            StringBuilder sb = new StringBuilder();
            int ficha = -1;
            int n = 0;
            boolean fin = false;
            for (int paso = 0; paso < 16; paso++) {
                float[] lg = red.paso(ficha, ctx);
                int elegida = muestrearFicha(lg, probs, rnd, 0.85);
                if (elegida == RedImprovisador.FICHA_FIN) {
                    fin = true;
                    break;
                }
                int g = RedImprovisador.gradoDe(elegida);
                int d = RedImprovisador.duracionDe(elegida);
                int h = RedImprovisador.huecoExtraDe(elegida);
                if (g < RedImprovisador.GRADO_MIN || g > RedImprovisador.GRADO_MAX) {
                    fueraDeRango++;
                }
                sb.append('(').append(g).append(',').append(d).append(',').append(h).append(") ");
                ficha = elegida;
                n++;
            }
            if (fin) {
                terminadas++;
            }
            sumaLargo += n;
            System.out.println(String.format("  %2d  n=%d %-4s %s", c + 1, n,
                    fin ? "FIN" : "corte", sb.toString()));
        }
        System.out.println(String.format(
                "  terminan en FICHA_FIN: %d/%d   largo medio: %.2f   grados fuera de rango: %d",
                terminadas, cuantas, sumaLargo / (double) cuantas, fueraDeRango));
        condicionamiento(red, 600);
    }

    /**
     * Comprueba que la red usa el contexto: si lo ignorase, la primera y la
     * ultima nota caerian sobre el acorde solo por azar.
     */
    private static void condicionamiento(RedImprovisador red, int cuantas) {
        Random rnd = new Random(555);
        float[] probs = new float[RedImprovisador.VOCABULARIO];
        int conPrimera = 0, conUltima = 0, validas = 0, azarPrimera = 0;
        int sumaLargo = 0, terminadas = 0;
        for (int c = 0; c < cuantas; c++) {
            float[] ctx = Corpus.contextoDeMuestra(c);
            red.reiniciar();
            int ficha = -1, n = 0, primera = 0, ultima = 0;
            boolean fin = false;
            for (int paso = 0; paso < 16; paso++) {
                int elegida = muestrearFicha(red.paso(ficha, ctx), probs, rnd, 0.85);
                if (elegida == RedImprovisador.FICHA_FIN) {
                    fin = true;
                    break;
                }
                int g = RedImprovisador.gradoDe(elegida);
                if (n == 0) {
                    primera = g;
                }
                ultima = g;
                ficha = elegida;
                n++;
            }
            if (n == 0) {
                continue;
            }
            if (fin) {
                terminadas++;
            }
            sumaLargo += n;
            validas++;
            if (Corpus.esNotaDeAcorde(ctx, primera)) {
                conPrimera++;
            }
            if (Corpus.esNotaDeAcorde(ctx, ultima)) {
                conUltima++;
            }
            // Linea base: el mismo grado juzgado con el acorde de otro contexto.
            if (Corpus.esNotaDeAcorde(Corpus.contextoDeMuestra(c + 1), primera)) {
                azarPrimera++;
            }
        }
        System.out.println();
        System.out.println("== Uso del contexto (" + validas + " frases muestreadas) ==");
        System.out.println(String.format(
                "  primera nota sobre el acorde : %.1f%%   (linea base con otro acorde: %.1f%%)",
                100.0 * conPrimera / validas, 100.0 * azarPrimera / validas));
        System.out.println(String.format(
                "  ultima nota sobre el acorde  : %.1f%%", 100.0 * conUltima / validas));
        System.out.println(String.format(
                "  terminan en FICHA_FIN        : %.1f%%   largo medio %.2f",
                100.0 * terminadas / validas, sumaLargo / (double) validas));
    }

    private static int muestrearFicha(float[] logits, float[] probs, Random rnd, double temp) {
        int n = logits.length;
        float max = logits[0];
        for (int i = 1; i < n; i++) {
            if (logits[i] > max) {
                max = logits[i];
            }
        }
        double suma = 0;
        for (int i = 0; i < n; i++) {
            double ex = Math.exp((logits[i] - max) / temp);
            probs[i] = (float) ex;
            suma += ex;
        }
        double u = rnd.nextDouble() * suma;
        double acum = 0;
        for (int i = 0; i < n; i++) {
            acum += probs[i];
            if (acum >= u) {
                return i;
            }
        }
        return n - 1;
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------
    private static void estadisticasCorpus(Corpus.Frase[] c) {
        long notas = 0;
        int[] histLargo = new int[10];
        int resuelven = 0, saltosGrandes = 0, transiciones = 0;
        for (int i = 0; i < c.length; i++) {
            int n = c[i].grados.length;
            notas += n;
            if (n < histLargo.length) {
                histLargo[n]++;
            }
            if (Math.abs(c[i].grados[n - 1] - c[i].grados[0]) <= 2) {
                resuelven++;
            }
            for (int k = 1; k < n; k++) {
                transiciones++;
                if (Math.abs(c[i].grados[k] - c[i].grados[k - 1]) >= 3) {
                    saltosGrandes++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i <= 6; i++) {
            sb.append(i).append(":").append(histLargo[i]).append("  ");
        }
        System.out.println("  largos        : " + sb);
        System.out.println(String.format(
                "  notas=%d  resuelven cerca del inicio=%.1f%%  saltos grandes=%.1f%%",
                notas, 100.0 * resuelven / c.length, 100.0 * saltosGrandes / transiciones));
    }

    private static void copiar(File origen, File destino) throws IOException {
        File padre = destino.getParentFile();
        if (padre != null && !padre.exists()) {
            padre.mkdirs();
        }
        FileInputStream in = new FileInputStream(origen);
        FileOutputStream out = new FileOutputStream(destino);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
    }
}
