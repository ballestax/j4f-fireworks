import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Deja listo el motor hibrido: descarga los jars de OpenJFX y compila
 * src-fx/.
 *
 * Se ejecuta con el propio Java 11 o mas moderno que va a correr el motor
 * hibrido, no con el Java 8 del proyecto:
 *
 * <pre>
 *   "C:\ruta\al\jdk17\bin\java" herramientas\PrepararMotorFx.java
 * </pre>
 *
 * Desde Java 11 un ".java" se puede ejecutar tal cual, sin compilar aparte
 * (lanzamiento de fichero fuente unico), y de ahi el truco: el mismo binario
 * que compila este fichero es el que despues compila src-fx/, asi que no hay
 * que adivinar donde esta el compilador correcto, es literalmente el que ya
 * se esta usando para correr esto.
 *
 * Nada de esto hace falta para el motor clasico. build/classes (Java 8) no se
 * toca.
 */
public class PrepararMotorFx {

    private static final String VERSION_JAVAFX = "21.0.2";
    private static final String[] MODULOS = {"javafx-base", "javafx-graphics"};

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() < 11) {
            System.err.println("Esto hay que correrlo con un Java 11 o mas moderno,");
            System.err.println("no con el Java 8 del proyecto. Version actual: "
                    + Runtime.version());
            System.exit(1);
        }

        File raiz = raizDelProyecto();
        File libFx = new File(raiz, "lib-fx");
        File srcFx = new File(raiz, "src-fx");
        File clases = new File(raiz, "build/classes");
        File clasesFx = new File(raiz, "build-fx");

        libFx.mkdirs();
        descargarJars(libFx);

        if (!clases.isDirectory()) {
            System.err.println("No existe " + clases + " todavia.");
            System.err.println("Compila primero el motor clasico (ant jar, o javac sobre src/),");
            System.err.println("el hibrido reutiliza esas clases (Fireworks, Escenario, Musica...).");
            System.exit(1);
        }

        compilar(srcFx, clases, libFx, clasesFx);

        System.out.println();
        System.out.println("Listo. Para probarlo, con el java de siempre (el 8):");
        System.out.println("  java -cp build\\classes j4f.J4F --motor javafx");
    }

    private static File raizDelProyecto() {
        // Este fichero vive en <raiz>/herramientas/. Si algun dia se copia o
        // se ejecuta desde otro sitio, mejor fallar con un mensaje claro que
        // adivinar mal y escribir en un sitio inesperado.
        File aqui = new File(PrepararMotorFx.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath());
        File herramientas = aqui.isDirectory() ? aqui : aqui.getParentFile();
        File raiz = herramientas.getName().equals("herramientas")
                ? herramientas.getParentFile() : new File(".");
        return raiz;
    }

    private static void descargarJars(File libFx) throws Exception {
        for (String modulo : MODULOS) {
            File destino = new File(libFx, modulo + "-" + VERSION_JAVAFX + "-win.jar");
            if (destino.isFile() && destino.length() > 0) {
                System.out.println("Ya esta: " + destino.getName());
                continue;
            }
            String url = "https://repo1.maven.org/maven2/org/openjfx/" + modulo + "/"
                    + VERSION_JAVAFX + "/" + modulo + "-" + VERSION_JAVAFX + "-win.jar";
            System.out.println("Descargando " + modulo + "...");
            descargar(url, destino);
            System.out.println("  " + destino.length() / 1024 + " KB");
        }
        System.out.println();
        System.out.println("Nota: estos jars son para Windows (clasificador \"win\"). En otro");
        System.out.println("sistema operativo cambia \"-win\" por \"-linux\" o \"-mac\" en la URL.");
    }

    private static void descargar(String url, File destino) throws Exception {
        InputStream in = java.net.URI.create(url).toURL().openStream();
        try {
            OutputStream out = new FileOutputStream(destino);
            try {
                byte[] buffer = new byte[64 * 1024];
                int leidos;
                while ((leidos = in.read(buffer)) > 0) {
                    out.write(buffer, 0, leidos);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void compilar(File srcFx, File clases, File libFx, File clasesFx)
            throws Exception {
        clasesFx.mkdirs();
        StringBuilder cp = new StringBuilder(clases.getAbsolutePath());
        File[] jars = libFx.listFiles();
        if (jars != null) {
            for (File jar : jars) {
                if (jar.getName().toLowerCase().endsWith(".jar")) {
                    cp.append(File.pathSeparator).append(jar.getAbsolutePath());
                }
            }
        }

        List<String> fuentes = new ArrayList<String>();
        recorrer(srcFx, fuentes);
        if (fuentes.isEmpty()) {
            System.err.println("No se ha encontrado ningun .java bajo " + srcFx);
            System.exit(1);
        }

        List<String> orden = new ArrayList<String>();
        orden.add("-encoding");
        orden.add("UTF-8");
        orden.add("-cp");
        orden.add(cp.toString());
        orden.add("-d");
        orden.add(clasesFx.getAbsolutePath());
        orden.addAll(fuentes);

        JavaCompiler compilador = ToolProvider.getSystemJavaCompiler();
        int resultado = compilador.run(null, System.out, System.err,
                orden.toArray(new String[0]));
        if (resultado != 0) {
            System.err.println("La compilacion del motor hibrido fallo.");
            System.exit(resultado);
        }
        System.out.println("Motor hibrido compilado en " + clasesFx);
    }

    private static void recorrer(File dir, List<String> fuentes) {
        File[] hijos = dir.listFiles();
        if (hijos == null) {
            return;
        }
        for (File hijo : hijos) {
            if (hijo.isDirectory()) {
                recorrer(hijo, fuentes);
            } else if (hijo.getName().endsWith(".java")) {
                fuentes.add(hijo.getAbsolutePath());
            }
        }
    }
}
