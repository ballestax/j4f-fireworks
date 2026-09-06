package j4f.fx;

/**
 * Punto de entrada real del motor hibrido.
 *
 * No extiende Application a proposito. El arranque de JavaFX en modo classpath
 * (sin modulo, que es como se ejecuta aqui: ver LEEME.md en lib-fx/) rechaza
 * con "faltan los componentes de JavaFX runtime" cuando la clase principal
 * del proceso es directamente una subclase de Application cargada desde el
 * modulo sin nombre. Con una clase intermedia que solo llama a
 * Application.launch() el arranque funciona, con un aviso por stderr que no
 * afecta a nada. Es un truco conocido de JavaFX en classpath, no un capricho.
 */
public final class Lanzador {

    private Lanzador() {
    }

    public static void main(String[] args) {
        MotorFx.main(args);
    }
}
