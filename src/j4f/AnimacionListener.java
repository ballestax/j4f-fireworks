package j4f;

import java.util.EventListener;

/**
 *
 * @author ballestas
 */
public interface AnimacionListener extends EventListener {
    public abstract void animacionStarted(AnimacionEvent animacionevent);

    public abstract void animacionStoped(AnimacionEvent animacionevent);

}
