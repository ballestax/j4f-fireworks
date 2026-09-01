package j4f;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache de sprites de resplandor. Dibujar cada chispa con un degradado radial
 * daria un halo precioso pero es carisimo repetido cientos de veces por
 * fotograma, asi que se genera un sprite por color (cuantizado) una sola vez y
 * despues solo se escala y se compone.
 *
 * @author ballestas
 */
public final class Destello {

    private static final int TAM = 64;
    private static final Map<Integer, BufferedImage> CACHE = new HashMap<Integer, BufferedImage>();

    /** Niveles de opacidad precalculados. */
    private static final int NIVELES = 64;
    private static final AlphaComposite[] MEZCLAS = new AlphaComposite[NIVELES + 1];

    static {
        for (int i = 0; i <= NIVELES; i++) {
            MEZCLAS[i] = AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, (float) i / NIVELES);
        }
    }

    private Destello() {
    }

    /**
     * Composite de opacidad ya construido. Se pedia uno nuevo por chispa y por
     * fotograma, o sea decenas de miles de objetos por segundo; con 64 niveles
     * la diferencia no se ve y se reutilizan siempre los mismos.
     */
    public static AlphaComposite mezcla(double alfa) {
        int i = (int) Math.round(alfa * NIVELES);
        if (i < 0) {
            i = 0;
        } else if (i > NIVELES) {
            i = NIVELES;
        }
        return MEZCLAS[i];
    }

    /** Sprite de halo para el color dado (nucleo casi blanco y caida suave). */
    public static synchronized BufferedImage de(Color c) {
        int clave = cuantizar(c);
        BufferedImage img = CACHE.get(clave);
        if (img == null) {
            img = crear(new Color(clave));
            CACHE.put(clave, img);
        }
        return img;
    }

    /** Reduce la paleta a 5 bits por canal para acotar el tamano del cache. */
    private static int cuantizar(Color c) {
        return ((c.getRed() & 0xF8) << 16) | ((c.getGreen() & 0xF8) << 8) | (c.getBlue() & 0xF8);
    }

    private static BufferedImage crear(Color c) {
        BufferedImage img = new BufferedImage(TAM, TAM, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float r = TAM / 2f;
        Color nucleo = mezclar(c, Color.WHITE, 0.85f);
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(r, r), r,
                new float[]{0f, 0.09f, 0.22f, 0.52f, 1f},
                new Color[]{
                    alfa(nucleo, 255),
                    alfa(nucleo, 232),
                    alfa(c, 165),
                    alfa(c, 42),
                    alfa(c, 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fillRect(0, 0, TAM, TAM);
        g.dispose();
        return img;
    }

    /** Interpola a hacia b: t=0 devuelve a, t=1 devuelve b. */
    public static Color mezclar(Color a, Color b, float t) {
        float u = t < 0f ? 0f : (t > 1f ? 1f : t);
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * u),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * u),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * u));
    }

    public static Color alfa(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a < 0 ? 0 : (a > 255 ? 255 : a));
    }
}
