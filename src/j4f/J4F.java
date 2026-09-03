package j4f;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Aplicacion. Monta la ventana, el panel del espectaculo y los controles.
 *
 * Teclas: F alterna pantalla completa, ESC sale de pantalla completa (y cierra
 * la aplicacion si ya estaba en ventana), ESPACIO lanza un cohete, T una traca,
 * P pausa y F3 muestra el contador de fotogramas.
 *
 * Linea de ordenes: --emision para un directo, --genero &lt;nombre&gt; para
 * quedarse en un solo genero y --rotar (por defecto) para ir cambiando.
 *
 * @author ballestas
 */
public class J4F {

    private static Fireworks panel;
    private static JFrame ventana;
    private static GraphicsDevice pantalla;
    private static boolean pantallaCompleta;
    /** Geometria de la ventana antes de pasar a pantalla completa. */
    private static Rectangle geometriaPrevia;

    /** Arranque pensado para emitir: pantalla completa, limpio y sin tocar nada. */
    private static boolean modoEmision;
    /**
     * Genero fijado por linea de ordenes, o null para rotar.
     *
     * Un directo generalista gana rotando, pero uno anunciado como "guitarra
     * toda la noche" no: ahi el cambio automatico es un defecto. Se decide al
     * arrancar porque es lo unico que hay al lanzar el directo desde un script.
     */
    private static Musica.Genero generoFijo;
    /**
     * Si la pantalla completa usa el modo exclusivo del dispositivo.
     *
     * En exclusivo la ventana deja de ser una ventana normal del escritorio y
     * los capturadores no la encuentran: en OBS no aparece siquiera en la
     * lista de "captura de ventana". Por eso el modo emision usa una ventana
     * sin bordes del tamano de la pantalla, que se ve igual y si es
     * capturable. El exclusivo se queda para la tecla F en uso normal, donde
     * da algo menos de latencia, y se puede pedir con --exclusiva.
     */
    private static boolean exclusiva = true;

    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i].toLowerCase();
            if (a.equals("--emision") || a.equals("-e") || a.equals("--stream")) {
                modoEmision = true;
                // Se emite para que lo capture OBS: sin exclusiva por defecto.
                exclusiva = false;
            } else if (a.equals("--exclusiva")) {
                exclusiva = true;
            } else if (a.equals("--rotar")) {
                generoFijo = null;
            } else if (a.startsWith("--genero=")) {
                generoFijo = generoDe(a.substring("--genero=".length()));
            } else if ((a.equals("--genero") || a.equals("-g")) && i + 1 < args.length) {
                i++;
                generoFijo = generoDe(args[i].toLowerCase());
            } else if (a.equals("--ayuda") || a.equals("-h") || a.equals("--help")) {
                uso();
                return;
            }
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                construir();
            }
        });
    }

    /**
     * Traduce el nombre de genero de la linea de ordenes.
     *
     * Si no se reconoce se corta el arranque en vez de seguir con el genero
     * por defecto: un directo lanzado desde un script con el nombre mal
     * escrito emitiria durante horas el ambiente equivocado sin que nadie se
     * entere. Mas vale no arrancar.
     *
     * Se admiten unos pocos alias en ingles porque son los que salen solos al
     * escribir de memoria.
     */
    private static Musica.Genero generoDe(String nombre) {
        String n = nombre.trim().toUpperCase();
        Musica.Genero[] todos = Musica.Genero.values();
        for (int i = 0; i < todos.length; i++) {
            if (todos[i].name().equals(n)) {
                return todos[i];
            }
        }
        if (n.equals("CLASSICAL") || n.equals("CLASICO")) {
            return Musica.Genero.CLASICA;
        }
        if (n.equals("CARIBBEAN") || n.equals("SALSA")) {
            return Musica.Genero.CARIBENA;
        }
        if (n.equals("GUITAR")) {
            return Musica.Genero.GUITARRA;
        }
        if (n.equals("VIOLIN") || n.equals("CUERDA")) {
            return Musica.Genero.VIOLIN;
        }
        System.err.println("Genero desconocido: " + nombre);
        System.err.println("Disponibles: " + nombres());
        System.exit(2);
        return null;
    }

    private static String nombres() {
        StringBuilder sb = new StringBuilder();
        Musica.Genero[] todos = Musica.Genero.values();
        for (int i = 0; i < todos.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(todos[i].name().toLowerCase());
        }
        return sb.toString();
    }

    private static void uso() {
        System.out.println("Fireworks");
        System.out.println("  --emision, -e      pantalla completa y escena limpia, para emitir");
        System.out.println("  --genero <nombre>  un solo genero, sin rotacion automatica");
        System.out.println("  --rotar            cambia de genero cada 9 minutos (por defecto)");
        System.out.println("  --exclusiva        pantalla completa exclusiva (OBS no la captura)");
        System.out.println("  --ayuda, -h        esta ayuda");
        System.out.println("Generos: " + nombres());
    }

    private static void construir() {
        panel = new Fireworks();

        ventana = new JFrame("Fireworks");
        ventana.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        ventana.setLayout(new BorderLayout());
        ventana.add(panel, BorderLayout.CENTER);
        ventana.setBackground(java.awt.Color.BLACK);
        ventana.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                salir();
            }
        });

        instalarControles();
        ocultarCursorTrasInactividad();

        ventana.pack();
        ventana.setMinimumSize(new Dimension(480, 320));
        ventana.setLocationRelativeTo(null);
        pantalla = ventana.getGraphicsConfiguration().getDevice();
        ventana.setVisible(true);
        panel.requestFocusInWindow();
        panel.iniciar();
        panel.iniciarMusica();

        // Despues de iniciarMusica: antes no hay secuenciador al que mandarle
        // el cambio de timbres.
        if (generoFijo != null) {
            panel.fijarGenero(generoFijo);
        }
        panel.setRotarGenero(generoFijo == null);

        if (modoEmision) {
            // Escena limpia para el capturador: nada de rotulos ni de ventana.
            panel.setModoEmision(true);
            alternarPantallaCompleta();
        }
    }

    // ------------------------------------------------------------------
    // Controles
    // ------------------------------------------------------------------

    private static void instalarControles() {
        atajo("F", "pantallaCompleta", new Runnable() {
            @Override
            public void run() {
                alternarPantallaCompleta();
            }
        });
        atajo("ESCAPE", "escapar", new Runnable() {
            @Override
            public void run() {
                escapar();
            }
        });
        atajo("SPACE", "lanzar", new Runnable() {
            @Override
            public void run() {
                panel.lanzarAlAzar();
            }
        });
        atajo("T", "traca", new Runnable() {
            @Override
            public void run() {
                panel.traca();
            }
        });
        atajo("G", "genero", new Runnable() {
            @Override
            public void run() {
                panel.cambiarGenero();
            }
        });
        atajo("M", "musica", new Runnable() {
            @Override
            public void run() {
                panel.alternarMusica();
            }
        });
        // Varias grafias del mismo gesto: flechas, el mas y el menos de la
        // fila de numeros y los del teclado numerico.
        Runnable subir = new Runnable() {
            @Override
            public void run() {
                panel.subirVolumen();
            }
        };
        Runnable bajar = new Runnable() {
            @Override
            public void run() {
                panel.bajarVolumen();
            }
        };
        String[] teclasSubir = {"UP", "PLUS", "ADD", "EQUALS"};
        for (int i = 0; i < teclasSubir.length; i++) {
            atajo(teclasSubir[i], "subirVolumen" + i, subir);
        }
        String[] teclasBajar = {"DOWN", "MINUS", "SUBTRACT"};
        for (int i = 0; i < teclasBajar.length; i++) {
            atajo(teclasBajar[i], "bajarVolumen" + i, bajar);
        }
        atajo("C", "colorCiudad", new Runnable() {
            @Override
            public void run() {
                panel.alternarColorCiudad();
            }
        });
        atajo("H", "hora", new Runnable() {
            @Override
            public void run() {
                panel.alternarHora();
            }
        });
        atajo("P", "pausa", new Runnable() {
            @Override
            public void run() {
                panel.alternarPausa();
            }
        });
        atajo("F3", "fps", new Runnable() {
            @Override
            public void run() {
                panel.alternarFps();
            }
        });
    }

    /**
     * Registra un atajo en el ambito de la ventana entera, no del componente
     * enfocado: asi sigue funcionando despues de recrear la ventana al entrar o
     * salir de pantalla completa.
     */
    private static void atajo(String tecla, String nombre, final Runnable accion) {
        JComponent raiz = panel;
        raiz.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(tecla), nombre);
        raiz.getActionMap().put(nombre, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                accion.run();
            }
        });
    }

    /** ESC: primero abandona la pantalla completa; si ya estaba en ventana, cierra. */
    private static void escapar() {
        if (pantallaCompleta) {
            alternarPantallaCompleta();
        } else {
            salir();
        }
    }

    private static void salir() {
        if (panel != null) {
            panel.detener();
        }
        if (pantallaCompleta && pantalla != null) {
            pantalla.setFullScreenWindow(null);
        }
        if (ventana != null) {
            ventana.dispose();
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Pantalla completa
    // ------------------------------------------------------------------

    /**
     * Alterna entre ventana y pantalla completa.
     *
     * Cambiar la decoracion obliga a soltar el peer nativo, por eso hay un
     * dispose() en ambas ramas. Si el dispositivo no admite el modo exclusivo
     * se recurre a una ventana sin bordes maximizada, que da el mismo resultado
     * visual.
     */
    private static void alternarPantallaCompleta() {
        if (ventana == null) {
            return;
        }
        if (pantalla == null) {
            pantalla = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        }

        if (pantallaCompleta) {
            pantalla.setFullScreenWindow(null);
            ventana.dispose();
            ventana.setUndecorated(false);
            ventana.setExtendedState(JFrame.NORMAL);
            if (geometriaPrevia != null) {
                ventana.setBounds(geometriaPrevia);
            }
            ventana.setVisible(true);
            pantallaCompleta = false;
        } else {
            geometriaPrevia = ventana.getBounds();
            ventana.dispose();
            ventana.setUndecorated(true);
            if (exclusiva && pantalla.isFullScreenSupported()) {
                pantalla.setFullScreenWindow(ventana);
            } else {
                // Ventana sin bordes ocupando la pantalla entera. Se usan los
                // limites del dispositivo y no MAXIMIZED_BOTH porque este
                // respeta el area de trabajo y dejaria la barra de tareas a la
                // vista, que en un directo de 24 horas se ve todo el rato.
                ventana.setBounds(pantalla.getDefaultConfiguration().getBounds());
                ventana.setVisible(true);
            }
            pantallaCompleta = true;
        }

        ventana.validate();
        ventana.toFront();
        panel.requestFocusInWindow();
        panel.setPantallaCompleta(pantallaCompleta);
    }

    // ------------------------------------------------------------------

    /** El cursor estorba en un espectaculo a pantalla completa: se esconde al parar. */
    private static void ocultarCursorTrasInactividad() {
        final Cursor invisible = Toolkit.getDefaultToolkit().createCustomCursor(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "vacio");
        final javax.swing.Timer temporizador = new javax.swing.Timer(2500, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                panel.setCursor(invisible);
            }
        });
        temporizador.setRepeats(false);
        temporizador.start();

        MouseAdapter despertar = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                panel.setCursor(Cursor.getDefaultCursor());
                panel.despertarAyuda();
                temporizador.restart();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                mouseMoved(e);
            }
        };
        panel.addMouseMotionListener(despertar);
    }
}
