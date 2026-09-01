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
 * @author ballestas
 */
public class J4F {

    private static Fireworks panel;
    private static JFrame ventana;
    private static GraphicsDevice pantalla;
    private static boolean pantallaCompleta;
    /** Geometria de la ventana antes de pasar a pantalla completa. */
    private static Rectangle geometriaPrevia;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                construir();
            }
        });
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
            if (pantalla.isFullScreenSupported()) {
                pantalla.setFullScreenWindow(ventana);
            } else {
                ventana.setExtendedState(JFrame.MAXIMIZED_BOTH);
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
