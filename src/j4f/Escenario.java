package j4f;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Escenario de fondo: una bahia urbana de noche.
 *
 * El fondo se hornea en imagenes una sola vez por cada cambio de tamano (cielo
 * con luna y polucion luminica, silueta de la ciudad y base del agua) y solo lo
 * verdaderamente vivo se pinta por fotograma: el centelleo de las estrellas,
 * unas pocas ventanas parpadeantes, las balizas de las antenas y el reflejo
 * real de la pirotecnia sobre el agua.
 *
 * @author ballestas
 */
public class Escenario {

    /** Altura relativa de la linea de agua. */
    private static final double FRACCION_HORIZONTE = 0.78;
    private static final int NUM_ESTRELLAS = 190;
    private static final int NUM_ESTRELLAS_CRUZ = 8;
    private static final int MAX_VENTANAS_VIVAS = 14;
    private static final int MAX_BALIZAS = 4;
    private static final int NUM_BRILLOS_AGUA = 5;
    /** Jirones de niebla que se arrastran entre los edificios. */
    private static final int NUM_JIRONES = 10;

    /** Tonos posibles de las estrellas: blanco, azulado y calido. */
    private static final Color[] TONOS_ESTRELLA = {
        new Color(255, 255, 255),
        new Color(198, 214, 255),
        new Color(255, 233, 202)
    };

    /** Tonos del smog: frio arriba, sucio y calido junto a las luces. */
    private static final Color NIEBLA_FRIA = new Color(96, 116, 156);
    private static final Color NIEBLA_CALIDA = new Color(168, 138, 104);

    private static final Color EDIFICIO_OSCURO = new Color(0x04, 0x06, 0x0F);
    private static final Color EDIFICIO_CLARO = new Color(0x0A, 0x10, 0x24);
    private static final Color LUZ_VENTANA = new Color(0xFF, 0xD9, 0xA0);
    private static final Color LUZ_CIUDAD = new Color(255, 180, 90);
    private static final Color LUZ_LUNA = new Color(0xF2, 0xF4, 0xFF);
    private static final Color LUZ_BALIZA = new Color(255, 64, 48);
    private static final Color BRILLO_AGUA = new Color(170, 205, 255);

    private int ancho;
    private int alto;
    private double horizonte;
    private int yHorizonte;
    private double t;

    // Jirones de niebla: posicion, tamano y deriva de cada uno.
    private double[] nieX;
    private int[] nieY;
    private int[] nieW;
    private int[] nieH;
    private double[] nieVel;
    /** Velo fijo del smog, ya rasterizado. */
    private BufferedImage nieblaVelo;
    private int yNiebla;
    private double[] nieFase;
    private float[] nieAlfa;
    private boolean[] nieCalida;

    // Capas horneadas.
    private BufferedImage cielo;
    private BufferedImage skyline;
    private BufferedImage aguaBase;

    // Estrellas: generadas una vez, animadas por fotograma.
    private int[] estX;
    private int[] estY;
    private int[] estTam;
    private float[] estBrillo;
    private double[] estFase;
    private double[] estVel;
    private boolean[] estCruz;
    private int[] estTono;

    // Ventanas que parpadean encima del skyline horneado.
    private int[] venX;
    private int[] venY;
    private int[] venW;
    private int[] venH;
    private double[] venFase;
    private double[] venVel;

    // Balizas rojas de las antenas.
    private int[] balX;
    private int[] balY;
    private double[] balFase;

    // Brillos especulares del agua.
    private double[] briFase;
    private double[] briVel;
    private double[] briAncho;
    private int[] briY;

    public Escenario() {
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    public void redimensionar(int ancho, int alto) {
        if (ancho == this.ancho && alto == this.alto) {
            return;
        }
        this.ancho = ancho;
        this.alto = alto;
        if (ancho <= 0 || alto <= 0) {
            horizonte = 0;
            yHorizonte = 0;
            cielo = null;
            skyline = null;
            aguaBase = null;
            nieblaVelo = null;
            nieX = null;
            return;
        }
        horizonte = alto * FRACCION_HORIZONTE;
        yHorizonte = (int) Math.round(horizonte);
        if (yHorizonte < 1) {
            yHorizonte = 1;
        }
        if (yHorizonte > alto - 1) {
            yHorizonte = alto - 1;
        }
        generarEstrellas();
        generarBrillosAgua();
        generarNiebla();
        cielo = crearCielo();
        skyline = crearSkyline();
        incrustarVeloNiebla();
        aguaBase = crearAgua();
    }

    public void actualizar(double dt) {
        if (dt > 0) {
            t += dt;
        }
    }

    public double getHorizonte() {
        return horizonte;
    }

    private boolean listo() {
        return ancho > 0 && alto > 0 && cielo != null && skyline != null && aguaBase != null;
    }

    // ------------------------------------------------------------------
    // Horneado del cielo: degradado, domo de polucion luminica y luna
    // ------------------------------------------------------------------

    private BufferedImage crearCielo() {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(0f, 0f),
                new Point2D.Float(0f, Math.max(1f, (float) alto)),
                new float[]{0f, 0.45f, 0.78f, 1f},
                new Color[]{
                    new Color(0x03, 0x05, 0x0E),
                    new Color(0x08, 0x11, 0x32),
                    new Color(0x12, 0x20, 0x4A),
                    new Color(0x1B, 0x2A, 0x55)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fillRect(0, 0, ancho, alto);

        // Domo calido de la ciudad justo encima del agua: el detalle que hace
        // que el cielo se lea como noche urbana y no como un degradado.
        BufferedImage domo = Destello.de(LUZ_CIUDAD);
        int dw = Math.max(2, (int) Math.round(ancho * 1.3));
        int dh = Math.max(2, (int) Math.round(alto * 0.5));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.14f));
        g.drawImage(domo, (ancho - dw) / 2, yHorizonte - dh / 2, dw, dh, null);

        // Un segundo domo mas estrecho concentra el resplandor sobre el centro.
        int dw2 = Math.max(2, (int) Math.round(ancho * 0.7));
        int dh2 = Math.max(2, (int) Math.round(alto * 0.26));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.10f));
        g.drawImage(domo, (ancho - dw2) / 2, yHorizonte - dh2 / 2, dw2, dh2, null);

        pintarLuna(g);

        g.dispose();
        return img;
    }

    private void pintarLuna(Graphics2D g) {
        double lx = ancho * 0.78;
        double ly = alto * 0.16;
        double r = Math.max(2.0, alto * 0.032);

        BufferedImage halo = Destello.de(new Color(200, 216, 255));
        int hw = Math.max(4, (int) Math.round(r * 13));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
        g.drawImage(halo, (int) Math.round(lx - hw / 2.0), (int) Math.round(ly - hw / 2.0), hw, hw, null);

        int hw2 = Math.max(4, (int) Math.round(r * 5));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.drawImage(halo, (int) Math.round(lx - hw2 / 2.0), (int) Math.round(ly - hw2 / 2.0), hw2, hw2, null);

        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(LUZ_LUNA);
        int d = (int) Math.round(r * 2);
        g.fillOval((int) Math.round(lx - r), (int) Math.round(ly - r), d, d);

        // Sombreado muy leve para que el disco no parezca una pegatina plana.
        g.setColor(new Color(212, 220, 242, 60));
        g.fillOval((int) Math.round(lx - r * 0.45), (int) Math.round(ly - r * 0.15),
                Math.max(1, (int) Math.round(r * 0.9)), Math.max(1, (int) Math.round(r * 0.8)));
        g.setColor(new Color(216, 224, 244, 45));
        g.fillOval((int) Math.round(lx + r * 0.05), (int) Math.round(ly - r * 0.62),
                Math.max(1, (int) Math.round(r * 0.5)), Math.max(1, (int) Math.round(r * 0.45)));
    }

    // ------------------------------------------------------------------
    // Estrellas
    // ------------------------------------------------------------------

    private void generarEstrellas() {
        estX = new int[NUM_ESTRELLAS];
        estY = new int[NUM_ESTRELLAS];
        estTam = new int[NUM_ESTRELLAS];
        estBrillo = new float[NUM_ESTRELLAS];
        estFase = new double[NUM_ESTRELLAS];
        estVel = new double[NUM_ESTRELLAS];
        estCruz = new boolean[NUM_ESTRELLAS];
        estTono = new int[NUM_ESTRELLAS];

        int techo = Math.max(1, yHorizonte - 2);
        for (int i = 0; i < NUM_ESTRELLAS; i++) {
            double u = Azar.entre(0.0, 1.0);
            estX[i] = Azar.entre(0, Math.max(0, ancho - 1));
            // El sesgo cuadratico concentra las estrellas en la parte alta.
            estY[i] = (int) Math.round(Math.pow(u, 2.0) * techo);
            estTam[i] = Azar.probabilidad(0.12) ? 2 : 1;
            estBrillo[i] = (float) Azar.entre(0.25, 1.0);
            estFase[i] = Azar.angulo();
            estVel[i] = Azar.entre(0.5, 2.6);
            estTono[i] = Azar.entre(0, TONOS_ESTRELLA.length - 1);
            estCruz[i] = false;
        }

        // Las puntas de estrella se reservan para unas pocas muy brillantes.
        int puestas = 0;
        for (int vuelta = 0; vuelta < 4 && puestas < NUM_ESTRELLAS_CRUZ; vuelta++) {
            float umbral = 0.92f - vuelta * 0.12f;
            for (int i = 0; i < NUM_ESTRELLAS && puestas < NUM_ESTRELLAS_CRUZ; i++) {
                if (!estCruz[i] && estBrillo[i] >= umbral) {
                    estCruz[i] = true;
                    estTam[i] = 2;
                    puestas++;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Skyline
    // ------------------------------------------------------------------

    private BufferedImage crearSkyline() {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        List<int[]> vivas = new ArrayList<int[]>();
        List<int[]> balizas = new ArrayList<int[]>();

        int minVentana = Math.max(1, (int) Math.round(alto * 0.005));
        int pisoAlto = Math.max(3, (int) Math.round(alto * 0.014));

        int x = 0;
        while (x < ancho) {
            int w = (int) Math.round(Azar.entre(alto * 0.03, alto * 0.11));
            if (w < 6) {
                w = 6;
            }
            int h = (int) Math.round(Azar.entre(alto * 0.04, alto * 0.20));
            if (h < 8) {
                h = 8;
            }
            if (h > yHorizonte - 2) {
                h = Math.max(4, yHorizonte - 2);
            }
            int baseAlta = yHorizonte - h;
            int cima = baseAlta;

            Color relleno = Destello.mezclar(EDIFICIO_OSCURO, EDIFICIO_CLARO,
                    (float) Azar.entre(0.0, 1.0));
            g.setColor(relleno);
            g.fillRect(x, baseAlta, w, h);

            // Remate escalonado.
            if (Azar.probabilidad(0.35) && w > 10) {
                int w2 = (int) Math.round(w * Azar.entre(0.45, 0.72));
                int h2 = (int) Math.round(h * Azar.entre(0.12, 0.32));
                if (w2 >= 4 && h2 >= 3 && baseAlta - h2 >= 0) {
                    g.fillRect(x + (w - w2) / 2, baseAlta - h2, w2, h2);
                    cima = baseAlta - h2;
                }
            }

            // Mastil de antena con su baliza.
            if (Azar.probabilidad(0.30)) {
                int hm = (int) Math.round(h * Azar.entre(0.14, 0.32));
                if (hm >= 4) {
                    int xm = x + w / 2;
                    int ym = Math.max(0, cima - hm);
                    if (cima - ym > 0) {
                        g.setColor(Destello.mezclar(relleno, EDIFICIO_CLARO, 0.6f));
                        g.fillRect(xm, ym, Azar.probabilidad(0.5) ? 1 : 2, cima - ym);
                        if (balizas.size() < MAX_BALIZAS && Azar.probabilidad(0.75)) {
                            balizas.add(new int[]{xm, ym});
                        }
                    }
                }
            }

            pintarVentanas(g, x, baseAlta, w, h, pisoAlto, minVentana, vivas);

            x += w + Azar.entre(-2, 3);
        }

        g.dispose();

        volcarVentanas(vivas);
        volcarBalizas(balizas);
        return img;
    }

    /** Ventanas encendidas: pocas, irregulares, nunca una rejilla uniforme. */
    private void pintarVentanas(Graphics2D g, int x, int y, int w, int h,
            int pisoAlto, int minVentana, List<int[]> vivas) {
        int margen = Math.max(2, w / 8);
        int vw = Math.max(minVentana, (int) Math.round(w * 0.11));
        int vh = Math.max(minVentana, (int) Math.round(pisoAlto * 0.45));
        int paso = vw + Math.max(2, vw);
        int columnas = (w - margen * 2) / paso;
        if (columnas < 1) {
            return;
        }
        double densidad = Azar.entre(0.10, 0.42);

        for (int fy = y + pisoAlto; fy < y + h - vh; fy += pisoAlto) {
            if (Azar.probabilidad(0.45)) {
                continue; // Planta entera a oscuras.
            }
            for (int c = 0; c < columnas; c++) {
                if (!Azar.probabilidad(densidad)) {
                    continue;
                }
                int vx = x + margen + c * paso;
                if (vx + vw > x + w - margen) {
                    continue;
                }
                if (vivas.size() < MAX_VENTANAS_VIVAS && Azar.probabilidad(0.02)) {
                    // Esta no se hornea: parpadeara en vivo sobre la capa.
                    vivas.add(new int[]{vx, fy, vw, vh});
                    continue;
                }
                Color luz = Destello.mezclar(LUZ_VENTANA,
                        Azar.probabilidad(0.25) ? new Color(0xBF, 0xD8, 0xFF) : Color.WHITE,
                        (float) Azar.entre(0.0, 0.35));
                g.setColor(Destello.alfa(luz, Azar.entre(120, 235)));
                g.fillRect(vx, fy, vw, vh);
            }
        }
    }

    private void volcarVentanas(List<int[]> vivas) {
        int n = vivas.size();
        venX = new int[n];
        venY = new int[n];
        venW = new int[n];
        venH = new int[n];
        venFase = new double[n];
        venVel = new double[n];
        for (int i = 0; i < n; i++) {
            int[] v = vivas.get(i);
            venX[i] = v[0];
            venY[i] = v[1];
            venW[i] = v[2];
            venH[i] = v[3];
            venFase[i] = Azar.angulo();
            venVel[i] = Azar.entre(0.7, 3.4);
        }
    }

    private void volcarBalizas(List<int[]> balizas) {
        int n = balizas.size();
        balX = new int[n];
        balY = new int[n];
        balFase = new double[n];
        for (int i = 0; i < n; i++) {
            int[] b = balizas.get(i);
            balX[i] = b[0];
            balY[i] = b[1];
            balFase[i] = Azar.angulo();
        }
    }

    // ------------------------------------------------------------------
    // Agua
    // ------------------------------------------------------------------

    /**
     * Reparte los jirones de smog por la franja de los edificios. Los de abajo
     * son mas anchos y densos; hacia arriba se afinan y se enfrian.
     */
    private void generarNiebla() {
        nieX = new double[NUM_JIRONES];
        nieY = new int[NUM_JIRONES];
        nieW = new int[NUM_JIRONES];
        nieH = new int[NUM_JIRONES];
        nieVel = new double[NUM_JIRONES];
        nieFase = new double[NUM_JIRONES];
        nieAlfa = new float[NUM_JIRONES];
        nieCalida = new boolean[NUM_JIRONES];

        double techo = yHorizonte - alto * 0.22;
        for (int i = 0; i < NUM_JIRONES; i++) {
            // Sesgado hacia el suelo: el smog se acumula abajo.
            double u = Azar.entre(0.0, 1.0);
            u = u * u;
            nieY[i] = (int) Math.round(techo + (yHorizonte - techo) * (1 - u));
            nieX[i] = Azar.entre(-0.2, 1.2) * ancho;
            nieW[i] = (int) Math.round(ancho * Azar.entre(0.15, 0.30));
            nieH[i] = (int) Math.round(alto * Azar.entre(0.035, 0.075));
            // Los de mas abajo van algo mas lentos, como si pesaran.
            nieVel[i] = Azar.entre(2.0, 9.0) * (Azar.probabilidad(0.5) ? 1 : -1);
            nieFase[i] = Azar.angulo();
            double cercania = (double) (nieY[i] - techo) / Math.max(1.0, yHorizonte - techo);
            nieAlfa[i] = (float) (0.038 + 0.070 * cercania);
            nieCalida[i] = Azar.probabilidad(0.45);
        }
        nieblaVelo = crearVeloNiebla();
    }

    /**
     * Rasteriza el velo del smog una sola vez.
     *
     * Rellenar cada fotograma con un LinearGradientPaint costaba mas de diez
     * milisegundos a 1080p; como el velo no se mueve, basta con dejarlo cocido
     * y volcarlo de una pasada.
     */
    /**
     * Funde el velo del smog dentro de la silueta ya horneada.
     *
     * Volcarlo aparte costaba un blit a pantalla completa por fotograma. Como
     * componer con SrcOver es asociativo, meterlo en la propia silueta da el
     * mismo resultado a coste cero, y de regalo el reflejo del agua sale ya
     * con la niebla incluida.
     */
    private void incrustarVeloNiebla() {
        if (skyline == null || nieblaVelo == null) {
            return;
        }
        Graphics2D g = skyline.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(nieblaVelo, 0, yNiebla, null);
        g.dispose();
        nieblaVelo = null;
    }

    private BufferedImage crearVeloNiebla() {
        yNiebla = (int) Math.round(yHorizonte - alto * 0.26);
        if (yNiebla < 0) {
            yNiebla = 0;
        }
        int altoVelo = yHorizonte - yNiebla;
        if (altoVelo <= 0 || ancho <= 0) {
            return null;
        }
        BufferedImage img = new BufferedImage(ancho, altoVelo, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(0f, 0f),
                new Point2D.Float(0f, altoVelo),
                new float[]{0f, 0.55f, 1f},
                new Color[]{
                    Destello.alfa(NIEBLA_FRIA, 0),
                    Destello.alfa(NIEBLA_FRIA, 16),
                    Destello.alfa(NIEBLA_CALIDA, 40)}));
        g.fillRect(0, 0, ancho, altoVelo);
        g.dispose();
        return img;
    }

    private void generarBrillosAgua() {
        briFase = new double[NUM_BRILLOS_AGUA];
        briVel = new double[NUM_BRILLOS_AGUA];
        briAncho = new double[NUM_BRILLOS_AGUA];
        briY = new int[NUM_BRILLOS_AGUA];
        int hAgua = Math.max(1, alto - yHorizonte);
        for (int i = 0; i < NUM_BRILLOS_AGUA; i++) {
            briFase[i] = Azar.angulo();
            briVel[i] = Azar.entre(0.10, 0.32);
            briAncho[i] = Azar.entre(0.12, 0.34);
            briY[i] = yHorizonte + (int) Math.round(hAgua * Azar.entre(0.04, 0.55));
        }
    }

    private BufferedImage crearAgua() {
        int hAgua = Math.max(1, alto - yHorizonte);
        BufferedImage img = new BufferedImage(ancho, hAgua, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(0f, 0f),
                new Point2D.Float(0f, Math.max(1f, (float) hAgua)),
                new float[]{0f, 0.35f, 1f},
                new Color[]{
                    new Color(0x14, 0x22, 0x4C),
                    new Color(0x09, 0x11, 0x2A),
                    new Color(0x03, 0x05, 0x0D)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fillRect(0, 0, ancho, hAgua);

        // Reflejo estatico de la ciudad: la misma silueta volteada y apagada.
        int origenArriba = yHorizonte - hAgua;
        if (origenArriba < 0) {
            origenArriba = 0;
        }
        int destAlto = yHorizonte - origenArriba;
        if (destAlto > 0) {
            // El reflejo se compone varias veces con pequenos desplazamientos
            // verticales. Volcado de una sola pasada, las ventanas quedaban
            // como bloques nitidos flotando en el agua; asi se deshacen en
            // trazos, que es como se comportan de verdad.
            int[] desplazamientos = {-7, -4, -2, 0, 2, 4, 7};
            for (int i = 0; i < desplazamientos.length; i++) {
                int dy = desplazamientos[i];
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.030f));
                g.drawImage(skyline,
                        0, dy, ancho, destAlto + dy,
                        0, yHorizonte, ancho, origenArriba, null);
            }
        }

        // Bandas horizontales que rompen el reflejo y sugieren oleaje.
        g.setComposite(AlphaComposite.SrcOver);
        for (int y = 0; y < hAgua; y += 2) {
            int a = 34 + (int) Math.round(46.0 * y / hAgua);
            g.setColor(new Color(3, 6, 16, Math.min(255, a)));
            g.fillRect(0, y, ancho, 1);
        }

        // Filo de la linea de agua. Un trazo fuerte se leia como un borde de
        // interfaz, asi que va tenue y con una caida suave por debajo.
        g.setColor(new Color(120, 160, 230, 30));
        g.fillRect(0, 0, ancho, 1);
        for (int y = 1; y < 7 && y < hAgua; y++) {
            g.setColor(new Color(110, 150, 220, 22 - y * 3));
            g.fillRect(0, y, ancho, 1);
        }

        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------
    // Pintado
    // ------------------------------------------------------------------

    public void pintarFondo(Graphics2D g) {
        if (g == null || !listo()) {
            return;
        }
        Paint pinturaPrevia = g.getPaint();

        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(cielo, 0, 0, null);

        Object aaPrevio = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);
        for (int i = 0; i < estX.length; i++) {
            double osc = 0.55 + 0.45 * Math.sin(estFase[i] + t * estVel[i]);
            float b = (float) (estBrillo[i] * osc);
            if (b <= 0.05f) {
                continue;
            }
            int a = (int) Math.round(b * 255);
            if (a > 255) {
                a = 255;
            }
            Color tono = TONOS_ESTRELLA[estTono[i]];
            g.setColor(new Color(tono.getRed(), tono.getGreen(), tono.getBlue(), a));
            g.fillRect(estX[i], estY[i], estTam[i], estTam[i]);

            if (estCruz[i]) {
                g.setColor(new Color(tono.getRed(), tono.getGreen(), tono.getBlue(), a / 3));
                g.fillRect(estX[i] - 3, estY[i] + 1, 8, 1);
                g.fillRect(estX[i] + 1, estY[i] - 3, 1, 8);
            }
        }

        if (aaPrevio != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aaPrevio);
        }
        g.setPaint(pinturaPrevia);
        g.setComposite(AlphaComposite.SrcOver);
    }

    public void pintarAgua(Graphics2D g, BufferedImage estelas) {
        if (g == null || !listo()) {
            return;
        }
        Paint pinturaPrevia = g.getPaint();
        Object interpPrevia = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);

        int y0 = yHorizonte;
        int hAgua = alto - y0;
        if (hAgua <= 0) {
            g.setComposite(AlphaComposite.SrcOver);
            return;
        }

        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(aguaBase, 0, y0, null);

        // Reflejo vivo de la pirotecnia: tiras horizontales espejadas sobre el
        // horizonte, desplazadas por una onda y desvanecidas con la profundidad.
        if (estelas != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int paso = 6;
            for (int d = 0; d < hAgua; d += paso) {
                int sy = y0 - d;
                if (sy - paso < 0) {
                    break;
                }
                float a = (float) (0.34 * (1.0 - (double) d / hAgua));
                if (a <= 0.01f) {
                    break;
                }
                int off = (int) Math.round(Math.sin(d * 0.055 + t * 2.2) * (1.5 + d * 0.05));
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
                g.drawImage(estelas, off, y0 + d, ancho + off, y0 + d + paso,
                        0, sy, ancho, sy - paso, null);
            }
        }

        // Destellos especulares suaves cerca del horizonte.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        BufferedImage brillo = Destello.de(BRILLO_AGUA);
        for (int i = 0; i < NUM_BRILLOS_AGUA; i++) {
            double fase = briFase[i] + t * briVel[i];
            int bw = Math.max(8, (int) Math.round(ancho * briAncho[i]));
            int bh = Math.max(3, (int) Math.round(alto * 0.018));
            int bx = (int) Math.round((0.5 + 0.42 * Math.sin(fase)) * ancho - bw / 2.0);
            float a = (float) (0.07 + 0.05 * (0.5 + 0.5 * Math.sin(fase * 1.7 + i)));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            g.drawImage(brillo, bx, briY[i] - bh / 2, bw, bh, null);
        }

        // Velo azul que unifica el agua.
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(22, 48, 96, 26));
        g.fillRect(0, y0, ancho, hAgua);

        if (interpPrevia != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpPrevia);
        }
        g.setPaint(pinturaPrevia);
        g.setComposite(AlphaComposite.SrcOver);
    }

    /**
     * Smog sobre la ciudad.
     *
     * Va despues de la silueta, de modo que come el pie de los edificios y les
     * quita contraste: es lo que da sensacion de distancia. Son dos capas, un
     * velo fijo en degradado que apoya el conjunto y unos jirones que derivan
     * despacio y se deforman con el tiempo para que nunca se repita el dibujo.
     */
    private void pintarNiebla(Graphics2D g) {
        if (nieX == null) {
            return;
        }
        Shape recortePrevio = g.getClip();
        Object interpPrevia = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);

        // El smog se queda en tierra: no debe derramarse sobre el agua.
        g.clipRect(0, yNiebla, ancho, yHorizonte - yNiebla + 1);

        // Los jirones son manchas difusas: interpolar fino no aporta nada y
        // a este tamano cuesta varios milisegundos.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (int i = 0; i < nieX.length; i++) {
            double x = nieX[i] + nieVel[i] * t;
            double periodo = ancho + nieW[i] * 2.0;
            // Deriva ciclica: asi no hace falta reponer jirones nunca.
            x = ((x + nieW[i]) % periodo + periodo) % periodo - nieW[i];
            double respiro = 1.0 + 0.18 * Math.sin(nieFase[i] + t * 0.35);
            int w = (int) Math.round(nieW[i] * respiro);
            int h = (int) Math.round(nieH[i] * (2.0 - respiro));
            if (w <= 0 || h <= 0) {
                continue;
            }
            BufferedImage jiron = Destello.de(nieCalida[i] ? NIEBLA_CALIDA : NIEBLA_FRIA);
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, nieAlfa[i]));
            g.drawImage(jiron, (int) Math.round(x), nieY[i] - h / 2, w, h, null);
        }

        if (interpPrevia != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpPrevia);
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setClip(recortePrevio);
    }

    public void pintarSkyline(Graphics2D g) {
        if (g == null || !listo()) {
            return;
        }
        Paint pinturaPrevia = g.getPaint();
        Stroke trazoPrevio = g.getStroke();

        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(skyline, 0, 0, null);

        // Ventanas que parpadean.
        for (int i = 0; i < venX.length; i++) {
            double osc = 0.5 + 0.5 * Math.sin(venFase[i] + t * venVel[i]);
            int a = 40 + (int) Math.round(osc * 200);
            if (a > 255) {
                a = 255;
            }
            g.setColor(Destello.alfa(LUZ_VENTANA, a));
            g.fillRect(venX[i], venY[i], venW[i], venH[i]);
        }

        // Balizas rojas de las antenas, a unos 0.8 Hz.
        BufferedImage halo = Destello.de(LUZ_BALIZA);
        for (int i = 0; i < balX.length; i++) {
            double onda = Math.sin(balFase[i] + t * (2 * Math.PI * 0.8));
            if (onda <= 0) {
                continue;
            }
            float a = (float) Math.min(1.0, onda);
            int hw = Math.max(6, (int) Math.round(alto * 0.026));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f * a));
            g.drawImage(halo, balX[i] - hw / 2, balY[i] - hw / 2, hw, hw, null);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            g.setColor(LUZ_BALIZA);
            g.fillRect(balX[i], balY[i] - 1, 2, 2);
        }

        pintarNiebla(g);

        if (trazoPrevio != null) {
            g.setStroke(trazoPrevio);
        }
        g.setPaint(pinturaPrevia);
        g.setComposite(AlphaComposite.SrcOver);
    }
}
