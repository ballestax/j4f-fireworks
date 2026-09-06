package j4f.fx;

import j4f.Fireworks;
import j4f.Musica;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Point3D;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.ParallelCamera;
import javafx.scene.Scene;
import javafx.scene.SubScene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.PointLight;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.stage.Stage;

/**
 * Motor hibrido: el mismo espectaculo de siempre, con los edificios en 3D de
 * verdad y luz real de las explosiones sobre sus caras.
 *
 * <h2>Que es hibrido y que no</h2>
 *
 * Cielo, luna, agua, niebla, la ciudad plana y los propios fuegos artificiales
 * sale identico al motor clasico: es literalmente el mismo codigo,
 * {@link Fireworks#avanzarYComponer}, compuesto en una imagen y volcado como
 * fondo. Sobre ese fondo va una {@link SubScene} 3D real, con un
 * {@link Box} por edificio (la geometria que Escenario guarda desde la fase 4)
 * y una {@link PointLight} que seigue al fogonazo de cada estallido: eso es lo
 * que Java2D no puede dar, porque no tiene mezcla aditiva ni un modelo de luz,
 * solo composicion Porter-Duff.
 *
 * No hay sombras proyectadas ni reflejos 3D: JavaFX no las da sin escribir
 * shaders propios, y eso queda fuera de esta primera version. Tampoco hay
 * camara movil; los edificios estan alineados en pixeles exactos con el fondo
 * 2D mediante una camara paralela (ortografica), que es lo que permite mezclar
 * las dos capas sin que se note la costura.
 *
 * <h2>Por que un proceso aparte</h2>
 *
 * El motor clasico corre en Java 8 sin depender de nada. JavaFX moderno
 * (OpenJFX 21) ya no viene con ningun JDK y no carga en una maquina virtual
 * de Java 8. Por eso {@code j4f.J4F} no importa nada de aqui: cuando se pide
 * el motor hibrido, lanza este codigo como un proceso hijo con un Java 11+
 * que encuentre en la maquina. Vease J4F.lanzarMotorHibrido().
 */
public final class MotorFx extends Application {

    /** Igual que en Fireworks.bucle(): un salto de reloj enorme no debe verse. */
    private static final double DT_MAXIMO = 0.08;

    /**
     * Cuanto se acerca la ciudad al ojo entre capa y capa, en pixeles de
     * profundidad. Con la camara paralela esto no cambia el tamano en
     * pantalla de nada (es ortografica, no en perspectiva); solo ordena el
     * z-buffer para que la capa de delante tape a la de detras, exactamente
     * como ya hace el volcado plano de Escenario.
     */
    private static final double PASO_CAPA = 36.0;

    /** Color base de un edificio sin luz encima: casi negro, con algo de azul. */
    private static final Color TONO_EDIFICIO = Color.rgb(0x10, 0x14, 0x22);

    private final Fireworks modelo = new Fireworks();

    private BufferedImage fondoAwt;
    private int[] fondoArgb;
    private WritableImage fondoFx;
    private ImageView vistaFondo;

    private SubScene subEscena3D;
    private Group edificios3D;
    private PointLight luzFogonazo;
    private int anchoBox = -1;
    private int altoBox = -1;

    private Stage stage;
    private long anteriorNs = -1;

    public static void main(String[] args) {
        // Sin esto, un JPanel construido fuera del hilo de Swing (aqui vive
        // en el hilo de JavaFX) puede intentar tocar la pantalla real y
        // fallar en una maquina sin cabeza. El motor clasico ya usa el mismo
        // truco en sus bancos de pruebas (Ritmo, Foto, Perfil): el JPanel
        // nunca se anade a una ventana, solo sirve de modelo y compositor.
        System.setProperty("java.awt.headless", "true");
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("Fireworks - motor hibrido");

        int w = 1280;
        int h = 720;

        vistaFondo = new ImageView();
        vistaFondo.setSmooth(false);
        prepararFondo(w, h);

        edificios3D = new Group();
        luzFogonazo = new PointLight(Color.TRANSPARENT);
        luzFogonazo.setTranslateZ(-260);
        AmbientLight ambiente = new AmbientLight(Color.rgb(0x30, 0x36, 0x4a, 0.55));
        // Luz de luna: fija, tenue, un poco azulada, para que los edificios no
        // se lean como cajas negras cuando no hay fogonazo.
        PointLight luna = new PointLight(Color.rgb(0xC8, 0xD8, 0xFF, 0.28));
        luna.setTranslateX(w * 0.78);
        luna.setTranslateY(-h * 0.3);
        luna.setTranslateZ(-600);
        Group raiz3D = new Group(ambiente, luna, luzFogonazo, edificios3D);

        subEscena3D = new SubScene(raiz3D, w, h, true, SceneAntialiasing.BALANCED);
        subEscena3D.setFill(Color.TRANSPARENT);
        ParallelCamera camara = new ParallelCamera();
        subEscena3D.setCamera(camara);

        StackPane raiz = new StackPane(vistaFondo, subEscena3D);
        Scene escena = new Scene(raiz, w, h, Color.BLACK);
        escena.setOnKeyPressed(e -> manejarTecla(e.getCode()));

        stage.setScene(escena);
        stage.show();

        modelo.iniciarMusica();

        // El tamano real llega en el primer pulso del layout, no antes: el
        // Stage puede decorarse o Windows puede recortar la ventana al mostrarla.
        escena.widthProperty().addListener((o, a, b) -> marcarRedimension());
        escena.heightProperty().addListener((o, a, b) -> marcarRedimension());

        AnimationTimer bucle = new AnimationTimer() {
            @Override
            public void handle(long ahoraNs) {
                paso(ahoraNs);
            }
        };
        bucle.start();
    }

    private volatile boolean pendienteRedimension;

    private void marcarRedimension() {
        pendienteRedimension = true;
    }

    private void paso(long ahoraNs) {
        if (anteriorNs < 0) {
            anteriorNs = ahoraNs;
            return;
        }
        double dt = (ahoraNs - anteriorNs) / 1e9;
        anteriorNs = ahoraNs;
        if (dt > DT_MAXIMO) {
            dt = DT_MAXIMO;
        }
        if (dt <= 0) {
            return;
        }

        int w = (int) Math.round(subEscena3D.getScene() == null
                ? stage.getWidth() : subEscena3D.getScene().getWidth());
        int h = (int) Math.round(subEscena3D.getScene() == null
                ? stage.getHeight() : subEscena3D.getScene().getHeight());
        if (w <= 0 || h <= 0) {
            return;
        }
        if (pendienteRedimension || fondoAwt == null
                || fondoAwt.getWidth() != w || fondoAwt.getHeight() != h) {
            prepararFondo(w, h);
            subEscena3D.setWidth(w);
            subEscena3D.setHeight(h);
            pendienteRedimension = false;
        }

        modelo.avanzarYComponer(dt, fondoAwt, w, h);
        volcarFondo(w, h);
        actualizarEdificios3D(w, h);
        actualizarLuzFogonazo();
    }

    private void prepararFondo(int w, int h) {
        fondoAwt = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        fondoArgb = ((DataBufferInt) fondoAwt.getRaster().getDataBuffer()).getData();
        fondoFx = new WritableImage(w, h);
        vistaFondo.setImage(fondoFx);
        vistaFondo.setFitWidth(w);
        vistaFondo.setFitHeight(h);
    }

    /**
     * Copia el fotograma AWT a la imagen de JavaFX.
     *
     * TYPE_INT_RGB guarda 0x00RRGGBB; PixelFormat.getIntArgbInstance() de
     * JavaFX espera 0xAARRGGBB. Es el mismo array reescrito con el bit de
     * alfa puesto, sin asignar memoria nueva por fotograma.
     */
    private void volcarFondo(int w, int h) {
        int n = w * h;
        for (int i = 0; i < n; i++) {
            fondoArgb[i] |= 0xFF000000;
        }
        fondoFx.getPixelWriter().setPixels(0, 0, w, h,
                PixelFormat.getIntArgbInstance(), fondoArgb, 0, w);
    }

    /**
     * Reconstruye los volumenes de los edificios cuando cambia el tamano o el
     * numero de edificios (redimension real, rehorneado del skyline).
     */
    private void actualizarEdificios3D(int w, int h) {
        List<int[]> filas = modelo.getGeometriaEdificios();
        if (w == anchoBox && h == altoBox && edificios3D.getChildren().size() == filas.size()) {
            return;
        }
        anchoBox = w;
        altoBox = h;
        edificios3D.getChildren().clear();
        PhongMaterial material = new PhongMaterial(TONO_EDIFICIO);
        material.setSpecularColor(Color.rgb(0x60, 0x68, 0x80));
        material.setSpecularPower(24);
        for (int i = 0; i < filas.size(); i++) {
            int[] f = filas.get(i);
            int x = f[0];
            int cima = f[1];
            int bw = f[2];
            int bh = f[3];
            int capa = f[4];
            if (bw <= 0 || bh <= 0) {
                continue;
            }
            Box caja = new Box(bw, bh, Math.max(6, bw * 0.5));
            caja.setMaterial(material);
            caja.setTranslateX(x + bw / 2.0);
            caja.setTranslateY(cima + bh / 2.0);
            caja.setTranslateZ(-capa * PASO_CAPA);
            edificios3D.getChildren().add(caja);
        }
    }

    /** Mueve la luz del fogonazo a donde esta el estallido y la apaga cuando pasa. */
    private void actualizarLuzFogonazo() {
        double energia = modelo.getEnergiaResplandor();
        java.awt.Color c = modelo.getResplandorColor();
        if (energia <= 0.003 || c == null) {
            luzFogonazo.setColor(Color.TRANSPARENT);
            return;
        }
        luzFogonazo.setTranslateX(modelo.getResplandorX());
        luzFogonazo.setTranslateY(modelo.getResplandorY());
        double e = energia > 1 ? 1 : energia;
        luzFogonazo.setColor(Color.rgb(c.getRed(), c.getGreen(), c.getBlue(), 1.0)
                .deriveColor(0, 1, 0.4 + 1.6 * e, 1));
    }

    private void manejarTecla(KeyCode k) {
        switch (k) {
            case F:
            case F11:
                stage.setFullScreen(!stage.isFullScreen());
                break;
            case ESCAPE:
                if (stage.isFullScreen()) {
                    stage.setFullScreen(false);
                } else {
                    modelo.detener();
                    Platform.exit();
                }
                break;
            case SPACE:
                modelo.lanzarAlAzar();
                break;
            case T:
                modelo.traca();
                break;
            case G:
                modelo.cambiarGenero();
                break;
            case M:
                modelo.alternarMusica();
                break;
            case UP:
            case ADD:
            case PLUS:
            case EQUALS:
                modelo.subirVolumen();
                break;
            case DOWN:
            case SUBTRACT:
            case MINUS:
                modelo.bajarVolumen();
                break;
            default:
                break;
        }
    }
}
