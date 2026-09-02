import j4f.Fireworks;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import javax.imageio.ImageIO;

/**
 * Genera el arte del canal con el propio motor de la aplicacion.
 *   java ArteCanal <carpeta> <nombre> <lema>
 */
public class ArteCanal {

    static Method m(String n, Class<?>... c) throws Exception {
        Method x = Fireworks.class.getDeclaredMethod(n, c); x.setAccessible(true); return x;
    }

    /** Renderiza una escena limpia, sin interfaz. */
    static BufferedImage escena(int w, int h, int fotogramas, long semilla) throws Exception {
        Field fr = Class.forName("j4f.Azar").getDeclaredField("R"); fr.setAccessible(true);
        ((java.util.Random) fr.get(null)).setSeed(semilla);
        Fireworks p = new Fireworks(); p.setSize(w, h);
        Method aju = m("ajustarTamano", int.class, int.class),
               act = m("actualizar", double.class, int.class, int.class),
               est = m("dibujarEstelas", double.class, int.class, int.class),
               ren = m("renderizar", BufferedImage.class, int.class, int.class);
        aju.invoke(p, w, h);
        // Sin rotulos: el titulo y la ayuda estorban en el arte del canal.
        Field intro = Fireworks.class.getDeclaredField("intro"); intro.setAccessible(true);
        Field desde = Fireworks.class.getDeclaredField("desdeInteraccion"); desde.setAccessible(true);
        Field fs = Fireworks.class.getDeclaredField("pantallaCompleta"); fs.setAccessible(true);
        fs.setBoolean(p, true);
        for (int i = 0; i < fotogramas; i++) {
            act.invoke(p, 1.0 / 60, w, h); est.invoke(p, 1.0 / 60, w, h);
            intro.setDouble(p, 0); desde.setDouble(p, 999);
        }
        BufferedImage f = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ren.invoke(p, f, w, h);
        return f;
    }

    static void texto(Graphics2D g, String s, Font f, float x, float y, float espaciado, Color c) {
        g.setFont(f); FontMetrics fm = g.getFontMetrics();
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // Sombra suave: el arte de fondo es claro en algunas zonas.
            g.setColor(new Color(0, 0, 0, 150));
            g.drawString(String.valueOf(ch), cx + 3, y + 3);
            g.setColor(c);
            g.drawString(String.valueOf(ch), cx, y);
            cx += fm.charWidth(ch) + espaciado;
        }
    }

    static float ancho(String s, Font f, Graphics2D g, float espaciado) {
        FontMetrics fm = g.getFontMetrics(f); float a = 0;
        for (int i = 0; i < s.length(); i++) a += fm.charWidth(s.charAt(i)) + espaciado;
        return a - espaciado;
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File dir = new File(a[0]); String nombre = a[1]; String lema = a[2];

        // --- Banner 2048x1152, zona segura 1546x423 centrada ---
        BufferedImage b = null; double mejor = -1;
        long[] semillas = {424242L, 7L, 99L, 20260901L, 31337L, 555L};
        for (int i = 0; i < semillas.length; i++) {
            BufferedImage c = escena(2048, 1152, 380 + i * 17, semillas[i]);
            // Se puntua el brillo de la mitad superior, que es donde van los
            // fuegos: un cielo vacio da un banner soso.
            double luz = 0;
            for (int y = 60; y < 560; y += 3)
                for (int x = 200; x < 1848; x += 3) {
                    int v = c.getRGB(x, y);
                    luz += ((v >> 16 & 255) + (v >> 8 & 255) + (v & 255));
                }
            if (luz > mejor) { mejor = luz; b = c; }
        }
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font ft = new Font("Segoe UI", Font.PLAIN, 108);
        Font fl = new Font("Segoe UI", Font.PLAIN, 38);
        float espT = 14f, espL = 6f;
        float at = ancho(nombre, ft, g, espT), al = ancho(lema, fl, g, espL);
        // La zona segura de movil son 1235 px: lo que la pase se recorta ahi.
        if (at > 1235) System.out.println("AVISO: el nombre se sale en movil ("
                + (int) at + " > 1235 px)");
        if (al > 1235) System.out.println("AVISO: el lema se sale en movil ("
                + (int) al + " > 1235 px)");
        texto(g, nombre, ft, (2048 - at) / 2f, 1152 / 2f + 10, espT, new Color(255, 255, 255, 245));
        texto(g, lema, fl, (2048 - al) / 2f, 1152 / 2f + 78, espL, new Color(214, 226, 255, 210));
        g.setColor(new Color(255, 255, 255, 90));
        g.fillRect((int) ((2048 - at * 0.45f) / 2), 1152 / 2 + 34, (int) (at * 0.45f), 2);
        g.dispose();
        ImageIO.write(b, "png", new File(dir, "banner_2048x1152.png"));

        // Referencia con la zona segura marcada, solo para revisar
        BufferedImage guia = new BufferedImage(2048, 1152, BufferedImage.TYPE_INT_RGB);
        Graphics2D gg = guia.createGraphics();
        gg.drawImage(b, 0, 0, null);
        gg.setColor(new Color(0, 255, 120, 200)); gg.setStroke(new BasicStroke(3));
        gg.drawRect((2048 - 1546) / 2, (1152 - 423) / 2, 1546, 423);
        gg.setColor(new Color(255, 200, 0, 200));
        gg.drawRect((2048 - 1235) / 2, (1152 - 338) / 2, 1235, 338);
        gg.dispose();
        ImageIO.write(guia, "png", new File(dir, "banner_GUIA_zonas.png"));

        // --- Avatar 800x800, pensado para recorte circular ---
        BufferedImage av = escena(800, 800, 260, 777L);
        Graphics2D ga = av.createGraphics();
        ga.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ga.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Iniciales grandes, que es lo unico legible a 48 px
        String ini = iniciales(nombre);
        Font fa = new Font("Segoe UI", Font.PLAIN, 300);
        float aa = ancho(ini, fa, ga, 8f);
        texto(ga, ini, fa, (800 - aa) / 2f, 800 / 2f + 105, 8f, new Color(255, 255, 255, 240));
        ga.dispose();
        ImageIO.write(av, "png", new File(dir, "avatar_800x800.png"));

        // Vista de como se ve recortado en circulo y pequeno
        BufferedImage mini = new BufferedImage(176, 176, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gm = mini.createGraphics();
        gm.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gm.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gm.setClip(new java.awt.geom.Ellipse2D.Float(0, 0, 176, 176));
        gm.drawImage(av, 0, 0, 176, 176, null);
        gm.dispose();
        ImageIO.write(mini, "png", new File(dir, "avatar_vista_176.png"));

        System.out.println("Generado en " + dir.getAbsolutePath());
        System.out.println("  banner_2048x1152.png   (el que se sube)");
        System.out.println("  banner_GUIA_zonas.png  (verde: visible en todo; amarillo: solo movil)");
        System.out.println("  avatar_800x800.png     (el que se sube)");
        System.out.println("  avatar_vista_176.png   (como se vera recortado)");
    }

    static String iniciales(String n) {
        String[] p = n.trim().split(" +");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length && sb.length() < 2; i++)
            if (p[i].length() > 0) sb.append(Character.toUpperCase(p[i].charAt(0)));
        return sb.toString();
    }
}
