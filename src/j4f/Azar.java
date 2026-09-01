package j4f;

import java.awt.Color;
import java.util.Random;

/**
 * Utilidades de azar.
 *
 * Reemplaza las dependencias externas del proyecto original
 * (org.balx.Utiles, org.dzur.Mat y org.dzur.Util), que apuntaban a
 * bxLibrary_2.0.jar mediante una ruta absoluta de otra maquina y hacian
 * imposible compilar el proyecto.
 *
 * @author ballestas
 */
public final class Azar {

    private static final Random R = new Random();

    private Azar() {
    }

    /** Entero aleatorio en [min, max], ambos inclusive. */
    public static int entre(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + R.nextInt(max - min + 1);
    }

    /** Real aleatorio en [min, max). */
    public static double entre(double min, double max) {
        return min + R.nextDouble() * (max - min);
    }

    /** Cierto con probabilidad p (0..1). */
    public static boolean probabilidad(double p) {
        return R.nextDouble() < p;
    }

    /** Ruido normal de media 0 y desviacion 1. */
    public static double gauss() {
        return R.nextGaussian();
    }

    /** Angulo aleatorio en radianes, [0, 2*PI). */
    public static double angulo() {
        return R.nextDouble() * Math.PI * 2;
    }

    /** Matiz aleatorio en [0, 1). */
    public static float matiz() {
        return R.nextFloat();
    }

    /** Color saturado y luminoso, apto para pirotecnia. */
    public static Color colorVivo() {
        return hsb(R.nextFloat(), (float) entre(0.80, 1.0), 1f);
    }

    public static Color hsb(float h, float s, float b) {
        return Color.getHSBColor(h - (float) Math.floor(h), clamp(s), clamp(b));
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
