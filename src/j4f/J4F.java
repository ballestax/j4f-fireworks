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
import java.io.File;
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
 * quedarse en un solo genero, --rotar (por defecto) para ir cambiando, y
 * --motor java2d|javafx para elegir el motor de dibujo. Sin --motor se
 * pregunta por pantalla (salvo en --emision, que siempre usa java2d).
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

    /**
     * Motor de dibujo: "java2d" (de siempre) o "javafx" (hibrido, con los
     * edificios en 3D y luz real de las explosiones). Null si no se ha
     * decidido todavia: entonces se pregunta, salvo en emision.
     *
     * Vease MOTOR_FX.md para como esta montado el motor hibrido y por que
     * corre en un proceso aparte.
     */
    private static String motor;

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
            } else if (a.startsWith("--motor=")) {
                motor = motorDe(a.substring("--motor=".length()));
            } else if ((a.equals("--motor") || a.equals("-m")) && i + 1 < args.length) {
                i++;
                motor = motorDe(args[i].toLowerCase());
            } else if (a.equals("--ayuda") || a.equals("-h") || a.equals("--help")) {
                uso();
                return;
            }
        }

        if (motor == null) {
            // Sin --motor en la linea de ordenes: se pregunta, que es lo que
            // se pidio ("poder escoger el motor al lanzar la app"). En
            // emision no se pregunta nunca: un directo de 24 horas no puede
            // quedarse esperando un clic que nadie va a dar, y el motor
            // clasico es el que esta medido y probado para eso.
            motor = modoEmision ? "java2d" : elegirMotor();
        }

        if (motor.equals("javafx")) {
            System.exit(lanzarMotorHibrido(args));
            return;
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                construir();
            }
        });
    }

    private static String motorDe(String nombre) {
        String n = nombre.trim().toLowerCase();
        if (n.equals("java2d") || n.equals("clasico") || n.equals("classic")) {
            return "java2d";
        }
        if (n.equals("javafx") || n.equals("fx") || n.equals("hibrido") || n.equals("hybrid")) {
            return "javafx";
        }
        System.err.println("Motor desconocido: " + nombre);
        System.err.println("Disponibles: java2d, javafx");
        System.exit(2);
        return null;
    }

    /**
     * Pregunta que motor usar. Se llama antes de montar nada: por eso es
     * seguro invocar Swing aqui sin pasar por el hilo de eventos todavia.
     *
     * @return "java2d" tambien si se cierra el dialogo sin elegir: es el
     * motor de siempre, y quedarse sin abrir nada por un Escape accidental
     * seria peor que arrancar con el de toda la vida.
     */
    private static String elegirMotor() {
        Object[] opciones = {"Motor clasico (Java2D)", "Motor hibrido (JavaFX, edificios 3D)"};
        int eleccion = javax.swing.JOptionPane.showOptionDialog(null,
                "El motor clasico es el de siempre: cero dependencias, Java 8.\n"
                + "El motor hibrido anade edificios en 3D con luz real de las\n"
                + "explosiones, pero necesita un Java 11 o mas moderno instalado\n"
                + "aparte y arranca en un proceso propio (unos segundos mas).",
                "Fireworks: elegir motor",
                javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE,
                null, opciones, opciones[0]);
        return eleccion == 1 ? "javafx" : "java2d";
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

    // ------------------------------------------------------------------
    // Motor hibrido: JavaFX en un proceso aparte
    // ------------------------------------------------------------------

    /**
     * Lanza el motor hibrido como proceso hijo y espera a que termine.
     *
     * Por que un proceso aparte y no una llamada directa: esta maquina
     * virtual es Java 8 (es la que trae el proyecto, cero dependencias) y
     * OpenJFX moderno no carga ahi ni con reflexion, revienta con
     * UnsupportedClassVersionError en cuanto se toca la primera clase FX. La
     * unica forma limpia de mezclar los dos motores en el mismo lanzador es
     * que este, que sigue siendo Java 8 puro sin importar nada de javafx.*,
     * busque un Java mas moderno instalado en la maquina y le entregue el
     * trabajo.
     *
     * @return el codigo de salida del proceso hijo, para devolverlo tal cual.
     */
    private static int lanzarMotorHibrido(String[] argsOriginales) {
        File clases = new File("build/classes");
        File clasesFx = new File("build-fx");
        File libFx = new File("lib-fx");
        if (!clasesFx.isDirectory() || !hayJars(libFx)) {
            error("El motor hibrido no esta preparado todavia.\n\n"
                    + "Con un Java 11 o mas moderno instalado, una vez:\n\n"
                    + "  \"<esa carpeta>\\bin\\java\" herramientas\\PrepararMotorFx.java\n\n"
                    + "Descarga los jars de OpenJFX y compila build-fx/. Mientras tanto\n"
                    + "el motor clasico sigue funcionando igual que siempre.");
            return 1;
        }

        File javaBin = buscarJavaCompatible();
        if (javaBin == null) {
            error("El motor hibrido necesita un Java 11 o mas moderno instalado\n"
                    + "aparte (OpenJFX moderno no arranca en Java 8, que es el que trae\n"
                    + "este proyecto). No se ha encontrado ninguno en esta maquina.\n\n"
                    + "Instala un JDK 17 o superior (por ejemplo Eclipse Temurin,\n"
                    + "adoptium.net) o, si ya tienes uno en un sitio no habitual,\n"
                    + "define la variable de entorno JAVAFX_JAVA_HOME apuntando a su\n"
                    + "carpeta.\n\n"
                    + "El motor clasico no necesita nada de esto y sigue funcionando.");
            return 1;
        }

        StringBuilder cp = new StringBuilder();
        cp.append(clases.getAbsolutePath()).append(File.pathSeparator);
        cp.append(clasesFx.getAbsolutePath());
        File[] jars = libFx.listFiles();
        if (jars != null) {
            for (int i = 0; i < jars.length; i++) {
                if (jars[i].getName().toLowerCase().endsWith(".jar")) {
                    cp.append(File.pathSeparator).append(jars[i].getAbsolutePath());
                }
            }
        }

        java.util.List<String> orden = new java.util.ArrayList<String>();
        orden.add(javaBin.getAbsolutePath());
        orden.add("-cp");
        orden.add(cp.toString());
        orden.add("j4f.fx.Lanzador");
        // Se reenvian los argumentos utiles al motor hibrido (por ahora solo
        // el genero: --motor ya esta decidido y --emision/--exclusiva son
        // cosas del modo ventana de Swing que el hibrido todavia no replica).
        for (int i = 0; i < argsOriginales.length; i++) {
            String a = argsOriginales[i].toLowerCase();
            if (a.equals("--genero") || a.equals("-g") || a.startsWith("--genero=")) {
                orden.add(argsOriginales[i]);
                if (!a.startsWith("--genero=") && i + 1 < argsOriginales.length) {
                    orden.add(argsOriginales[++i]);
                }
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(orden);
            pb.inheritIO();
            Process p = pb.start();
            return p.waitFor();
        } catch (Exception e) {
            error("No se pudo arrancar el motor hibrido: " + e.getMessage());
            return 1;
        }
    }

    private static boolean hayJars(File dir) {
        File[] f = dir.listFiles();
        if (f == null) {
            return false;
        }
        for (int i = 0; i < f.length; i++) {
            if (f[i].getName().toLowerCase().endsWith(".jar")) {
                return true;
            }
        }
        return false;
    }

    private static void error(String mensaje) {
        System.err.println(mensaje);
        try {
            javax.swing.JOptionPane.showMessageDialog(null, mensaje,
                    "Fireworks: motor hibrido", javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Exception ignorado) {
            // Sin entorno grafico (por ejemplo, lanzado desde un script sin
            // consola): el mensaje por stderr de arriba ya basta.
        }
    }

    /**
     * Busca un "java" de version 11 o superior instalado en la maquina.
     *
     * Orden: la variable JAVAFX_JAVA_HOME si esta puesta (para quien ya sabe
     * donde tiene el suyo), JAVA_HOME si es lo bastante moderno, y despues
     * las carpetas donde los principales distribuidores instalan un JDK en
     * Windows por defecto. La version se lee del fichero "release" que trae
     * todo JDK moderno, sin necesidad de arrancar un proceso solo para
     * preguntarle su version.
     */
    private static File buscarJavaCompatible() {
        String override = System.getenv("JAVAFX_JAVA_HOME");
        if (override != null && override.length() > 0) {
            File candidato = javaEnCarpeta(new File(override));
            if (candidato != null) {
                return candidato;
            }
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && javaHome.length() > 0) {
            File candidato = javaCompatibleEnCarpeta(new File(javaHome));
            if (candidato != null) {
                return candidato;
            }
        }
        String[] raices = {
            "C:/Program Files/Eclipse Adoptium",
            "C:/Program Files/Java",
            "C:/Program Files/Microsoft",
            "C:/Program Files/BellSoft",
            "C:/Program Files/Zulu",
            "C:/Program Files/RedHat",
        };
        File mejor = null;
        int mejorVersion = 0;
        for (int i = 0; i < raices.length; i++) {
            File raiz = new File(raices[i]);
            File[] hijos = raiz.listFiles();
            if (hijos == null) {
                continue;
            }
            for (int j = 0; j < hijos.length; j++) {
                int v = versionDeCarpeta(hijos[j]);
                if (v >= 11 && v > mejorVersion) {
                    File candidato = javaEnCarpeta(hijos[j]);
                    if (candidato != null) {
                        mejor = candidato;
                        mejorVersion = v;
                    }
                }
            }
        }
        if (mejor != null) {
            return mejor;
        }
        // Ultimo recurso: el JDK que trae el propio editor, si esta instalado
        // con la extension de Java de VS Code. No es donde deberia vivir el
        // Java de un usuario final, pero en una maquina de desarrollo suele
        // ser el unico JDK moderno a mano, y evita un mensaje de error inutil.
        File extensiones = new File(System.getProperty("user.home"),
                ".vscode/extensions");
        File[] ext = extensiones.listFiles();
        if (ext != null) {
            for (int i = 0; i < ext.length; i++) {
                if (!ext[i].getName().startsWith("redhat.java-")) {
                    continue;
                }
                File jreDir = new File(ext[i], "jre");
                File[] jres = jreDir.listFiles();
                if (jres == null) {
                    continue;
                }
                for (int j = 0; j < jres.length; j++) {
                    int v = versionDeCarpeta(jres[j]);
                    File candidato = javaEnCarpeta(jres[j]);
                    if (v >= 11 && candidato != null) {
                        return candidato;
                    }
                }
            }
        }
        return null;
    }

    private static File javaCompatibleEnCarpeta(File carpeta) {
        return versionDeCarpeta(carpeta) >= 11 ? javaEnCarpeta(carpeta) : null;
    }

    /** @return el ejecutable de java dentro de esta carpeta de JDK, o null si no hay. */
    private static File javaEnCarpeta(File carpeta) {
        File exe = new File(carpeta, "bin/java.exe");
        if (exe.isFile()) {
            return exe;
        }
        File sh = new File(carpeta, "bin/java");
        return sh.isFile() ? sh : null;
    }

    /**
     * Version mayor de un JDK a partir de su fichero "release", o del nombre
     * de la carpeta si ese fichero no esta.
     */
    private static int versionDeCarpeta(File carpeta) {
        File release = new File(carpeta, "release");
        if (release.isFile()) {
            try {
                java.util.Properties p = new java.util.Properties();
                java.io.FileInputStream in = new java.io.FileInputStream(release);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
                String v = p.getProperty("JAVA_VERSION");
                if (v != null) {
                    int parseada = primerNumero(v.replace("\"", ""));
                    if (parseada > 0) {
                        // El formato viejo "1.8.0_502" cuenta como 8, no como 1.
                        return parseada == 1 ? 8 : parseada;
                    }
                }
            } catch (Exception ignorado) {
                // Carpeta sin fichero de version legible: se sigue por el nombre.
            }
        }
        return primerNumero(carpeta.getName());
    }

    /** Primer numero que aparece en la cadena, o 0 si no hay ninguno. */
    private static int primerNumero(String s) {
        int i = 0;
        int n = s.length();
        while (i < n && !Character.isDigit(s.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < n && Character.isDigit(s.charAt(j))) {
            j++;
        }
        if (j > i) {
            try {
                return Integer.parseInt(s.substring(i, j));
            } catch (NumberFormatException ignorado) {
                return 0;
            }
        }
        return 0;
    }

    private static void uso() {
        System.out.println("Fireworks");
        System.out.println("  --emision, -e      pantalla completa y escena limpia, para emitir");
        System.out.println("  --genero <nombre>  un solo genero, sin rotacion automatica");
        System.out.println("  --rotar            cambia de genero cada 9 minutos (por defecto)");
        System.out.println("  --exclusiva        pantalla completa exclusiva (OBS no la captura)");
        System.out.println("  --motor <nombre>   java2d (por defecto) o javafx (hibrido, 3D)");
        System.out.println("  --ayuda, -h        esta ayuda");
        System.out.println("Generos: " + nombres());
        System.out.println("Sin --motor se pregunta por pantalla, salvo en --emision (java2d).");
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
