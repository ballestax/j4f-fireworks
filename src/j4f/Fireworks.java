package j4f;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.swing.JPanel;

/**
 * Panel del espectaculo pirotecnico.
 *
 * El hilo de animacion hace todo el trabajo: actualiza la fisica, compone el
 * fotograma en un buffer fuera de pantalla y lo publica; el EDT unicamente
 * vuelca ese buffer ya terminado. Asi desaparece la carrera del disenio
 * original, donde el hilo mutaba la lista de explosiones mientras el EDT la
 * recorria para pintarla.
 *
 * @author ballestas
 */
public class Fireworks extends JPanel {

    /** Periodo objetivo: 60 fotogramas por segundo. */
    private static final long PERIODO_NS = 1000000000L / 60L;
    /** Fraccion de alfa que conserva la capa de estelas cada segundo. */
    private static final double RETENCION_ESTELA = 0.0015;
    /** Explosiones vivas simultaneas como maximo. */
    private static final int MAX_EXPLOSIONES = 26;
    /** Duracion del resplandor que un estallido derrama sobre la escena. */
    private static final double DUR_RESPLANDOR = 0.38;
    /** Intensidad del resplandor. Subirlo lo hace mas teatral y menos sutil. */
    private static final double FUERZA_RESPLANDOR = 0.20;
    /** Franjas del lavado; mas franjas, transicion mas suave. */
    private static final int BANDAS_RESPLANDOR = 12;
    /** Opacidad maxima del lavado sobre la ciudad, de 0 a 255. */
    private static final int ALFA_LAVADO = 14;
    /** Cuanto sube o baja el volumen con cada pulsacion. */
    private static final double PASO_VOLUMEN = 0.08;
    /** Segundos que permanece en pantalla el aviso de audio. */
    private static final double DUR_AVISO_VOLUMEN = 1.8;
    /** Segundos sin interaccion tras los cuales la ayuda se atenua. */
    private static final double ESPERA_AYUDA = 6.0;
    /** Color del halo del titulo, fijo: crearlo por fotograma era basura de GC. */
    private static final Color HALO_TITULO = new Color(180, 205, 255);

    private final Animacion animacion = new Animacion();
    private final Musica musica = new Musica();
    private final Escenario escenario = new Escenario();
    private final List<Explosion> explosiones = new ArrayList<Explosion>();
    /** Lanzamientos pedidos desde el EDT (raton/teclado) y drenados por el hilo. */
    private final ConcurrentLinkedQueue<Double> pendientes = new ConcurrentLinkedQueue<Double>();

    /** Capa de estelas: solo los fuegos, sobre fondo transparente. */
    private BufferedImage estelas;
    /** Pixeles de esa capa, para atenuarlos a mano. */
    private int[] pixelesEstelas;
    /** Fotograma terminado que consume el EDT. */
    private BufferedImage marcoListo;
    /** Fotograma en construccion, propiedad exclusiva del hilo de animacion. */
    private BufferedImage trasero;
    private final Object cerrojoMarco = new Object();

    private int anchoEscena;
    private int altoEscena;
    private double tiempo;
    private double proximoLanzamiento = 0.6;
    private double intro = 1.0;
    private double desdeInteraccion;
    private double fps;
    private boolean mostrarFps;

    // Resplandor que el ultimo estallido derrama sobre la ciudad y el agua.
    private double resplandor;
    private double resplandorX;
    private double resplandorY;
    private double resplandorRadio;
    private Color resplandorColor;
    private volatile boolean pantallaCompleta;
    /** Reloj de 24 horas abajo a la derecha. */
    private volatile boolean mostrarHora;
    /**
     * Modo emision: escena limpia para un directo.
     *
     * Sin ayuda, sin rotulo de entrada y sin avisos, porque en una captura de
     * pantalla todo eso queda pegado en el video. Por defecto rota de genero
     * sola: un directo de veinticuatro horas con un unico ambiente cansa, y
     * nadie va a estar pulsando la tecla al otro lado. Se puede fijar un solo
     * genero al arrancar, para un directo tematico.
     */
    private volatile boolean modoEmision;
    /** Cada cuanto cambia de genero cuando emite. */
    private static final double MINUTOS_POR_GENERO = 9.0;
    private double desdeCambioGenero;
    /**
     * Si el directo rota de genero o se queda en uno solo.
     *
     * Rotar es lo que conviene a un canal generalista, pero un directo tiene
     * a menudo un tema: una emision de solo guitarra o solo chill se anuncia
     * como tal y cambiar de ambiente cada nueve minutos la estropea. Se elige
     * al arrancar, que es cuando se sabe que se va a emitir.
     */
    private volatile boolean rotarGenero = true;

    /**
     * Cuenta atras del indicador de volumen: 1 recien tocado, 0 oculto.
     * Lo pone el EDT al pulsar una tecla y lo descuenta el hilo de animacion,
     * de ahi el volatile.
     */
    private volatile double avisoVolumen;
    /**
     * Rotulo del aviso. Si es null se muestra el volumen; si trae texto
     * (un genero, por ejemplo) manda ese texto.
     */
    private volatile String avisoEtiqueta;

    private Font fuenteAyuda;
    private Font fuenteTitulo;
    private Font fuenteDato;
    private Font fuenteHora;

    public Fireworks() {
        setOpaque(true);
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(1100, 680));
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lanzarEn(e.getX());
                despertarAyuda();
                requestFocusInWindow();
            }
        });
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /** Arranca el hilo de animacion. */
    public void iniciar() {
        animacion.start(new Runnable() {
            @Override
            public void run() {
                bucle();
            }
        });
    }

    public void detener() {
        animacion.stop();
        musica.detener();
    }

    public void alternarPausa() {
        animacion.alternarPausa();
        despertarAyuda();
    }

    /**
     * Alterna las fachadas en color.
     *
     * Rehornea la silueta, asi que se hace en un hilo aparte: en el hilo de
     * animacion daria un tiron de varias decimas justo al pulsar.
     */
    public void alternarColorCiudad() {
        final boolean valor = !escenario.isFachadasEnColor();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                escenario.setFachadasEnColor(valor);
            }
        }, "ColorCiudad");
        t.setDaemon(true);
        t.start();
        mostrarAviso(valor ? "CIUDAD EN COLOR" : "CIUDAD EN SOMBRA");
    }

    /** Muestra u oculta el reloj. */
    public void alternarHora() {
        mostrarHora = !mostrarHora;
        despertarAyuda();
    }

    public boolean isMostrarHora() {
        return mostrarHora;
    }

    public void alternarFps() {
        mostrarFps = !mostrarFps;
        despertarAyuda();
    }

    public Animacion getAnimacion() {
        return animacion;
    }

    public Musica getMusica() {
        return musica;
    }

    /**
     * Arranca la musica de fondo.
     *
     * Abrir el dispositivo MIDI tarda medio segundo largo (y bastante mas la
     * primera vez tras encender el equipo), asi que se hace en un hilo aparte:
     * en el EDT congelaria la ventana al abrirse.
     */
    public void iniciarMusica() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                musica.iniciar();
            }
        }, "ArranqueMusica");
        t.setDaemon(true);
        t.start();
    }

    public void alternarMusica() {
        musica.alternarSilencio();
        mostrarAviso(null);
    }

    public void subirVolumen() {
        musica.subirVolumen(PASO_VOLUMEN);
        mostrarAviso(null);
    }

    public void bajarVolumen() {
        musica.bajarVolumen(PASO_VOLUMEN);
        mostrarAviso(null);
    }

    /** Pasa al siguiente genero musical y lo anuncia en pantalla. */
    public void cambiarGenero() {
        Musica.Genero g = musica.siguienteGenero();
        mostrarAviso(g == null ? null : g.name());
    }

    /** Pone un genero concreto. Anuncia en pantalla salvo que se este emitiendo. */
    public void fijarGenero(Musica.Genero g) {
        if (g == null) {
            return;
        }
        musica.setGenero(g);
        if (!modoEmision) {
            mostrarAviso(g.name());
        }
    }

    /**
     * En emision, si rota de genero cada pocos minutos o se queda en uno.
     *
     * Solo afecta al cambio automatico: la tecla G sigue funcionando, que es
     * util para corregir a mano sin reiniciar el directo.
     */
    public void setRotarGenero(boolean valor) {
        rotarGenero = valor;
        desdeCambioGenero = 0;
    }

    private void mostrarAviso(String etiqueta) {
        avisoEtiqueta = etiqueta;
        avisoVolumen = 1.0;
        despertarAyuda();
    }

    /** Pide un cohete en la x indicada. Seguro desde cualquier hilo. */
    public void lanzarEn(double x) {
        pendientes.add(Double.valueOf(x));
    }

    /** Pide un cohete en una x aleatoria. */
    public void lanzarAlAzar() {
        int w = anchoEscena > 0 ? anchoEscena : getWidth();
        if (w > 0) {
            lanzarEn(Azar.entre(w * 0.08, w * 0.92));
        }
        despertarAyuda();
    }

    /** Salva de varios cohetes a la vez. */
    public void traca() {
        int w = anchoEscena > 0 ? anchoEscena : getWidth();
        if (w <= 0) {
            return;
        }
        int n = Azar.entre(5, 8);
        for (int i = 0; i < n; i++) {
            lanzarEn(Azar.entre(w * 0.06, w * 0.94));
        }
        despertarAyuda();
    }

    /** Activa el modo emision: escena limpia y rotacion de genero. */
    public void setModoEmision(boolean valor) {
        modoEmision = valor;
        if (valor) {
            intro = 0;
            avisoVolumen = 0;
            desdeCambioGenero = 0;
        }
    }

    public boolean isModoEmision() {
        return modoEmision;
    }

    public void despertarAyuda() {
        desdeInteraccion = 0;
    }

    /**
     * En ventana la ayuda se queda tenue de fondo, pero a pantalla completa
     * estorba: alli se apaga por completo y solo reaparece si se toca algo.
     */
    public void setPantallaCompleta(boolean valor) {
        pantallaCompleta = valor;
        despertarAyuda();
    }

    public boolean isPantallaCompleta() {
        return pantallaCompleta;
    }

    // ------------------------------------------------------------------
    // Bucle de animacion
    // ------------------------------------------------------------------

    private void bucle() {
        long anterior = System.nanoTime();
        int marcos = 0;
        double acumulado = 0;

        while (animacion.isEjecutando()) {
            long ahora = System.nanoTime();
            double dt = (ahora - anterior) / 1e9;
            anterior = ahora;
            // Tras un congelamiento (arrastrar la ventana, cambiar de pantalla)
            // el delta puede ser enorme; acotarlo evita saltos absurdos.
            if (dt > 0.08) {
                dt = 0.08;
            }

            int w = getWidth();
            int h = getHeight();
            if (w > 0 && h > 0) {
                ajustarTamano(w, h);

                if (animacion.isPausada()) {
                    // Un ultimo fotograma para mostrar el rotulo de pausa y a dormir.
                    publicar(w, h);
                    repaint();
                    try {
                        animacion.esperarSiPausado();
                    } catch (InterruptedException ex) {
                        break;
                    }
                    anterior = System.nanoTime();
                    continue;
                }

                actualizar(dt, w, h);
                dibujarEstelas(dt, w, h);
                publicar(w, h);
                repaint();

                marcos++;
                acumulado += dt;
                if (acumulado >= 0.5) {
                    fps = marcos / acumulado;
                    marcos = 0;
                    acumulado = 0;
                }
            }

            long espera = (ahora + PERIODO_NS) - System.nanoTime();
            if (espera > 0) {
                try {
                    Thread.sleep(espera / 1000000L, (int) (espera % 1000000L));
                } catch (InterruptedException ex) {
                    break;
                }
            }
        }
    }

    private void ajustarTamano(int w, int h) {
        if (w == anchoEscena && h == altoEscena && estelas != null) {
            return;
        }
        anchoEscena = w;
        altoEscena = h;
        estelas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        pixelesEstelas = ((DataBufferInt) estelas.getRaster().getDataBuffer()).getData();
        trasero = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        synchronized (cerrojoMarco) {
            marcoListo = null;
        }
        escenario.redimensionar(w, h);
        int base = Math.max(11, Math.min(17, h / 46));
        fuenteAyuda = new Font("Segoe UI", Font.PLAIN, base);
        fuenteDato = new Font("Segoe UI", Font.PLAIN, base - 1);
        fuenteTitulo = new Font("Segoe UI", Font.PLAIN, Math.max(28, h / 11));
        fuenteHora = new Font("Segoe UI", Font.PLAIN, Math.max(24, h / 18));
    }

    private void actualizar(double dt, int w, int h) {
        tiempo += dt;
        desdeInteraccion += dt;
        if (intro > 0) {
            intro = Math.max(0, intro - dt / 4.5);
        }
        escenario.actualizar(dt);

        // Lanzamientos pedidos a mano.
        Double x;
        while ((x = pendientes.poll()) != null) {
            if (explosiones.size() < MAX_EXPLOSIONES + 8) {
                explosiones.add(nuevoCohete(x.doubleValue(), h));
            }
        }

        // Lanzamiento automatico.
        proximoLanzamiento -= dt;
        if (proximoLanzamiento <= 0) {
            if (explosiones.size() < MAX_EXPLOSIONES) {
                explosiones.add(nuevoCohete(Azar.entre(w * 0.08, w * 0.92), h));
            }
            // De vez en cuando encadena una traca rapida.
            proximoLanzamiento = Azar.probabilidad(0.22)
                    ? Azar.entre(0.10, 0.22)
                    : Azar.entre(0.45, 1.35);
        }

        if (resplandor > 0) {
            resplandor = Math.max(0, resplandor - dt / DUR_RESPLANDOR);
        }
        if (modoEmision && rotarGenero) {
            desdeCambioGenero += dt;
            if (desdeCambioGenero >= MINUTOS_POR_GENERO * 60.0) {
                desdeCambioGenero = 0;
                musica.siguienteGenero();
            }
        }
        if (avisoVolumen > 0) {
            avisoVolumen = Math.max(0, avisoVolumen - dt / DUR_AVISO_VOLUMEN);
        }

        for (int i = explosiones.size() - 1; i >= 0; i--) {
            Explosion e = explosiones.get(i);
            e.actualizar(dt);
            if (e.consumirEstallido()) {
                encenderResplandor(e);
                // El estallido va con su sitio, no al centro: Musica le pone
                // el panorama y, sobre todo, el retardo de propagacion. El
                // destello se ve ahora y el trueno llega despues, como fuera.
                //
                // La altura hace de distancia. Lo que estalla arriba esta mas
                // lejos en la escena, y ademas es lo que de verdad se comporta
                // como lejano: llega mas tarde, mas sordo y con mas cola.
                double pan = w > 0 ? (e.getX() / w) * 2 - 1 : 0;
                double lejos = h > 0 ? 1 - e.getY() / (h * 0.5) : 0.5;
                musica.golpe(Azar.entre(0.55, 1.0), pan, lejos);
            }
            if (!e.viva()) {
                explosiones.remove(i);
            }
        }
    }

    /**
     * Un estallido no solo brilla: ilumina lo que tiene alrededor. Se guarda
     * su posicion y color para tenir la escena durante un instante.
     */
    private void encenderResplandor(Explosion e) {
        resplandor = 1.0;
        resplandorX = e.getX();
        resplandorY = e.getY();
        resplandorRadio = e.getBase() * 0.85;
        resplandorColor = e.getPrimario();
    }

    private Explosion nuevoCohete(double x, int h) {
        // Salen de detras del skyline y estallan en el tercio superior.
        double desde = escenario.getHorizonte() + h * 0.02;
        double hasta = Azar.entre(h * 0.12, h * 0.42);
        return new Explosion(x, desde, hasta, h);
    }

    /**
     * Atenua la capa de estelas y dibuja encima las chispas de este fotograma.
     * Ese desvanecimiento progresivo es lo que produce las colas luminosas.
     */
    private void dibujarEstelas(double dt, int w, int h) {
        desvanecer(dt);

        Graphics2D g = estelas.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        rapidez(g);
        for (int i = 0; i < explosiones.size(); i++) {
            explosiones.get(i).pintar(g);
        }
        g.dispose();
    }

    /**
     * Atenua la capa de estelas recorriendo el raster a mano.
     *
     * Hacerlo con un fillRect en DST_OUT costaba 14 ms a 1080p, casi todo el
     * presupuesto de un fotograma; este bucle hace lo mismo en menos de medio
     * milisegundo, sobre todo porque puede saltarse de golpe el pixel
     * transparente, que es la mayor parte del cielo.
     *
     * Ademas el restar uno garantiza que el alfa llegue a cero. Multiplicando
     * a secas se estancaba: en 8 bits, un alfa de 4 por 0.897 vuelve a
     * redondear a 4, asi que cada estallido dejaba su silueta grabada para
     * siempre y el cielo se iba llenando de manchas.
     */
    private void desvanecer(double dt) {
        int[] p = pixelesEstelas;
        if (p == null) {
            return;
        }
        // Retencion del fotograma en coma fija de 8 bits.
        int k = (int) Math.round(Math.pow(RETENCION_ESTELA, dt) * 256.0);
        if (k < 0) {
            k = 0;
        } else if (k > 256) {
            k = 256;
        }
        for (int i = 0; i < p.length; i++) {
            int px = p[i];
            if (px == 0) {
                continue;
            }
            // La capa esta premultiplicada, asi que hay que escalar tambien el
            // color; si solo se bajara el alfa, los restos virarian de tono.
            int a = ((px >>> 24) * k) >> 8;
            int r = (((px >> 16) & 0xFF) * k) >> 8;
            int v = (((px >> 8) & 0xFF) * k) >> 8;
            int b = ((px & 0xFF) * k) >> 8;
            a--;
            if (a <= 0) {
                p[i] = 0;
                continue;
            }
            if (r > a) {
                r = a;
            }
            if (v > a) {
                v = a;
            }
            if (b > a) {
                b = a;
            }
            p[i] = (a << 24) | (r << 16) | (v << 8) | b;
        }
    }

    /** Compone la escena completa y la publica para el EDT. */
    private void publicar(int w, int h) {
        renderizar(trasero, w, h);
        synchronized (cerrojoMarco) {
            BufferedImage previo = marcoListo;
            marcoListo = trasero;
            trasero = (previo != null && previo.getWidth() == w && previo.getHeight() == h)
                    ? previo
                    : new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
    }

    private void renderizar(BufferedImage destino, int w, int h) {
        Graphics2D g = destino.createGraphics();
        calidad(g);
        escenario.pintarFondo(g);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(estelas, 0, 0, null);
        // La ciudad va antes que el agua.
        //
        // Al reves, que era como estaba, el agua se pintaba cuando la ciudad
        // todavia no existia y por eso no podia reflejarla: se conformaba con
        // un reflejo horneado de la silueta, siempre igual. En este orden el
        // agua refleja el fotograma tal y como ha quedado, con los fuegos, la
        // luna y los edificios como esten iluminados en ese instante.
        //
        // El agua ocupa de la linea de horizonte hacia abajo y la ciudad de
        // ahi hacia arriba, asi que pintarla despues no tapa nada.
        escenario.pintarSkyline(g);
        escenario.pintarAgua(g, destino);
        pintarResplandor(g, w, h);
        g.setComposite(AlphaComposite.SrcOver);
        pintarInterfaz(g, w, h);
        g.dispose();
    }

    /**
     * Ajustes para la capa de estelas. Son halos borrosos: interpolar por
     * vecino mas cercano y sin antialias es indistinguible a la vista y
     * mucho mas rapido, que es lo que permite mantener 60 fps a 1080p.
     */
    private static void rapidez(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
    }

    /**
     * Derrama el fogonazo sobre la escena ya compuesta.
     *
     * Son dos brochazos muy tenues: un halo alrededor del estallido y un lavado
     * de color sobre la franja de la ciudad y el agua, que es lo que hace que
     * los edificios parezcan iluminarse un instante. Va despues del skyline
     * para que tambien alcance a los edificios, no solo al cielo.
     */
    private void pintarResplandor(Graphics2D g, int w, int h) {
        if (resplandor <= 0.001 || resplandorColor == null) {
            return;
        }
        // Caida cuadratica: el destello se apaga rapido, como el de verdad.
        double energia = resplandor * resplandor;

        double d = resplandorRadio;
        // Es una mancha difusa enorme: interpolar fino solo cuesta tiempo.
        Object interpPrevia = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setComposite(Destello.mezcla(energia * FUERZA_RESPLANDOR));
        g.drawImage(Destello.de(resplandorColor),
                (int) Math.round(resplandorX - d), (int) Math.round(resplandorY - d),
                (int) Math.round(d * 2), (int) Math.round(d * 2), null);
        if (interpPrevia != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpPrevia);
        }

        // Lavado sobre ciudad y agua: es lo que hace que los edificios acusen
        // el destello. Va en franjas de opacidad creciente porque un unico
        // fillRect dejaba un canto recto que se leia como un rectangulo
        // pegado encima; en pixeles cuesta exactamente lo mismo.
        int techo = (int) Math.round(escenario.getHorizonte() - h * 0.18);
        if (techo < 0) {
            techo = 0;
        }
        g.setComposite(AlphaComposite.SrcOver);
        double altoBanda = (h - techo) / (double) BANDAS_RESPLANDOR;
        for (int i = 0; i < BANDAS_RESPLANDOR; i++) {
            double avance = (i + 1) / (double) BANDAS_RESPLANDOR;
            int alfa = (int) Math.round(energia * ALFA_LAVADO * Math.min(1.0, avance * 1.7));
            if (alfa <= 0) {
                continue;
            }
            int y = (int) Math.round(techo + i * altoBanda);
            int alto = (int) Math.round(techo + (i + 1) * altoBanda) - y;
            if (alto <= 0) {
                continue;
            }
            g.setColor(Destello.alfa(resplandorColor, alfa));
            g.fillRect(0, y, w, alto);
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    static void calidad(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    // ------------------------------------------------------------------
    // Interfaz sobre la escena
    // ------------------------------------------------------------------

    private void pintarInterfaz(Graphics2D g, int w, int h) {
        if (modoEmision) {
            // En emision solo se permite el reloj, y solo si se pide a mano.
            pintarHora(g, w, h);
            if (animacion.isPausada()) {
                pintarPausa(g, w, h);
            }
            return;
        }
        pintarTitulo(g, w, h);
        pintarAyuda(g, w, h);
        pintarVolumen(g, w, h);
        pintarHora(g, w, h);
        if (mostrarFps) {
            // Panel de diagnostico: ademas del ritmo, por donde sale el sonido
            // y cuanto manda la red frente al generador de reglas.
            g.setFont(fuenteDato);
            g.setColor(new Color(255, 255, 255, 120));
            String s = String.format("%.0f fps   %d explosiones", fps, explosiones.size());
            int x = w - 22;
            g.drawString(s, x - g.getFontMetrics().stringWidth(s), 30);
            String s2 = musica.nombreSalida() + "   frases: " + musica.reparteFrases()
                    + "   energia " + String.format("%.2f", musica.getEnergiaVisual());
            g.drawString(s2, x - g.getFontMetrics().stringWidth(s2), 30 + g.getFontMetrics().getHeight());
        }
        if (animacion.isPausada()) {
            pintarPausa(g, w, h);
        }
    }

    /** Rotulo de entrada: aparece unos segundos y se desvanece. */
    private void pintarTitulo(Graphics2D g, int w, int h) {
        if (intro <= 0.001) {
            return;
        }
        double a = intro < 0.25 ? intro / 0.25 : 1.0;
        g.setFont(fuenteTitulo);
        FontMetrics fm = g.getFontMetrics();
        String texto = "FIREWORKS";
        double espaciado = fuenteTitulo.getSize() * 0.34;
        double ancho = 0;
        for (int i = 0; i < texto.length(); i++) {
            ancho += fm.charWidth(texto.charAt(i)) + espaciado;
        }
        ancho -= espaciado;
        double x = (w - ancho) / 2.0;
        double y = h * 0.30;

        // Halo detras del texto para que despegue del cielo.
        g.setComposite(Destello.mezcla((0.30 * a)));
        BufferedImage halo = Destello.de(HALO_TITULO);
        int hw = (int) (ancho * 0.95);
        int hh = (int) (fuenteTitulo.getSize() * 2.6);
        g.drawImage(halo, (int) (w / 2 - hw / 2), (int) (y - hh * 0.62), hw, hh, null);

        g.setComposite(Destello.mezcla(a));
        g.setColor(new Color(255, 255, 255, 232));
        double cx = x;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            g.drawString(String.valueOf(c), (float) cx, (float) y);
            cx += fm.charWidth(c) + espaciado;
        }

        g.setColor(new Color(255, 255, 255, 70));
        g.fillRect((int) (w / 2 - ancho * 0.22), (int) (y + fuenteTitulo.getSize() * 0.42),
                (int) (ancho * 0.44), 1);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private void pintarAyuda(Graphics2D g, int w, int h) {
        double a;
        if (desdeInteraccion < ESPERA_AYUDA) {
            a = 0.88;
        } else {
            // A pantalla completa baja hasta cero; en ventana queda un rastro.
            double suelo = pantallaCompleta ? 0.0 : 0.20;
            a = Math.max(suelo, 0.88 - (desdeInteraccion - ESPERA_AYUDA) * 0.5);
        }
        if (a <= 0.01) {
            return;
        }
        g.setFont(fuenteAyuda);
        FontMetrics fm = g.getFontMetrics();
        String[] teclas = {"F", "ESC", "ESPACIO", "T", "G", "M", "+/-", "C", "H", "P", "CLIC"};
        String[] textos = {"pantalla completa", "salir", "lanzar", "traca",
            "genero", "musica", "volumen", "color", "hora", "pausa", "apuntar"};

        int margen = Math.max(18, w / 50);
        int ancho = Math.max(80, w - margen * 2 - anchoHora(g));
        int alturaFila = fm.getHeight() + 9;

        // Con ocho atajos la tira ya no cabe en una ventana estrecha, asi que
        // se reparte en las filas que hagan falta y se apilan hacia arriba.
        int[] anchos = new int[teclas.length];
        for (int i = 0; i < teclas.length; i++) {
            anchos[i] = fm.stringWidth(teclas[i]) + 14 + 10
                    + fm.stringWidth(textos[i]) + 20;
        }
        int filas = 1;
        int acumulado = 0;
        for (int i = 0; i < anchos.length; i++) {
            if (acumulado > 0 && acumulado + anchos[i] > ancho) {
                filas++;
                acumulado = 0;
            }
            acumulado += anchos[i];
        }

        int base = h - Math.max(16, h / 40) - (filas - 1) * alturaFila;
        int x = margen;
        int y = base;
        for (int i = 0; i < teclas.length; i++) {
            if (x > margen && x + anchos[i] > margen + ancho) {
                x = margen;
                y += alturaFila;
            }
            int anchoTecla = fm.stringWidth(teclas[i]) + 14;
            int altoTecla = fm.getHeight() + 2;
            g.setColor(new Color(255, 255, 255, (int) (30 * a)));
            g.fillRoundRect(x, y - altoTecla + 4, anchoTecla, altoTecla, 6, 6);
            g.setColor(new Color(255, 255, 255, (int) (215 * a)));
            g.drawString(teclas[i], x + 7, y);
            x += anchoTecla + 10;
            g.setColor(new Color(226, 234, 255, (int) (150 * a)));
            g.drawString(textos[i], x, y);
            x += fm.stringWidth(textos[i]) + 20;
        }
    }

    /**
     * Indicador de volumen. Solo asoma al tocarlo y se retira solo, para no
     * ensuciar la escena; si no hay salida MIDI avisa de que no hay audio.
     */
    private void pintarVolumen(Graphics2D g, int w, int h) {
        if (avisoVolumen <= 0.001) {
            return;
        }
        // Se mantiene opaco un rato y solo se difumina al final.
        double a = avisoVolumen > 0.35 ? 1.0 : avisoVolumen / 0.35;

        g.setFont(fuenteAyuda);
        FontMetrics fm = g.getFontMetrics();
        String etiqueta;
        double nivel;
        String forzada = avisoEtiqueta;
        if (!musica.estaDisponible()) {
            etiqueta = "SIN AUDIO";
            nivel = 0;
        } else if (musica.isSilenciada()) {
            etiqueta = forzada != null ? forzada + "  -  SILENCIO" : "SILENCIO";
            nivel = 0;
        } else if (forzada != null) {
            etiqueta = forzada;
            nivel = musica.getVolumen();
        } else {
            etiqueta = "VOLUMEN " + (int) Math.round(musica.getVolumen() * 100) + "%";
            nivel = musica.getVolumen();
        }

        int anchoBarra = Math.max(120, w / 7);
        int altoBarra = Math.max(3, h / 260);
        int x = (w - anchoBarra) / 2;
        int y = (int) (h * 0.86);

        g.setColor(new Color(226, 234, 255, (int) (190 * a)));
        g.drawString(etiqueta, (w - fm.stringWidth(etiqueta)) / 2, y - altoBarra - 8);

        g.setColor(new Color(255, 255, 255, (int) (45 * a)));
        g.fillRoundRect(x, y, anchoBarra, altoBarra, altoBarra, altoBarra);
        int lleno = (int) Math.round(anchoBarra * nivel);
        if (lleno > 0) {
            g.setColor(new Color(255, 255, 255, (int) (225 * a)));
            g.fillRoundRect(x, y, lleno, altoBarra, altoBarra, altoBarra);
        }
    }

    /** Formato fijo de 24 horas, independiente de la configuracion regional. */
    private static final DateTimeFormatter RELOJ = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Sitio que hay que reservarle al reloj para que la ayuda no se le eche
     * encima al partirse en varias filas.
     */
    private int anchoHora(Graphics2D g) {
        if (!mostrarHora || fuenteHora == null) {
            return 0;
        }
        return g.getFontMetrics(fuenteHora).stringWidth("00:00:00") + 28;
    }

    /**
     * Reloj abajo a la derecha.
     *
     * Lleva una sombra suave detras: en blanco puro sobre un estallido claro
     * el texto se perderia, y la sombra lo mantiene legible sin ensuciarlo.
     */
    private void pintarHora(Graphics2D g, int w, int h) {
        if (!mostrarHora || fuenteHora == null) {
            return;
        }
        String texto = LocalTime.now().format(RELOJ);
        g.setFont(fuenteHora);
        FontMetrics fm = g.getFontMetrics();
        int x = w - Math.max(18, w / 50) - fm.stringWidth(texto);
        int y = h - Math.max(16, h / 40);

        int desplazamiento = Math.max(1, fuenteHora.getSize() / 24);
        g.setColor(new Color(0, 0, 0, 130));
        g.drawString(texto, x + desplazamiento, y + desplazamiento);
        g.setColor(Color.WHITE);
        g.drawString(texto, x, y);
    }

    private void pintarPausa(Graphics2D g, int w, int h) {
        g.setColor(new Color(3, 5, 14, 130));
        g.fillRect(0, 0, w, h);
        g.setFont(fuenteTitulo.deriveFont(fuenteTitulo.getSize() * 0.5f));
        String s = "PAUSA";
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(255, 255, 255, 225));
        g.drawString(s, (w - fm.stringWidth(s)) / 2f, h * 0.5f);
    }

    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        synchronized (cerrojoMarco) {
            if (marcoListo != null) {
                g.drawImage(marcoListo, 0, 0, null);
                return;
            }
        }
        g.setColor(new Color(3, 5, 14));
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}
