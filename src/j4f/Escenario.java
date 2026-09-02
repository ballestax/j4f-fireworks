package j4f;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
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
    /**
     * Candidatas que se recogen antes de filtrar.
     *
     * El tope final se aplica DESPUES de descartar las tapadas. Aplicarlo al
     * recoger llenaba el cupo con la capa del fondo, que se dibuja primero, y
     * las de delante la tapaban casi entera: quedaba una baliza por escena.
     */
    private static final int CANDIDATAS_VIVAS = 90;
    private static final int CANDIDATAS_BALIZAS = 40;
    private static final int MAX_BALIZAS = 4;
    private static final int NUM_BRILLOS_AGUA = 5;
    /** Jirones de niebla que se arrastran entre los edificios. */
    private static final int NUM_JIRONES = 18;

    /** Tonos posibles de las estrellas: blanco, azulado y calido. */
    private static final Color[] TONOS_ESTRELLA = {
        new Color(255, 255, 255),
        new Color(198, 214, 255),
        new Color(255, 233, 202)
    };

    /** Tonos del smog: frio arriba, sucio y calido junto a las luces. */
    private static final Color NIEBLA_FRIA = new Color(96, 116, 156);
    private static final Color NIEBLA_CALIDA = new Color(168, 138, 104);

    /**
     * Tonos de estrella ya construidos por nivel de alfa. Crear dos Color por
     * estrella y fotograma eran mas de doce mil objetos por segundo solo para
     * variar la opacidad de tres tonos fijos; con 64 niveles no se distingue.
     */
    private static final Color[][] ESTRELLA_ALFA =
            new Color[TONOS_ESTRELLA.length][65];

    static {
        for (int t = 0; t < TONOS_ESTRELLA.length; t++) {
            Color c = TONOS_ESTRELLA[t];
            for (int a = 0; a <= 64; a++) {
                ESTRELLA_ALFA[t][a] = new Color(
                        c.getRed(), c.getGreen(), c.getBlue(), a * 255 / 64);
            }
        }
    }

    private static Color tonoEstrella(int tono, int alfa255) {
        int i = alfa255 * 64 / 255;
        if (i < 0) {
            i = 0;
        } else if (i > 64) {
            i = 64;
        }
        return ESTRELLA_ALFA[tono][i];
    }

    /**
     * Capas de profundidad de la ciudad.
     *
     * Con una sola fila de rectangulos la ciudad se lee como un cartel
     * recortado. Con tres y perspectiva aerea entre ellas, se lee como una
     * ciudad. Subir el numero da mas profundidad y mas coste al redimensionar.
     */
    private static final int CAPAS_CIUDAD = 3;
    /** Hacia donde se destinen los edificios lejanos: el aire, no el negro. */
    private static final Color CALIMA_CIUDAD = new Color(0x2A, 0x3C, 0x6E);
    /** Resplandor de la calle que sube por el pie de los edificios. */
    private static final Color RESPLANDOR_CALLE = new Color(0x4A, 0x38, 0x22);
    /** Luz fria de oficina. */
    private static final Color LUZ_OFICINA = new Color(0xBF, 0xD8, 0xFF);
    /** Azul de una pantalla encendida a deshora. */
    private static final Color LUZ_PANTALLA = new Color(0x7E, 0xA8, 0xE8);

    /**
     * Paleta de fachadas para el modo en color.
     *
     * Por defecto los edificios son masa casi negra, que es lo realista. Con
     * esta opcion cada uno toma un tono propio y la profundidad la da tambien
     * el color y no solo el valor. Son tonos nocturnos y no colores planos: la
     * escena tiene que seguir siendo de noche.
     */
    private static final Color[] PALETA_FACHADAS = {
        new Color(0x1A, 0x22, 0x33),   // gris azulado
        new Color(0x20, 0x22, 0x30),   // gris neutro frio
        new Color(0x1E, 0x20, 0x2A),   // pizarra
        new Color(0x24, 0x22, 0x28),   // gris tibio
        new Color(0x18, 0x20, 0x30),   // azul apagado
        new Color(0x26, 0x24, 0x26),   // hormigon de noche
        new Color(0x1C, 0x24, 0x2E)    // verdoso muy leve
    };

    /**
     * Cuanto se deja notar el tono de fachada.
     *
     * En una foto nocturna de verdad las fachadas son casi neutras: el color
     * lo ponen las luces, no la pared. Con la paleta a plena fuerza la ciudad
     * parecia pintada a mano, asi que el tono queda como una insinuacion.
     */
    private static final float FUERZA_TONO_FACHADA = 0.55f;

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
    /** Por encima de esta altura un edificio cuenta como torre. */
    private int alturaTorre;
    /** Si las fachadas llevan color propio en vez de ser casi negras. */
    private boolean fachadasEnColor;
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
        alturaTorre = (int) Math.round(alto * 0.14);
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

    /**
     * Conmuta las fachadas en color.
     *
     * Rehornea la silueta y el agua, porque el reflejo sale de la silueta.
     * Cuesta lo mismo que un redimensionado, unas decimas de segundo, y solo
     * al pulsarlo.
     */
    public void setFachadasEnColor(boolean valor) {
        if (fachadasEnColor == valor) {
            return;
        }
        fachadasEnColor = valor;
        if (ancho > 0 && alto > 0) {
            generarNiebla();
            skyline = crearSkyline();
            incrustarVeloNiebla();
            aguaBase = crearAgua();
        }
    }

    public boolean isFachadasEnColor() {
        return fachadasEnColor;
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
        g.setComposite(Destello.mezcla(0.14f));
        g.drawImage(domo, (ancho - dw) / 2, yHorizonte - dh / 2, dw, dh, null);

        // Un segundo domo mas estrecho concentra el resplandor sobre el centro.
        int dw2 = Math.max(2, (int) Math.round(ancho * 0.7));
        int dh2 = Math.max(2, (int) Math.round(alto * 0.26));
        g.setComposite(Destello.mezcla(0.10f));
        g.drawImage(domo, (ancho - dw2) / 2, yHorizonte - dh2 / 2, dw2, dh2, null);

        pintarMontanas(g);
        pintarLuna(g);

        g.dispose();
        return img;
    }

    /**
     * Posicion de los mares lunares, en radios desde el centro del disco.
     *
     * No son manchas al azar: son los mares de verdad, en su sitio. Es lo que
     * hace que el disco se lea como la Luna y no como una bola con pecas,
     * porque ese dibujo lo tenemos todos memorizado sin saberlo.
     * Cada fila es {x, y, radioX, radioY, oscuridad}.
     */
    private static final double[][] MARES_LUNA = {
        {-0.34, -0.34, 0.30, 0.26, 1.00},   // Imbrium
        {0.06, -0.30, 0.20, 0.18, 0.95},    // Serenitatis
        {0.26, -0.08, 0.22, 0.20, 1.00},    // Tranquillitatis
        {0.56, -0.26, 0.11, 0.10, 0.85},    // Crisium
        {0.46, 0.12, 0.15, 0.17, 0.80},     // Fecunditatis
        {0.30, 0.26, 0.11, 0.12, 0.75},     // Nectaris
        {-0.20, 0.30, 0.20, 0.14, 0.80},    // Nubium
        {-0.58, -0.02, 0.24, 0.34, 0.85},   // Procellarum
        {-0.42, 0.36, 0.13, 0.11, 0.70}     // Humorum
    };

    /** Crateres notables: {x, y, radio, brillo del reborde}. */
    private static final double[][] CRATERES_LUNA = {
        {-0.16, 0.54, 0.075, 1.00},   // Tycho, el de los rayos
        {-0.02, 0.30, 0.070, 0.75},   // Copernico
        {-0.44, 0.14, 0.050, 0.70},   // Kepler
        {0.38, 0.52, 0.055, 0.60},
        {-0.60, -0.40, 0.045, 0.55},
        {0.14, 0.62, 0.040, 0.50},
        {0.52, -0.52, 0.038, 0.45},
        {-0.30, -0.62, 0.042, 0.50},
        {0.62, 0.24, 0.035, 0.45},
        {-0.66, 0.30, 0.032, 0.40}
    };

    /** Cuanto se agranda el disco antes de reducirlo, para suavizar bordes. */
    private static final int SUPERMUESTREO_LUNA = 4;

    /**
     * Desenfoque de los mares, en tanto por uno del radio del disco.
     *
     * Generoso: un mar es una llanura de basalto que se funde con las tierras
     * altas a lo largo de decenas de kilometros. Con el contorno limpio se
     * leia como una mancha pegada encima del disco.
     */
    private static final double DESENFOQUE_MARES = 0.022;

    /**
     * Desenfoque del relieve, en tanto por uno del radio del disco.
     *
     * Mucho menor que el de los mares, y por una razon concreta: los crateres
     * miden entre 0.007 y 0.027 del radio, asi que con el sigma de los mares
     * no se suavizan, se borran. Con este se les quita el filo y siguen ahi.
     */
    private static final double DESENFOQUE_RELIEVE = 0.007;

    /** Cordilleras al fondo. Dos, para que haya lejania entre ellas. */
    private static final int CORDILLERAS = 2;

    /**
     * Sierra al fondo, detras de la ciudad.
     *
     * Va dentro del cielo horneado, y ahi esta la gracia: los fuegos se
     * dibujan despues del cielo y antes de la ciudad, asi que estallan por
     * delante de la montana y por detras de los edificios. Esa sola capa
     * intermedia da mas profundidad a la escena que cualquier degradado.
     *
     * El perfil sale por desplazamiento del punto medio, que es como se
     * generan las siluetas de montana desde siempre: se parte de una recta y
     * se va quebrando por la mitad con un desorden que decrece.
     */
    private void pintarMontanas(Graphics2D g) {
        // Semilla fija: la cordillera no tiene por que cambiar al redimensionar.
        java.util.Random rnd = new java.util.Random(0x5A17E5L);
        for (int c = 0; c < CORDILLERAS; c++) {
            double cercania = c / (double) Math.max(1, CORDILLERAS - 1);
            // La de atras es mas alta y esta mas desvaida; la de delante se
            // recorta por debajo, justo sobre los tejados.
            // Por encima de la linea de tejados: si la cumbre queda por
            // debajo, la sierra no asoma y no sirve de nada.
            double cumbre = alto * (0.30 + 0.09 * cercania);
            double falda = yHorizonte - alto * (0.02 - 0.02 * cercania);
            double aspereza = alto * (0.075 + 0.045 * cercania);

            int n = 129;
            double[] perfil = new double[n];
            perfil[0] = falda - (falda - cumbre) * (0.25 + rnd.nextDouble() * 0.35);
            perfil[n - 1] = falda - (falda - cumbre) * (0.25 + rnd.nextDouble() * 0.35);
            int paso = n - 1;
            double amplitud = aspereza;
            while (paso > 1) {
                int medio = paso / 2;
                for (int i = medio; i < n; i += paso) {
                    double media = (perfil[i - medio] + perfil[i + medio]) / 2;
                    perfil[i] = media + (rnd.nextDouble() - 0.5) * amplitud;
                }
                paso = medio;
                amplitud *= 0.56;
            }

            // Color: la montana lejana es casi el cielo, y esa es la clave de
            // que se lea como distancia y no como una mancha pegada.
            // Mas oscura que el cielo a esa altura. Con un tono parecido al
            // del cielo la sierra no se recortaba: era como si no estuviera.
            Color tono = Destello.mezclar(
                    new Color(0x10, 0x19, 0x3C), new Color(0x08, 0x0D, 0x22),
                    (float) cercania);
            g.setColor(tono);
            java.awt.Polygon sierra = new java.awt.Polygon();
            for (int i = 0; i < n; i++) {
                int x = (int) Math.round(i * (double) ancho / (n - 1));
                int y = (int) Math.round(perfil[i]);
                if (y > falda) {
                    y = (int) falda;
                }
                sierra.addPoint(x, y);
            }
            sierra.addPoint(ancho, (int) falda + 2);
            sierra.addPoint(0, (int) falda + 2);
            g.fill(sierra);

            // Nieve o luz de luna en las cumbres, muy leve: solo donde la
            // pendiente mira al cielo.
            g.setColor(Destello.alfa(Destello.mezclar(tono, LUZ_LUNA, 0.45f),
                    (int) (26 + 18 * cercania)));
            for (int i = 1; i < n; i++) {
                int x0 = (int) Math.round((i - 1) * (double) ancho / (n - 1));
                int x1 = (int) Math.round(i * (double) ancho / (n - 1));
                int y0 = (int) Math.round(perfil[i - 1]);
                int y1 = (int) Math.round(perfil[i]);
                if (y1 < y0) {   // ladera que sube hacia la derecha
                    g.fillRect(x0, y1, Math.max(1, x1 - x0),
                            Math.max(1, (int) (aspereza * 0.10)));
                }
            }
        }
    }

    private void pintarLuna(Graphics2D g) {
        double lx = ancho * 0.78;
        double ly = alto * 0.16;
        double r = Math.max(2.0, alto * 0.032);

        BufferedImage halo = Destello.de(new Color(200, 216, 255));
        int hw = Math.max(4, (int) Math.round(r * 13));
        g.setComposite(Destello.mezcla(0.30f));
        g.drawImage(halo, (int) Math.round(lx - hw / 2.0), (int) Math.round(ly - hw / 2.0), hw, hw, null);

        int hw2 = Math.max(4, (int) Math.round(r * 5));
        g.setComposite(Destello.mezcla(0.55f));
        g.drawImage(halo, (int) Math.round(lx - hw2 / 2.0), (int) Math.round(ly - hw2 / 2.0), hw2, hw2, null);

        g.setComposite(AlphaComposite.SrcOver);
        int d = Math.max(2, (int) Math.round(r * 2));
        BufferedImage disco = crearDiscoLunar(d * SUPERMUESTREO_LUNA);
        Object interp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(disco, (int) Math.round(lx - r), (int) Math.round(ly - r), d, d, null);
        if (interp != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp);
        }
    }

    /**
     * Desenfoque gaussiano sobre una capa con transparencia.
     *
     * Antes de convolucionar se pasa a alfa premultiplicado. Sin eso, el color
     * de cada pixel se promedia con el negro de los transparentes de al lado y
     * toda mancha sale con un cerco sucio: es el fallo clasico de desenfocar
     * una imagen ARGB tal cual.
     *
     * Dos pasadas de una dimension en lugar de una de dos. La gaussiana es
     * separable, asi que sale identico con 2n multiplicaciones por pixel en
     * vez de n al cuadrado.
     *
     * En los bordes se deja el pixel original (EDGE_NO_OP) en vez de rellenar
     * con cero: rellenando, el anillo exterior se vaciaria y comeria el limbo.
     */
    private static BufferedImage desenfocar(BufferedImage src, double sigma) {
        if (sigma <= 0.05) {
            return src;
        }
        int radio = (int) Math.ceil(sigma * 3);
        int n = radio * 2 + 1;
        float[] k = new float[n];
        double suma = 0;
        for (int i = 0; i < n; i++) {
            double x = i - radio;
            k[i] = (float) Math.exp(-(x * x) / (2 * sigma * sigma));
            suma += k[i];
        }
        for (int i = 0; i < n; i++) {
            k[i] /= (float) suma;
        }
        BufferedImage pre = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D gp = pre.createGraphics();
        gp.drawImage(src, 0, 0, null);
        gp.dispose();
        java.awt.image.ConvolveOp horizontal = new java.awt.image.ConvolveOp(
                new java.awt.image.Kernel(n, 1, k),
                java.awt.image.ConvolveOp.EDGE_NO_OP, null);
        java.awt.image.ConvolveOp vertical = new java.awt.image.ConvolveOp(
                new java.awt.image.Kernel(1, n, k),
                java.awt.image.ConvolveOp.EDGE_NO_OP, null);
        return vertical.filter(horizontal.filter(pre, null), null);
    }

    /**
     * Un crater: sombra a un lado, reborde iluminado al otro y suelo.
     *
     * Los tres tonos se mantienen cerca del gris de la superficie. Antes la
     * sombra era casi negra y el reborde blanco puro, y ese salto convertia
     * cada crater en un ojo pintado en vez de en un hoyo: en la Luna el
     * contraste entre un crater y el terreno es suave.
     */
    private static void dibujarCrater(Graphics2D g, double x, double y, double radio,
            double luzX, double luzY, double fuerza) {
        double desp = radio * 0.30;
        g.setColor(new Color(0x93, 0x96, 0xA2, (int) (66 * fuerza)));
        g.fill(new java.awt.geom.Ellipse2D.Double(
                x - radio - luzX * desp, y - radio - luzY * desp, radio * 2, radio * 2));
        g.setColor(new Color(0xF6, 0xF5, 0xEE, (int) (72 * fuerza)));
        g.fill(new java.awt.geom.Ellipse2D.Double(
                x - radio * 0.92 + luzX * desp, y - radio * 0.92 + luzY * desp,
                radio * 1.84, radio * 1.84));
        g.setColor(new Color(0xB4, 0xB7, 0xC0, (int) (48 * fuerza)));
        g.fill(new java.awt.geom.Ellipse2D.Double(
                x - radio * 0.62, y - radio * 0.62, radio * 1.24, radio * 1.24));
    }

    /**
     * Dibuja el disco lunar con relieve.
     *
     * Se pinta grande y se reduce al vuelco: a tamano final los crateres
     * ocupan pocos pixeles y sin supermuestreo saldrian dentados.
     *
     * El relieve sale de una sola idea repetida: cada crater es un borde claro
     * por donde da el sol y una sombra por el lado contrario. Con la luz
     * viniendo siempre del mismo sitio, el ojo reconstruye el volumen solo.
     */
    private BufferedImage crearDiscoLunar(int tam) {
        BufferedImage img = new BufferedImage(tam, tam, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double c = tam / 2.0;
        double r = c - 1;
        // El sol le da desde arriba a la derecha. Todas las sombras obedecen
        // a esta direccion: si cada crater se sombreara a su aire, el ojo lo
        // detecta enseguida y el relieve se deshace.
        double luzX = 0.55;
        double luzY = -0.62;
        // En proporcion al radio: el desenfoque tiene que valer lo mismo a
        // cualquier tamano de disco, no una cantidad fija de pixeles.
        double sigmaMares = r * DESENFOQUE_MARES;
        double sigmaRelieve = r * DESENFOQUE_RELIEVE;

        Shape disco = new java.awt.geom.Ellipse2D.Double(c - r, c - r, r * 2, r * 2);
        g.setClip(disco);

        // Base: la Luna no es blanca, es un gris calido.
        g.setColor(new Color(0xE8, 0xE6, 0xDF));
        g.fill(disco);

        // La Luna siempre es la misma: generador propio con semilla fija, para
        // que no cambie de cara en cada redimension.
        java.util.Random rnd = new java.util.Random(0x11FEL);

        // Mares: basalto oscuro. Cada uno se compone de varias elipses
        // solapadas y descentradas, no de una sola. Una elipse perfecta se lee
        // como un circulo pintado encima de una bola; los mares de verdad
        // tienen el contorno roto.
        // Cada mar se compone en su propia capa y se vuelca una sola vez. Si
        // se pintaran los lobulos directamente, donde se solapan el alfa se
        // acumularia y se verian anillos oscuros dentro del mar, justo lo que
        // delata que aquello son elipses pegadas y no una mancha de basalto.
        //
        // Todos los mares van a una misma capa para desenfocarla de una vez.
        // El basalto no tiene filo: el borde nitido era lo que hacia que se
        // leyeran como manchas pegadas encima del disco en vez de como parte
        // de la superficie.
        BufferedImage mares = new BufferedImage(tam, tam, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gms = mares.createGraphics();
        gms.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = 0; i < MARES_LUNA.length; i++) {
            double[] m = MARES_LUNA[i];
            double mx = c + m[0] * r;
            double my = c + m[1] * r;
            double rx = m[2] * r;
            double ry = m[3] * r;
            int lobulos = 4 + rnd.nextInt(3);

            // Dos capas, la de fuera algo mayor: al volcarlas una sobre otra
            // sale una orla mas clara alrededor del mar. Con una sola, el
            // borde quedaba recortado a cuchillo y se notaba el relleno.
            // Cada capa es solida por dentro, asi que los lobulos que se
            // solapan no acumulan alfa y no aparecen anillos.
            double[] escalas = {1.14, 1.0};
            float[] opacidades = {0.13f, 0.26f};
            for (int cap = 0; cap < escalas.length; cap++) {
                BufferedImage mar = new BufferedImage(tam, tam, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gm = mar.createGraphics();
                gm.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                gm.setColor(new Color(0x67, 0x6C, 0x7C));
                // Misma semilla para las dos capas: tienen que ser el mismo
                // mar, uno un poco mas gordo que el otro.
                java.util.Random rl = new java.util.Random(0x4D41A20L + i);
                for (int lb = 0; lb < lobulos; lb++) {
                    double dx = (rl.nextDouble() - 0.5) * rx * 0.85;
                    double dy = (rl.nextDouble() - 0.5) * ry * 0.85;
                    double fx = rx * (0.55 + rl.nextDouble() * 0.55) * escalas[cap];
                    double fy = ry * (0.55 + rl.nextDouble() * 0.55) * escalas[cap];
                    gm.fill(new java.awt.geom.Ellipse2D.Double(
                            mx + dx - fx, my + dy - fy, fx * 2, fy * 2));
                }
                gm.dispose();
                gms.setComposite(Destello.mezcla(opacidades[cap] * m[4]));
                gms.drawImage(mar, 0, 0, null);
            }
            gms.setComposite(AlphaComposite.SrcOver);
        }
        gms.dispose();
        g.drawImage(desenfocar(mares, sigmaMares), 0, 0, null);

        // Crateres.
        //
        // Se dibujan en una capa aparte y se compone una sola vez sobre el
        // disco. Pintandolos directamente, donde dos se solapaban el alfa se
        // acumulaba y la interseccion salia mucho mas oscura que cualquiera de
        // los dos: se veian manchas en las uniones. En capa propia, un crater
        // que pisa a otro simplemente lo tapa.
        //
        // Ademas se rechaza toda posicion que caiga sobre un crater ya puesto,
        // que es lo que de verdad deja la superficie homogenea.
        BufferedImage relieve = new BufferedImage(tam, tam, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gr = relieve.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gr.setClip(disco);

        int puestos = 0;
        double[] cx = new double[CRATERES_LUNA.length + 120];
        double[] cy = new double[cx.length];
        double[] cr = new double[cx.length];

        // Primero los notables, que tienen sitio reservado.
        for (int i = 0; i < CRATERES_LUNA.length; i++) {
            double[] k = CRATERES_LUNA[i];
            double kx = c + k[0] * r;
            double ky = c + k[1] * r;
            double kr = k[2] * r;
            dibujarCrater(gr, kx, ky, kr, luzX, luzY, k[3]);
            cx[puestos] = kx;
            cy[puestos] = ky;
            cr[puestos] = kr;
            puestos++;
        }

        // Y despues los pequenos, solo donde quepan sin pisarse.
        int intentos = 0;
        while (puestos < cx.length && intentos < 900) {
            intentos++;
            double ang = rnd.nextDouble() * Math.PI * 2;
            // Raiz cuadrada del radio: reparte por area y no amontona al centro.
            double rad = Math.sqrt(rnd.nextDouble()) * r * 0.93;
            double px = c + Math.cos(ang) * rad;
            double py = c + Math.sin(ang) * rad;
            double pr = r * (0.007 + rnd.nextDouble() * 0.020);
            boolean libre = true;
            for (int j = 0; j < puestos; j++) {
                double dx = px - cx[j];
                double dy = py - cy[j];
                double min = (pr + cr[j]) * 1.05;
                if (dx * dx + dy * dy < min * min) {
                    libre = false;
                    break;
                }
            }
            if (!libre) {
                continue;
            }
            dibujarCrater(gr, px, py, pr, luzX, luzY, 0.72);
            cx[puestos] = px;
            cy[puestos] = py;
            cr[puestos] = pr;
            puestos++;
        }

        // Rayos de Tycho: el rasgo mas visible de la Luna llena. Van en la
        // capa de relieve para que tampoco se acumulen sobre los crateres.
        double tx = c + CRATERES_LUNA[0][0] * r;
        double ty = c + CRATERES_LUNA[0][1] * r;
        gr.setStroke(new BasicStroke((float) (r * 0.012)));
        for (int i = 0; i < 11; i++) {
            double ang = i * (Math.PI * 2 / 11) + 0.4;
            double largo = r * (0.55 + 0.45 * ((i * 7 % 5) / 5.0));
            gr.setColor(new Color(255, 255, 255, 30));
            gr.drawLine((int) tx, (int) ty,
                    (int) (tx + Math.cos(ang) * largo), (int) (ty + Math.sin(ang) * largo));
        }
        gr.dispose();
        // Un crater no tiene filo: lo que se ve de el es un cambio de
        // sombreado, no un dibujo. De paso difumina los rayos de Tycho, que
        // trazados a linea limpia parecian una arana encima del disco.
        g.drawImage(desenfocar(relieve, sigmaRelieve), 0, 0, null);

        // Oscurecimiento del limbo: el borde del disco cae, y eso es lo que
        // convierte un circulo en una esfera.
        g.setPaint(new RadialGradientPaint(
                new Point2D.Double(c, c), (float) r,
                new float[]{0f, 0.70f, 0.92f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 0),
                    new Color(0x1A, 0x1C, 0x28, 60), new Color(0x12, 0x14, 0x20, 150)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(disco);

        // Terminador suave: una una de sombra en el borde de poniente. Sin
        // ella el disco se lee plano por muy bien sombreados que esten los
        // crateres; con ella, es una bola iluminada de lado.
        g.setPaint(new java.awt.GradientPaint(
                (float) (c - r), (float) c, new Color(0x0C, 0x0E, 0x18, 175),
                (float) (c - r * 0.55), (float) c, new Color(0, 0, 0, 0)));
        g.fill(disco);

        g.dispose();
        return img;
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

    /**
     * Silueta de la ciudad, horneada una vez por redimension.
     *
     * Tres capas de profundidad en vez de una fila plana de rectangulos. Lo
     * que da sensacion de ciudad y no de cartel recortado es la perspectiva
     * aerea: lo lejano se aclara y se destine porque hay aire de por medio, y
     * lo cercano se recorta oscuro contra ello. Encima, cada edificio lleva su
     * degradado vertical, su remate propio y una luz de canto tomada del
     * resplandor de la calle.
     *
     * Todo esto se paga al redimensionar, no en cada fotograma.
     */
    private BufferedImage crearSkyline() {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        List<int[]> vivas = new ArrayList<int[]>();
        List<int[]> balizas = new ArrayList<int[]>();

        // De la mas lejana a la mas cercana: las de delante tapan a las de atras.
        //
        // Las balizas y las ventanas que parpadean se pintan en vivo sobre
        // esta imagen ya compuesta, asi que una de una torre del fondo se
        // dibujaba encima del edificio que deberia ocultarla. Para saber cual
        // ha quedado tapada se anota el color que tenia su pixel justo despues
        // de dibujar SU capa; si al terminar es otro, se le puso algo delante.
        for (int capa = 0; capa < CAPAS_CIUDAD; capa++) {
            int nv = vivas.size();
            int nb = balizas.size();
            pintarCapaCiudad(g, capa, vivas, balizas);
            for (int i = nv; i < vivas.size(); i++) {
                int[] v = vivas.get(i);
                v[4] = img.getRGB(limitar(v[0] + v[2] / 2, 0, ancho - 1),
                        limitar(v[1] + v[3] / 2, 0, alto - 1));
            }
            for (int i = nb; i < balizas.size(); i++) {
                int[] b = balizas.get(i);
                b[2] = img.getRGB(limitar(b[0], 0, ancho - 1),
                        limitar(b[1], 0, alto - 1));
            }
        }

        g.dispose();
        volcarVentanas(img, vivas);
        volcarBalizas(img, balizas);
        return img;
    }

    /**
     * Una capa de profundidad.
     *
     * @param capa 0 la mas lejana. Decide altura, contraste y detalle: los
     *             edificios del fondo no llevan ventanas sueltas porque a esa
     *             distancia no se distinguirian y solo serian ruido.
     */
    private void pintarCapaCiudad(Graphics2D g, int capa, List<int[]> vivas,
            List<int[]> balizas) {
        double cercania = capa / (double) (CAPAS_CIUDAD - 1);
        double altMin = alto * (0.040 + 0.070 * cercania);
        double altMax = alto * (0.115 + 0.185 * cercania);
        double anchoMin = alto * (0.020 + 0.020 * cercania);
        double anchoMax = alto * (0.055 + 0.065 * cercania);
        // Calima de la distancia: el fondo tira a azul claro, no a negro.
        float calima = (float) (0.34 * (1 - cercania));
        int baseY = yHorizonte + (int) Math.round(alto * 0.004 * (1 - cercania));

        // Altura de planta. Con 0.014 un edificio de doscientos pixeles salia
        // con trece plantas: eso no es una torre, es un bloque de pisos. A la
        // mitad se leen veinticinco, que es lo que el ojo espera de una ciudad.
        int pisoAlto = Math.max(2, (int) Math.round(alto * 0.0072));
        int minVentana = 1;

        int x = -(int) Math.round(Azar.entre(0.0, anchoMax));
        while (x < ancho) {
            int w = (int) Math.round(Azar.entre(anchoMin, anchoMax));
            if (w < 5) {
                w = 5;
            }
            int h = (int) Math.round(Azar.entre(altMin, altMax));
            // Una de cada doce es un hito. Un perfil sin torres que sobresalgan
            // se lee como una tapia almenada, no como una ciudad.
            if (Azar.probabilidad(0.085)) {
                h = (int) Math.round(h * Azar.entre(1.30, 1.70));
                // Con tope: el multiplicador se aplica sobre una altura ya
                // alta y sin acotar salia una torre del doble que todo lo
                // demas, que no destaca sino que descuadra la escena.
                int tope = (int) (alto * 0.34);
                if (h > tope) {
                    h = tope;
                }
            }
            if (h < 8) {
                h = 8;
            }
            if (h > baseY - 2) {
                h = Math.max(4, baseY - 2);
            }
            int cima = baseY - h;

            Color base;
            if (fachadasEnColor) {
                // El color se apaga con la distancia, no solo se aclara: es lo
                // que hace la atmosfera de verdad, y evita que las tres capas
                // parezcan tres filas de cromos.
                Color tono = PALETA_FACHADAS[Azar.entre(0, PALETA_FACHADAS.length - 1)];
                base = Destello.mezclar(EDIFICIO_OSCURO, tono,
                        (float) ((0.35 + 0.55 * cercania) * FUERZA_TONO_FACHADA));
                base = Destello.mezclar(base, CALIMA_CIUDAD, calima * 0.75f);
            } else {
                base = Destello.mezclar(EDIFICIO_OSCURO, EDIFICIO_CLARO,
                        (float) Azar.entre(0.0, 1.0));
                base = Destello.mezclar(base, CALIMA_CIUDAD, calima);
                if (capa == CAPAS_CIUDAD - 1) {
                    // La primera fila se recorta casi en negro contra lo de
                    // atras: sin ese contraste las capas se funden en una mancha.
                    base = Destello.mezclar(base, EDIFICIO_OSCURO, 0.45f);
                }
            }
            // Degradado vertical: el pie recoge el resplandor de la calle y la
            // coronacion queda limpia contra el cielo. Plano se ve a carton.
            Color pie = Destello.mezclar(base, RESPLANDOR_CALLE,
                    0.22f + 0.10f * (1 - (float) cercania));
            g.setPaint(new LinearGradientPaint(
                    new Point2D.Float(0, cima), new Point2D.Float(0, baseY),
                    new float[]{0f, 0.62f, 1f},
                    new Color[]{base, base, pie}));

            // Forma del cuerpo. No todos los edificios son cajas: en una
            // ciudad como Hong Kong abundan las torres que se afilan al subir
            // y las que retranquean a media altura. En la linea del cielo esa
            // diferencia se nota mas que cualquier detalle de fachada.
            double merma = 0;
            int alturaRetranqueo = 0;
            double mermaRetranqueo = 0;
            double forma = Azar.entre(0.0, 1.0);
            if (forma < 0.28 && w > 10) {
                merma = Azar.entre(0.14, 0.38);
            } else if (forma < 0.46 && w > 14 && h > 40) {
                alturaRetranqueo = (int) Math.round(h * Azar.entre(0.35, 0.65));
                mermaRetranqueo = Azar.entre(0.16, 0.34);
            }
            Shape cuerpo = formaCuerpo(x, cima, w, h, merma, alturaRetranqueo,
                    mermaRetranqueo);
            // Todo lo que va sobre la fachada se recorta al contorno. Ir
            // corrigiendo sitio por sitio (ventanas, cara lateral, luz de
            // canto) dejaba siempre alguno fuera y aparecian luces flotando
            // junto a las torres afiladas. Con el recorte no se escapa nada.
            Shape recorteEdificio = g.getClip();
            g.clip(cuerpo);
            g.fill(cuerpo);

            // Cara lateral: un edificio visto de esquina ensena dos caras, y
            // la que no mira al observador va mas oscura. Es lo que le da
            // volumen; una fachada sola se lee como recorte de cartulina.
            g.setPaint(null);
            int anchoLado = 0;
            int ladoOscuroIzquierda = 0;
            int ladoOscuroDerecha = 0;
            if (w > 12 && Azar.probabilidad(0.55)) {
                anchoLado = (int) Math.round(w * Azar.entre(0.16, 0.32));
                boolean ladoDerecho = Azar.probabilidad(0.5);
                int xl = ladoDerecho ? x + w - anchoLado : x;
                g.setColor(Destello.mezclar(base, EDIFICIO_OSCURO, 0.42f));
                g.fillRect(xl, cima, anchoLado, h);
                // La cara de lado lleva sus propias ventanas, comprimidas y
                // mas apagadas. Sin ellas la banda oscura se lee como una
                // raya pintada encima y no como otra cara del edificio: eso
                // es lo que hacia que el volumen no convenciera.
                pintarVentanasLado(g, xl, cima, anchoLado, h, pisoAlto, cercania, calima);
                // Arista viva entre las dos caras: sin ella el cambio de tono
                // se lee como una mancha y no como un canto.
                g.setColor(Destello.alfa(
                        Destello.mezclar(base, RESPLANDOR_CALLE, 0.50f), 90));
                g.fillRect(ladoDerecho ? xl : xl + anchoLado - 1, cima, 1, h);
                if (!ladoDerecho) {
                    // Si el lado oscuro queda a la izquierda, las ventanas
                    // deben empezar despues de el.
                    ladoOscuroIzquierda = anchoLado;
                } else {
                    ladoOscuroDerecha = anchoLado;
                }
            }

            // Luz de canto en la arista exterior: la ciudad ilumina los bordes.
            g.setColor(Destello.alfa(Destello.mezclar(base, RESPLANDOR_CALLE, 0.55f),
                    (int) (70 + 60 * cercania)));
            g.fillRect(x, cima, 1, h);

            // El remate sale por encima de la cima, asi que va sin recorte.
            // Pero tiene que apoyarse en el ancho de la CIMA, no en el de la
            // base: en una torre afilada, coronarla con el ancho de abajo deja
            // agujas y maquinaria flotando fuera del edificio.
            g.setClip(recorteEdificio);
            int sangriaCima = sangriaEn(cima, h, w, cima, merma,
                    alturaRetranqueo, mermaRetranqueo);
            pintarRemate(g, x + sangriaCima, cima, w - 2 * sangriaCima, h,
                    base, cercania, balizas);

            if (capa > 0) {
                // Recortadas al cuerpo tambien: la sangria coloca bien las
                // columnas, pero el recorte es el que garantiza que ninguna
                // se salga por un caso no previsto.
                g.clip(cuerpo);
                pintarVentanas(g, x, cima, w, h, pisoAlto, minVentana,
                        vivas, cercania, calima, ladoOscuroIzquierda,
                        ladoOscuroDerecha, merma, alturaRetranqueo,
                        mermaRetranqueo);
                g.setClip(recorteEdificio);
            }
            // A veces el siguiente se mete por delante: en una ciudad los
            // edificios se tapan entre si, no van en fila india.
            x += Azar.probabilidad(0.30)
                    ? (int) Math.round(w * Azar.entre(0.45, 0.80))
                    : w + (int) Math.round(Azar.entre(-2.0, 4.0 + 4.0 * cercania));
        }
    }

    /**
     * Rellena el cuerpo del edificio con su forma.
     *
     * Un poligono en vez de un rectangulo. Es lo unico que separa una torre
     * afilada de una caja, y a esta distancia esa silueta pesa mas que
     * cualquier detalle de la fachada.
     */
    private Shape formaCuerpo(int x, int cima, int w, int h,
            double merma, int alturaRetranqueo, double mermaRetranqueo) {
        if (merma <= 0 && alturaRetranqueo <= 0) {
            return new java.awt.Rectangle(x, cima, w, h);
        }
        java.awt.Polygon cuerpo = new java.awt.Polygon();
        int base = cima + h;
        cuerpo.addPoint(x, base);
        if (alturaRetranqueo > 0) {
            int yr = base - alturaRetranqueo;
            int s = (int) Math.round(w * mermaRetranqueo * 0.5);
            cuerpo.addPoint(x, yr);
            cuerpo.addPoint(x + s, yr);
            cuerpo.addPoint(x + s, cima);
            cuerpo.addPoint(x + w - s, cima);
            cuerpo.addPoint(x + w - s, yr);
            cuerpo.addPoint(x + w, yr);
        } else {
            int s = (int) Math.round(w * merma * 0.5);
            cuerpo.addPoint(x + s, cima);
            cuerpo.addPoint(x + w - s, cima);
        }
        cuerpo.addPoint(x + w, base);
        return cuerpo;
    }

    /** Cuanto hay que meterse por cada lado a la altura dada. */
    private static int sangriaEn(int cima, int h, int w, int y,
            double merma, int alturaRetranqueo, double mermaRetranqueo) {
        if (alturaRetranqueo > 0) {
            int yr = cima + h - alturaRetranqueo;
            return y < yr ? (int) Math.round(w * mermaRetranqueo * 0.5) : 0;
        }
        if (merma <= 0) {
            return 0;
        }
        double f = (double) (y - cima) / Math.max(1, h);
        return (int) Math.round(w * merma * 0.5 * (1 - f));
    }

    /**
     * Corona el edificio.
     *
     * Cinco remates distintos en vez de una azotea plana: es lo que le da
     * silueta propia a la linea del cielo.
     *
     * @return la cota mas alta alcanzada.
     */
    private int pintarRemate(Graphics2D g, int x, int cima, int w, int h,
            Color base, double cercania, List<int[]> balizas) {
        g.setPaint(null);
        g.setColor(base);
        double d = Azar.entre(0.0, 1.0);
        int arriba = cima;

        if (d < 0.30 && w > 10) {
            // Escalonado: retranqueos, como un rascacielos clasico.
            int w2 = (int) Math.round(w * Azar.entre(0.50, 0.74));
            int h2 = (int) Math.round(h * Azar.entre(0.10, 0.24));
            if (w2 >= 4 && h2 >= 3 && cima - h2 >= 0) {
                g.fillRect(x + (w - w2) / 2, cima - h2, w2, h2);
                arriba = cima - h2;
                if (Azar.probabilidad(0.5) && w2 > 8) {
                    int w3 = (int) Math.round(w2 * 0.55);
                    int h3 = (int) Math.round(h2 * 0.7);
                    if (w3 >= 3 && arriba - h3 >= 0) {
                        g.fillRect(x + (w - w3) / 2, arriba - h3, w3, h3);
                        arriba -= h3;
                    }
                }
            }
        } else if (d < 0.45 && w > 8) {
            // Aguja.
            int ha = (int) Math.round(h * Azar.entre(0.10, 0.26));
            int xa = x + w / 2;
            for (int i = 0; i < ha && cima - i >= 0; i++) {
                int anchoAguja = Math.max(1, (int) ((1 - (double) i / ha) * w * 0.16));
                g.fillRect(xa - anchoAguja / 2, cima - i, anchoAguja, 1);
            }
            arriba = Math.max(0, cima - ha);
        } else if (d < 0.58 && w > 12) {
            // Cubierta a dos aguas: rompe la monotonia de las azoteas.
            int ht = (int) Math.round(h * Azar.entre(0.05, 0.12));
            for (int i = 0; i < ht && cima - i >= 0; i++) {
                int anchoFila = (int) (w * (1 - (double) i / ht));
                g.fillRect(x + (w - anchoFila) / 2, cima - i, Math.max(1, anchoFila), 1);
            }
            arriba = Math.max(0, cima - ht);
        } else if (d < 0.72 && w > 10) {
            // Maquinaria de cubierta: volumenes pequenos y descentrados.
            int n = Azar.entre(1, 2);
            for (int i = 0; i < n; i++) {
                int wc = Math.max(3, (int) Math.round(w * Azar.entre(0.14, 0.30)));
                int hc = Math.max(2, (int) Math.round(h * Azar.entre(0.02, 0.05)));
                int xc = x + Azar.entre(2, Math.max(3, w - wc - 2));
                if (cima - hc >= 0) {
                    g.fillRect(xc, cima - hc, wc, hc);
                }
            }
        }

        // Mastil con baliza, mas probable en lo cercano.
        if (Azar.probabilidad(0.22 + 0.20 * cercania)) {
            int hm = (int) Math.round(h * Azar.entre(0.12, 0.30));
            if (hm >= 4) {
                int xm = x + w / 2;
                int ym = Math.max(0, arriba - hm);
                if (arriba - ym > 0) {
                    g.setColor(Destello.mezclar(base, EDIFICIO_CLARO, 0.6f));
                    g.fillRect(xm, ym, Azar.probabilidad(0.5) ? 1 : 2, arriba - ym);
                    if (balizas.size() < CANDIDATAS_BALIZAS && Azar.probabilidad(0.75)) {
                        balizas.add(new int[]{xm, ym, 0});
                    }
                }
            }
        }
        return arriba;
    }

    /** Torre de oficinas: reticula apretada y luz fria. */
    private static final int TIPO_OFICINAS = 0;
    /** Vivienda: ventanas mayores, calidas y desordenadas. */
    private static final int TIPO_RESIDENCIAL = 1;
    /** Torre de cristal: bandas horizontales continuas de acristalamiento. */
    private static final int TIPO_CRISTAL = 2;
    /** Edificio a oscuras: casi sin luces, solo silueta. */
    private static final int TIPO_OSCURO = 3;

    /** Elige tipo de edificio. Los mas altos tienden a ser torres. */
    private int tipoEdificio(int h, double cercania) {
        boolean alto = h > alturaTorre;
        double d = Azar.entre(0.0, 1.0);
        if (alto) {
            if (d < 0.45) {
                return TIPO_OFICINAS;
            }
            if (d < 0.63) {
                return TIPO_CRISTAL;
            }
            return d < 0.96 ? TIPO_RESIDENCIAL : TIPO_OSCURO;
        }
        if (d < 0.46) {
            return TIPO_RESIDENCIAL;
        }
        if (d < 0.62) {
            return TIPO_OFICINAS;
        }
        return d < 0.84 ? TIPO_CRISTAL : TIPO_OSCURO;
    }

    /**
     * Ventanas encendidas.
     *
     * Cuatro tipos de edificio con tratamientos distintos. Aplicar la misma
     * reticula a todos los dejaba a todos iguales: una masa oscura con el
     * mismo picoteo, y algunos ademas atiborrados. Una ciudad de verdad mezcla
     * torres de oficinas, viviendas, fachadas de cristal y edificios que a esa
     * hora estan apagados, y esa mezcla es la que se lee como ciudad.
     */
    private void pintarVentanas(Graphics2D g, int x, int y, int w, int h,
            int pisoAlto, int minVentana, List<int[]> vivas,
            double cercania, float calima, int ladoIzq, int ladoDer,
            double merma, int alturaRetranqueo, double mermaRetranqueo) {
        g.setPaint(null);
        // La cara lateral oscura no lleva ventanas encendidas: es la que no
        // mira al observador, y iluminarla desharia el volumen.
        x += ladoIzq;
        w -= ladoIzq + ladoDer;
        if (w < 4) {
            return;
        }
        int tipo = tipoEdificio(h, cercania);
        if (tipo == TIPO_OSCURO) {
            // Unas pocas luces sueltas y poco mas: tambien hace falta que
            // algunos edificios esten a oscuras para que los demas destaquen.
            pintarLucesSueltas(g, x, y, w, h, pisoAlto, cercania, calima,
                    merma, alturaRetranqueo, mermaRetranqueo);
            return;
        }
        if (tipo == TIPO_CRISTAL) {
            pintarBandasCristal(g, x, y, w, h, pisoAlto, cercania, calima,
                    merma, alturaRetranqueo, mermaRetranqueo);
            return;
        }

        boolean oficinas = tipo == TIPO_OFICINAS;
        int margen = Math.max(1, w / 12);
        int vw = Math.max(minVentana, (int) Math.round(w * (oficinas
                ? Azar.entre(0.040, 0.070) : Azar.entre(0.090, 0.150))));
        int paso = vw + Math.max(1, (int) (vw * (oficinas
                ? Azar.entre(0.70, 1.05) : Azar.entre(1.10, 1.90))));
        int alturaPiso = oficinas ? pisoAlto : (int) Math.round(pisoAlto * 1.5);
        int vh = Math.max(minVentana, (int) Math.round(alturaPiso * Azar.entre(0.40, 0.60)));
        int columnas = (w - margen * 2) / paso;
        if (columnas < 1) {
            return;
        }
        // La vivienda enciende menos: no todo el mundo esta en casa despierto.
        // Mas encendida que antes. Una ciudad grande de noche esta iluminada,
        // no apagada con luces sueltas.
        double densidad = oficinas ? Azar.entre(0.24, 0.56) : Azar.entre(0.14, 0.34);
        boolean porPlantas = oficinas && Azar.probabilidad(0.26);

        for (int fy = y + alturaPiso; fy < y + h - vh; fy += alturaPiso) {
            boolean plantaViva = porPlantas && Azar.probabilidad(0.30);
            if (!plantaViva && Azar.probabilidad(oficinas ? 0.24 : 0.40)) {
                continue;
            }
            for (int c = 0; c < columnas; c++) {
                if (!plantaViva && !Azar.probabilidad(densidad)) {
                    continue;
                }
                // Sangria de la planta: en un edificio que se afila, el ancho
                // util mengua al subir. Sin esto las ventanas se salian del
                // contorno y quedaban luces flotando fuera del edificio.
                int sang = sangriaEn(y, h, w, fy, merma, alturaRetranqueo,
                        mermaRetranqueo);
                int vx = x + sang + margen + c * paso;
                if (vx + vw > x + w - sang - margen) {
                    continue;
                }
                if (vivas.size() < CANDIDATAS_VIVAS && Azar.probabilidad(0.02)) {
                    vivas.add(new int[]{vx, fy, vw, vh, 0});
                    continue;
                }
                g.setColor(colorVentana(oficinas, cercania, calima));
                g.fillRect(vx, fy, vw, vh);
            }
        }
    }

    /**
     * Torre de cristal: bandas horizontales de acristalamiento continuo.
     *
     * Es lo que distingue a un edificio moderno de uno de ventanas picadas, y
     * a distancia la diferencia se nota mas que ningun otro detalle.
     */
    private void pintarBandasCristal(Graphics2D g, int x, int y, int w, int h,
            int pisoAlto, double cercania, float calima,
            double merma, int alturaRetranqueo, double mermaRetranqueo) {
        int margen = Math.max(1, w / 14);
        int alturaBanda = Math.max(2, (int) Math.round(pisoAlto * 1.4));
        int grosor = Math.max(1, (int) Math.round(alturaBanda * 0.42));
        for (int fy = y + alturaBanda; fy < y + h - grosor; fy += alturaBanda) {
            if (Azar.probabilidad(0.46)) {
                continue;   // planta sin luz
            }
            // La banda no llega siempre de lado a lado: se interrumpe.
            int sang = sangriaEn(y, h, w, fy, merma, alturaRetranqueo,
                    mermaRetranqueo);
            int desde = x + sang + margen + (Azar.probabilidad(0.35)
                    ? (int) Math.round(w * Azar.entre(0.0, 0.35)) : 0);
            int hasta = x + w - sang - margen - (Azar.probabilidad(0.35)
                    ? (int) Math.round(w * Azar.entre(0.0, 0.35)) : 0);
            if (hasta - desde < 2) {
                continue;
            }
            // A media opacidad: encendidas a tope, las bandas se comian la
            // escena y la ciudad parecia un muro de persianas.
            Color c = colorVentana(true, cercania, calima * 1.2f);
            g.setColor(Destello.alfa(c, (int) (c.getAlpha() * 0.62)));
            g.fillRect(desde, fy, hasta - desde, grosor);
        }
    }

    /**
     * Ventanas de la cara lateral.
     *
     * En escorzo: columnas mas juntas y luz mas floja, porque esa cara recibe
     * menos y se ve de canto. Es lo que convierte la banda oscura en una cara
     * de verdad.
     */
    private void pintarVentanasLado(Graphics2D g, int x, int y, int w, int h,
            int pisoAlto, double cercania, float calima) {
        if (w < 3) {
            return;
        }
        int vw = Math.max(1, (int) Math.round(w * 0.16));
        int paso = vw + Math.max(1, vw);
        int columnas = Math.max(1, (w - 2) / paso);
        int vh = Math.max(1, (int) Math.round(pisoAlto * 0.45));
        for (int fy = y + pisoAlto; fy < y + h - vh; fy += pisoAlto) {
            if (Azar.probabilidad(0.62)) {
                continue;
            }
            for (int c = 0; c < columnas; c++) {
                if (!Azar.probabilidad(0.30)) {
                    continue;
                }
                int vx = x + 1 + c * paso;
                if (vx + vw > x + w - 1) {
                    continue;
                }
                Color luz = colorVentana(Azar.probabilidad(0.55), cercania, calima);
                g.setColor(Destello.alfa(luz, (int) (luz.getAlpha() * 0.55)));
                g.fillRect(vx, fy, vw, vh);
            }
        }
    }

    /** Edificio casi apagado: cuatro luces sueltas y nada mas. */
    private void pintarLucesSueltas(Graphics2D g, int x, int y, int w, int h,
            int pisoAlto, double cercania, float calima,
            double merma, int alturaRetranqueo, double mermaRetranqueo) {
        // Una o dos luces, rara vez tres. Un edificio a oscuras con cinco
        // ventanas encendidas no esta a oscuras.
        int n = Azar.probabilidad(0.12) ? 0 : Azar.entre(2, 6);
        int vw = Math.max(1, (int) Math.round(w * 0.09));
        int vh = Math.max(1, (int) Math.round(pisoAlto * 0.9));
        for (int i = 0; i < n; i++) {
            int vy = y + Azar.entre(pisoAlto, Math.max(pisoAlto + 1, h - vh));
            int sang = sangriaEn(y, h, w, vy, merma, alturaRetranqueo,
                    mermaRetranqueo);
            if (w - 2 * sang - vw - 4 < 1) {
                continue;
            }
            int vx = x + sang + Azar.entre(2, Math.max(3, w - 2 * sang - vw - 2));
            g.setColor(colorVentana(Azar.probabilidad(0.3), cercania, calima));
            g.fillRect(vx, vy, vw, vh);
        }
    }

    /**
     * Color de una ventana encendida.
     *
     * Tres temperaturas: calida de vivienda, fria de oficina y el azul de una
     * pantalla a deshora. Las oficinas tiran a fria y las viviendas a calida,
     * que es lo que de verdad se ve desde fuera.
     */
    private Color colorVentana(boolean oficinas, double cercania, float calima) {
        double t = Azar.entre(0.0, 1.0);
        Color luz;
        if (oficinas) {
            luz = t < 0.62 ? LUZ_OFICINA : (t < 0.88 ? LUZ_VENTANA : LUZ_PANTALLA);
        } else {
            luz = t < 0.74 ? LUZ_VENTANA : (t < 0.93 ? LUZ_OFICINA : LUZ_PANTALLA);
        }
        luz = Destello.mezclar(luz, CALIMA_CIUDAD, Math.min(0.85f, calima * 0.6f));
        int op = (int) (Azar.entre(120, 235) * (0.55 + 0.45 * cercania));
        return Destello.alfa(luz, op);
    }

    /**
     * Fija las ventanas que parpadean, descartando las que quedan tapadas.
     *
     * Mismo problema que las balizas: se recogen durante el horneado de las
     * tres capas pero se pintan en vivo sobre la imagen ya compuesta, asi que
     * una ventana de una torre del fondo parpadeaba encima del edificio que
     * la deberia ocultar.
     */
    private void volcarVentanas(BufferedImage capa, List<int[]> vivas) {
        List<int[]> visibles = new ArrayList<int[]>();
        for (int i = 0; i < vivas.size(); i++) {
            int[] v = vivas.get(i);
            // Se comprueba en el centro de la ventana, no en su esquina.
            if (!cambioElPixel(capa, v[0] + v[2] / 2, v[1] + v[3] / 2, v[4])) {
                visibles.add(v);
            }
        }
        // El tope, ahora si, sobre las que de verdad se ven.
        int n = Math.min(visibles.size(), MAX_VENTANAS_VIVAS);
        venX = new int[n];
        venY = new int[n];
        venW = new int[n];
        venH = new int[n];
        venFase = new double[n];
        venVel = new double[n];
        for (int i = 0; i < n; i++) {
            int[] v = visibles.get(i);
            venX[i] = v[0];
            venY[i] = v[1];
            venW[i] = v[2];
            venH[i] = v[3];
            venFase[i] = Azar.angulo();
            venVel[i] = Azar.entre(0.7, 3.4);
        }
    }

    /**
     * Cierto si la ventana ha quedado cubierta por un edificio posterior.
     *
     * Se apoya en que una ventana encendida deja pixeles claros: si donde
     * estaba ahora hay fachada oscura, es que algo se le puso delante.
     */
    private boolean tapadaPorDelante(BufferedImage capa, int x, int y) {
        if (capa == null || x < 0 || x >= ancho || y < 0 || y >= alto) {
            return true;
        }
        int px = capa.getRGB(x, y);
        int lum = ((px >> 16 & 0xFF) * 30 + (px >> 8 & 0xFF) * 59 + (px & 0xFF) * 11) / 100;
        return lum < 60;
    }

    /**
     * Fija las balizas de antena, descartando las que quedan tapadas.
     *
     * Se recogen mientras se hornean las tres capas, pero se pintan en vivo
     * encima de la imagen ya compuesta: una baliza de una torre del fondo se
     * dibujaba sobre el edificio que deberia ocultarla, y se veia el punto
     * rojo atravesando la fachada de delante.
     *
     * Para saber si esta tapada se mira el ancho de lo opaco a su alrededor:
     * un mastil tiene uno o dos pixeles y deja aire a los lados; si a seis
     * pixeles a ambos lados sigue habiendo relleno, lo que hay ahi es un
     * edificio por delante y la baliza sobra.
     */
    private void volcarBalizas(BufferedImage capa, List<int[]> balizas) {
        List<int[]> visibles = new ArrayList<int[]>();
        for (int i = 0; i < balizas.size(); i++) {
            int[] b = balizas.get(i);
            if (!cambioElPixel(capa, b[0], b[1], b[2])) {
                visibles.add(b);
            }
        }
        int n = Math.min(visibles.size(), MAX_BALIZAS);
        balX = new int[n];
        balY = new int[n];
        balFase = new double[n];
        for (int i = 0; i < n; i++) {
            int[] b = visibles.get(i);
            balX[i] = b[0];
            balY[i] = b[1];
            balFase[i] = Azar.angulo();
        }
    }

    /** Cierto si en esa cota hay fachada ancha, y no el mastil de la baliza. */
    /**
     * Cierto si el pixel dejo de ser el que era al anotarlo.
     *
     * Si cambio es porque una capa posterior pinto encima, o sea que hay un
     * edificio por delante y ese punto de luz no debe verse.
     */
    private boolean cambioElPixel(BufferedImage capa, int x, int y, int referencia) {
        if (capa == null || x < 0 || x >= ancho || y < 0 || y >= alto) {
            return true;
        }
        return capa.getRGB(x, y) != referencia;
    }

    private static int limitar(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
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
            nieAlfa[i] = (float) (0.050 + 0.095 * cercania);
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
                    Destello.alfa(NIEBLA_FRIA, 30),
                    Destello.alfa(NIEBLA_CALIDA, 66)}));
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
            // El reflejo se deshace en franjas con desplazamiento propio.
            // Volcarlo entero, aunque fuera varias veces, dejaba la ciudad
            // reconocible boca abajo; en el agua lo que hay son trazos
            // verticales rotos, y cuanto mas lejos de la orilla, mas rotos.
            int franja = 3;
            for (int d = 0; d < destAlto; d += franja) {
                int sy = yHorizonte - d;
                if (sy - franja < 0) {
                    break;
                }
                double prof = (double) d / Math.max(1, destAlto);
                // Dos ondas de periodo distinto: una sola se lee como zigzag.
                int off = (int) Math.round(
                        Math.sin(d * 0.085) * (2 + 16 * prof)
                        + Math.sin(d * 0.031 + 1.7) * (1 + 9 * prof));
                // Se estira en vertical: el reflejo se alarga con el oleaje.
                int altoDest = franja + (int) Math.round(4 * prof);
                float a = (float) (0.16 * (1 - prof * 0.75));
                g.setComposite(Destello.mezcla(a));
                g.drawImage(skyline,
                        off, d, ancho + off, d + altoDest,
                        0, sy, ancho, sy - franja, null);
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
            g.setColor(tonoEstrella(estTono[i], a));
            g.fillRect(estX[i], estY[i], estTam[i], estTam[i]);

            if (estCruz[i]) {
                g.setColor(tonoEstrella(estTono[i], a / 3));
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
            // Bilineal y no vecino mas cercano: con franjas finas el salto
            // entre ellas se veia como escalones.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int paso = 5;
            // El reflejo vivo solo se dibuja en la franja cercana a la orilla.
            // Mas abajo su opacidad ya es despreciable, pero seguia costando
            // el mismo volcado a lo ancho de la pantalla: era la mitad del
            // coste de pintar el agua a cambio de nada visible.
            int hastaD = (int) (hAgua * 0.66);
            for (int d = 0; d < hastaD; d += paso) {
                int sy = y0 - d;
                if (sy - paso < 0) {
                    break;
                }
                double prof = (double) d / hAgua;
                // Se apaga del todo al llegar al corte, para que no se vea
                // donde termina.
                float a = (float) (0.34 * (1.0 - prof) * (1.0 - d / (double) hastaD));
                if (a <= 0.012f) {
                    break;
                }
                // Dos ondas superpuestas, mas amplias con la profundidad.
                int off = (int) Math.round(
                        Math.sin(d * 0.055 + t * 2.2) * (2 + 14 * prof)
                        + Math.sin(d * 0.019 - t * 1.3) * (1 + 7 * prof));
                // Cada franja se dibuja mas alta que su origen y pisa a la
                // siguiente: eso las funde y da el estirado del reflejo.
                int altoDest = paso + 1 + (int) Math.round(3 * prof);
                g.setComposite(Destello.mezcla(a));
                g.drawImage(estelas, off, y0 + d, ancho + off, y0 + d + altoDest,
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
            g.setComposite(Destello.mezcla(a));
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
            g.setComposite(Destello.mezcla(nieAlfa[i]));
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
            g.setComposite(Destello.mezcla(0.55f * a));
            g.drawImage(halo, balX[i] - hw / 2, balY[i] - hw / 2, hw, hw, null);
            g.setComposite(Destello.mezcla(a));
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
