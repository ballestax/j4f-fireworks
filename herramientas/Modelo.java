package herramientas;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * La red GRU de dos capas, en flotante, con propagacion hacia atras en el
 * tiempo, Adam y exportacion a int8 con una escala por fila.
 *
 * Las ecuaciones son exactamente las de RedImprovisador.celda:
 *   z = sig(Wz x + Uz h + bz)
 *   r = sig(Wr x + Ur h + br)
 *   c = tanh(Wh x + Uh (r . h) + bh)
 *   h = (1 - z) . h + z . c
 *
 * Herramienta de desarrollo. No se empaqueta.
 */
public final class Modelo {

    public static final int V = 157;      // vocabulario
    public static final int E = 32;       // embebido
    public static final int C = 72;       // contexto
    public static final int H = 128;      // ocultas
    public static final int IN = E + C;   // 104, entrada de la capa 1
    public static final int MAXT = 16;    // pasos maximos por frase

    // Orden exacto de los tensores en el fichero.
    public static final String[] NOMBRES = {
        "emb",
        "g1_wz", "g1_wr", "g1_wh", "g1_uz", "g1_ur", "g1_uh",
        "g1_bz", "g1_br", "g1_bh",
        "g2_wz", "g2_wr", "g2_wh", "g2_uz", "g2_ur", "g2_uh",
        "g2_bz", "g2_br", "g2_bh",
        "sal_w", "sal_b"};

    public static final int[] FILAS = {
        V,
        H, H, H, H, H, H,
        1, 1, 1,
        H, H, H, H, H, H,
        1, 1, 1,
        V, 1};

    public static final int[] COLUMNAS = {
        E,
        IN, IN, IN, H, H, H,
        H, H, H,
        H, H, H, H, H, H,
        H, H, H,
        H, V};

    public static final int TENSORES = 21;

    // Indices con nombre, para leer el codigo sin contar posiciones.
    public static final int EMB = 0;
    public static final int G1WZ = 1, G1WR = 2, G1WH = 3;
    public static final int G1UZ = 4, G1UR = 5, G1UH = 6;
    public static final int G1BZ = 7, G1BR = 8, G1BH = 9;
    public static final int G2WZ = 10, G2WR = 11, G2WH = 12;
    public static final int G2UZ = 13, G2UR = 14, G2UH = 15;
    public static final int G2BZ = 16, G2BR = 17, G2BH = 18;
    public static final int SALW = 19, SALB = 20;

    /** Pesos maestros en flotante. */
    public final float[][] p = new float[TENSORES][];
    /** Pesos vistos por la propagacion (iguales a p, o con cuantizacion simulada). */
    public final float[][] w = new float[TENSORES][];
    private final float[][] m = new float[TENSORES][];
    private final float[][] v = new float[TENSORES][];
    private int paso;

    public Modelo(long semilla) {
        Random r = new Random(semilla);
        for (int t = 0; t < TENSORES; t++) {
            int n = FILAS[t] * COLUMNAS[t];
            p[t] = new float[n];
            w[t] = new float[n];
            m[t] = new float[n];
            v[t] = new float[n];
            if (esSesgo(t)) {
                continue;                       // sesgos a cero
            }
            double lim;
            if (t == EMB) {
                lim = 0.10;
                for (int i = 0; i < n; i++) {
                    p[t][i] = (float) (r.nextGaussian() * lim);
                }
                continue;
            }
            lim = Math.sqrt(6.0 / (FILAS[t] + COLUMNAS[t]));
            for (int i = 0; i < n; i++) {
                p[t][i] = (float) ((r.nextDouble() * 2 - 1) * lim);
            }
        }
        copiarPesos(false);
    }

    public static boolean esSesgo(int t) {
        return FILAS[t] == 1;
    }

    public int parametros() {
        int n = 0;
        for (int t = 0; t < TENSORES; t++) {
            n += FILAS[t] * COLUMNAS[t];
        }
        return n;
    }

    /**
     * Prepara los pesos de trabajo. Con cuantizada=true simula el int8 de la
     * exportacion (entrenamiento consciente de la cuantizacion, con gradiente
     * pasante), de modo que los pesos finales toleren el redondeo.
     */
    public void copiarPesos(boolean cuantizada) {
        for (int t = 0; t < TENSORES; t++) {
            if (!cuantizada) {
                System.arraycopy(p[t], 0, w[t], 0, p[t].length);
            } else {
                int filas = FILAS[t], cols = COLUMNAS[t];
                for (int f = 0; f < filas; f++) {
                    int base = f * cols;
                    float max = 0;
                    for (int j = 0; j < cols; j++) {
                        float a = Math.abs(p[t][base + j]);
                        if (a > max) {
                            max = a;
                        }
                    }
                    float esc = max / 127f;
                    if (esc <= 0) {
                        esc = 1e-8f;
                    }
                    for (int j = 0; j < cols; j++) {
                        int q = Math.round(p[t][base + j] / esc);
                        if (q > 127) {
                            q = 127;
                        } else if (q < -127) {
                            q = -127;
                        }
                        w[t][base + j] = q * esc;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Algebra
    // ------------------------------------------------------------------
    static void matVecAcum(float[] a, int filas, int cols, float[] x, float[] y) {
        for (int f = 0; f < filas; f++) {
            int base = f * cols;
            float s = 0;
            for (int j = 0; j < cols; j++) {
                s += a[base + j] * x[j];
            }
            y[f] += s;
        }
    }

    static void matTVecAcum(float[] a, int filas, int cols, float[] dy, float[] dx) {
        for (int f = 0; f < filas; f++) {
            float d = dy[f];
            if (d == 0) {
                continue;
            }
            int base = f * cols;
            for (int j = 0; j < cols; j++) {
                dx[j] += a[base + j] * d;
            }
        }
    }

    static void acumExterno(float[] g, int filas, int cols, float[] dy, float[] x) {
        for (int f = 0; f < filas; f++) {
            float d = dy[f];
            if (d == 0) {
                continue;
            }
            int base = f * cols;
            for (int j = 0; j < cols; j++) {
                g[base + j] += d * x[j];
            }
        }
    }

    static float sigmoide(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    static float tanh(float x) {
        return (float) Math.tanh(x);
    }

    // ------------------------------------------------------------------
    // Estado por hilo: activaciones, gradientes y buferes
    // ------------------------------------------------------------------
    public static final class Estado {

        public final float[][] gr = new float[TENSORES][];
        final float[][] x1 = new float[MAXT][IN];
        final float[][] h1p = new float[MAXT][H];
        final float[][] z1 = new float[MAXT][H];
        final float[][] r1 = new float[MAXT][H];
        final float[][] c1 = new float[MAXT][H];
        final float[][] rh1 = new float[MAXT][H];
        final float[][] h1 = new float[MAXT][H];
        final float[][] h2p = new float[MAXT][H];
        final float[][] z2 = new float[MAXT][H];
        final float[][] r2 = new float[MAXT][H];
        final float[][] c2 = new float[MAXT][H];
        final float[][] rh2 = new float[MAXT][H];
        final float[][] h2 = new float[MAXT][H];
        public final float[][] lg = new float[MAXT][V];
        final float[][] pr = new float[MAXT][V];
        final float[] dlog = new float[V];
        final float[] dh2a = new float[H], dh2b = new float[H];
        final float[] dh1a = new float[H], dh1b = new float[H];
        final float[] dh2acc = new float[H];
        final float[] dh1acc = new float[H];
        final float[] dent2 = new float[H];
        final float[] dx1 = new float[IN];
        final float[] tz = new float[H], tr = new float[H];
        final float[] tc = new float[H], trh = new float[H];
        public double perdida;
        public int fichasVistas;

        public Estado() {
            for (int t = 0; t < TENSORES; t++) {
                gr[t] = new float[FILAS[t] * COLUMNAS[t]];
            }
        }

        public void limpiarGradientes() {
            for (int t = 0; t < TENSORES; t++) {
                java.util.Arrays.fill(gr[t], 0f);
            }
            perdida = 0;
            fichasVistas = 0;
        }
    }

    // ------------------------------------------------------------------
    // Adelante
    // ------------------------------------------------------------------
    private static void celdaAdelante(float[][] w, int wz, int wr, int wh,
            int uz, int ur, int uh, int bz, int br, int bh, int nin,
            float[] x, float[] hp, float[] z, float[] r, float[] c,
            float[] rh, float[] hn) {
        for (int i = 0; i < H; i++) {
            z[i] = w[bz][i];
            r[i] = w[br][i];
        }
        matVecAcum(w[wz], H, nin, x, z);
        matVecAcum(w[uz], H, H, hp, z);
        matVecAcum(w[wr], H, nin, x, r);
        matVecAcum(w[ur], H, H, hp, r);
        for (int i = 0; i < H; i++) {
            z[i] = sigmoide(z[i]);
            r[i] = sigmoide(r[i]);
            rh[i] = r[i] * hp[i];
        }
        for (int i = 0; i < H; i++) {
            c[i] = w[bh][i];
        }
        matVecAcum(w[wh], H, nin, x, c);
        matVecAcum(w[uh], H, H, rh, c);
        for (int i = 0; i < H; i++) {
            c[i] = tanh(c[i]);
        }
        for (int i = 0; i < H; i++) {
            hn[i] = (1f - z[i]) * hp[i] + z[i] * c[i];
        }
    }

    /**
     * Propaga la secuencia completa con forzado por el maestro.
     *
     * @param fichas objetivos: las fichas de nota mas FICHA_FIN.
     * @return numero de pasos.
     */
    public int adelante(float[][] w, int[] fichas, float[] ctx, Estado e) {
        int T = fichas.length;
        for (int t = 0; t < T; t++) {
            int ficha = (t == 0) ? -1 : fichas[t - 1];
            float[] x = e.x1[t];
            if (ficha >= 0) {
                System.arraycopy(w[EMB], ficha * E, x, 0, E);
            } else {
                java.util.Arrays.fill(x, 0, E, 0f);
            }
            System.arraycopy(ctx, 0, x, E, C);

            float[] hp1 = e.h1p[t];
            float[] hp2 = e.h2p[t];
            if (t == 0) {
                java.util.Arrays.fill(hp1, 0f);
                java.util.Arrays.fill(hp2, 0f);
            } else {
                System.arraycopy(e.h1[t - 1], 0, hp1, 0, H);
                System.arraycopy(e.h2[t - 1], 0, hp2, 0, H);
            }
            celdaAdelante(w, G1WZ, G1WR, G1WH, G1UZ, G1UR, G1UH,
                    G1BZ, G1BR, G1BH, IN, x, hp1,
                    e.z1[t], e.r1[t], e.c1[t], e.rh1[t], e.h1[t]);
            celdaAdelante(w, G2WZ, G2WR, G2WH, G2UZ, G2UR, G2UH,
                    G2BZ, G2BR, G2BH, H, e.h1[t], hp2,
                    e.z2[t], e.r2[t], e.c2[t], e.rh2[t], e.h2[t]);

            float[] lg = e.lg[t];
            System.arraycopy(w[SALB], 0, lg, 0, V);
            matVecAcum(w[SALW], V, H, e.h2[t], lg);
        }
        return T;
    }

    // ------------------------------------------------------------------
    // Atras
    // ------------------------------------------------------------------
    private static void celdaAtras(float[][] w, int wz, int wr, int wh,
            int uz, int ur, int uh, int bz, int br, int bh, int nin,
            float[] x, float[] hp, float[] z, float[] r, float[] c, float[] rh,
            float[] dh, float[] dhp, float[] dx, float[][] gr, Estado e) {
        float[] tz = e.tz, tr = e.tr, tc = e.tc, trh = e.trh;
        for (int i = 0; i < H; i++) {
            float d = dh[i];
            float dz = d * (c[i] - hp[i]);
            float dc = d * z[i];
            dhp[i] += d * (1f - z[i]);
            tc[i] = dc * (1f - c[i] * c[i]);
            tz[i] = dz * z[i] * (1f - z[i]);
            trh[i] = 0;
        }
        matTVecAcum(w[uh], H, H, tc, trh);
        for (int i = 0; i < H; i++) {
            float dr = trh[i] * hp[i];
            dhp[i] += trh[i] * r[i];
            tr[i] = dr * r[i] * (1f - r[i]);
        }
        matTVecAcum(w[uz], H, H, tz, dhp);
        matTVecAcum(w[ur], H, H, tr, dhp);
        matTVecAcum(w[wz], H, nin, tz, dx);
        matTVecAcum(w[wr], H, nin, tr, dx);
        matTVecAcum(w[wh], H, nin, tc, dx);
        acumExterno(gr[wz], H, nin, tz, x);
        acumExterno(gr[uz], H, H, tz, hp);
        acumExterno(gr[wr], H, nin, tr, x);
        acumExterno(gr[ur], H, H, tr, hp);
        acumExterno(gr[wh], H, nin, tc, x);
        acumExterno(gr[uh], H, H, tc, rh);
        for (int i = 0; i < H; i++) {
            gr[bz][i] += tz[i];
            gr[br][i] += tr[i];
            gr[bh][i] += tc[i];
        }
    }

    /** Adelante y atras sobre una frase. Acumula perdida y gradientes en e. */
    public void fraseAdelanteAtras(float[][] w, int[] fichas, float[] ctx, Estado e) {
        int T = adelante(w, fichas, ctx, e);
        // Softmax y perdida.
        for (int t = 0; t < T; t++) {
            float[] lg = e.lg[t];
            float[] pr = e.pr[t];
            float max = lg[0];
            for (int i = 1; i < V; i++) {
                if (lg[i] > max) {
                    max = lg[i];
                }
            }
            double suma = 0;
            for (int i = 0; i < V; i++) {
                double ex = Math.exp(lg[i] - max);
                pr[i] = (float) ex;
                suma += ex;
            }
            float inv = (float) (1.0 / suma);
            for (int i = 0; i < V; i++) {
                pr[i] *= inv;
            }
            double pobj = pr[fichas[t]];
            if (pobj < 1e-12) {
                pobj = 1e-12;
            }
            e.perdida += -Math.log(pobj);
            e.fichasVistas++;
        }

        float[] dh2sig = e.dh2a, dh2ant = e.dh2b;
        float[] dh1sig = e.dh1a, dh1ant = e.dh1b;
        java.util.Arrays.fill(dh2sig, 0f);
        java.util.Arrays.fill(dh1sig, 0f);
        float[][] gr = e.gr;

        for (int t = T - 1; t >= 0; t--) {
            float[] pr = e.pr[t];
            float[] dlog = e.dlog;
            System.arraycopy(pr, 0, dlog, 0, V);
            dlog[fichas[t]] -= 1f;
            acumExterno(gr[SALW], V, H, dlog, e.h2[t]);
            for (int i = 0; i < V; i++) {
                gr[SALB][i] += dlog[i];
            }
            // dh2 = salW^T dlog + lo que viene del paso siguiente
            float[] dh2 = e.dh2acc;
            java.util.Arrays.fill(dh2, 0f);
            matTVecAcum(w[SALW], V, H, dlog, dh2);
            for (int i = 0; i < H; i++) {
                dh2[i] += dh2sig[i];
            }
            java.util.Arrays.fill(dh2ant, 0f);
            // La entrada de la capa 2 es h1[t]; su gradiente se suma a dh1.
            float[] dent2 = e.dent2;
            java.util.Arrays.fill(dent2, 0f);
            celdaAtras(w, G2WZ, G2WR, G2WH, G2UZ, G2UR, G2UH, G2BZ, G2BR, G2BH,
                    H, e.h1[t], e.h2p[t], e.z2[t], e.r2[t], e.c2[t], e.rh2[t],
                    dh2, dh2ant, dent2, gr, e);

            float[] dh1 = e.dh1acc;
            for (int i = 0; i < H; i++) {
                dh1[i] = dent2[i] + dh1sig[i];
            }
            java.util.Arrays.fill(dh1ant, 0f);
            float[] dx1 = e.dx1;
            java.util.Arrays.fill(dx1, 0f);
            celdaAtras(w, G1WZ, G1WR, G1WH, G1UZ, G1UR, G1UH, G1BZ, G1BR, G1BH,
                    IN, e.x1[t], e.h1p[t], e.z1[t], e.r1[t], e.c1[t], e.rh1[t],
                    dh1, dh1ant, dx1, gr, e);

            int ficha = (t == 0) ? -1 : fichas[t - 1];
            if (ficha >= 0) {
                int base = ficha * E;
                for (int i = 0; i < E; i++) {
                    gr[EMB][base + i] += dx1[i];
                }
            }
            float[] tmp = dh2sig;
            dh2sig = dh2ant;
            dh2ant = tmp;
            tmp = dh1sig;
            dh1sig = dh1ant;
            dh1ant = tmp;
        }
    }

    // ------------------------------------------------------------------
    // Adam
    // ------------------------------------------------------------------
    /**
     * Un paso de Adam sobre los gradientes ya sumados y escalados.
     *
     * @param g      gradientes acumulados (se reescalan por 1/fichas).
     * @param fichas numero de fichas del lote.
     * @param lr     tasa de aprendizaje.
     * @param recorte norma maxima del gradiente.
     * @return la norma global del gradiente antes de recortar.
     */
    public double actualizar(float[][] g, int fichas, double lr, double recorte,
            double decaimiento) {
        float inv = 1f / fichas;
        double sum2 = 0;
        for (int t = 0; t < TENSORES; t++) {
            float[] gt = g[t];
            for (int i = 0; i < gt.length; i++) {
                gt[i] *= inv;
                sum2 += (double) gt[i] * gt[i];
            }
        }
        double norma = Math.sqrt(sum2);
        float escala = 1f;
        if (norma > recorte) {
            escala = (float) (recorte / norma);
        }
        paso++;
        double b1 = 0.9, b2 = 0.999, eps = 1e-8;
        double c1 = 1.0 - Math.pow(b1, paso);
        double c2 = 1.0 - Math.pow(b2, paso);
        for (int t = 0; t < TENSORES; t++) {
            float[] gt = g[t];
            float[] pt = p[t];
            float[] mt = m[t];
            float[] vt = v[t];
            boolean decae = !esSesgo(t) && t != EMB;
            for (int i = 0; i < gt.length; i++) {
                float gi = gt[i] * escala;
                mt[i] = (float) (b1 * mt[i] + (1 - b1) * gi);
                vt[i] = (float) (b2 * vt[i] + (1 - b2) * gi * gi);
                double mh = mt[i] / c1;
                double vh = vt[i] / c2;
                double d = lr * mh / (Math.sqrt(vh) + eps);
                if (decae) {
                    d += lr * decaimiento * pt[i];
                }
                pt[i] -= (float) d;
            }
        }
        return norma;
    }

    // ------------------------------------------------------------------
    // Exportacion a int8 con una escala por fila
    // ------------------------------------------------------------------
    public static void cuantizar(float[] src, int filas, int cols,
            byte[] datos, float[] escalas) {
        for (int f = 0; f < filas; f++) {
            int base = f * cols;
            float max = 0;
            for (int j = 0; j < cols; j++) {
                float a = Math.abs(src[base + j]);
                if (a > max) {
                    max = a;
                }
            }
            float esc = max / 127f;
            if (esc <= 0) {
                esc = 1e-8f;
            }
            escalas[f] = esc;
            for (int j = 0; j < cols; j++) {
                int q = Math.round(src[base + j] / esc);
                if (q > 127) {
                    q = 127;
                } else if (q < -127) {
                    q = -127;
                }
                datos[base + j] = (byte) q;
            }
        }
    }

    /** Escribe el fichero en el formato que lee PesosNeuronales. */
    public long exportar(File destino) throws IOException {
        File padre = destino.getParentFile();
        if (padre != null && !padre.exists()) {
            padre.mkdirs();
        }
        DataOutputStream d = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(destino)));
        try {
            d.writeByte('J');
            d.writeByte('4');
            d.writeByte('F');
            d.writeByte('W');
            d.writeInt(1);
            d.writeInt(TENSORES);
            for (int t = 0; t < TENSORES; t++) {
                byte[] nombre = NOMBRES[t].getBytes("UTF-8");
                d.writeInt(nombre.length);
                d.write(nombre);
                int filas = FILAS[t], cols = COLUMNAS[t];
                d.writeInt(filas);
                d.writeInt(cols);
                float[] escalas = new float[filas];
                byte[] datos = new byte[filas * cols];
                cuantizar(p[t], filas, cols, datos, escalas);
                for (int f = 0; f < filas; f++) {
                    d.writeFloat(escalas[f]);
                }
                d.write(datos);
            }
        } finally {
            d.close();
        }
        return destino.length();
    }
}
