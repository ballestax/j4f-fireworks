package j4f;

import java.util.EventListener;

/**
 * Escucha los eventos generados por una {@link Animacion}.
 *
 * @author ballestas
 */
public interface AnimacionListener extends EventListener {

    /**
     * Invocado cuando la animacion ha iniciado.
     *
     * @param animacionevent evento con el origen, el codigo y la descripcion.
     */
    void animacionStarted(AnimacionEvent animacionevent);

    /**
     * Invocado cuando la animacion ha sido detenida.
     *
     * @param animacionevent evento con el origen, el codigo y la descripcion.
     */
    void animacionStoped(AnimacionEvent animacionevent);

}
