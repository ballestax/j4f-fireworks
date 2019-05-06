package j4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

/**
 *
 * @author ballestas
 */
public class Animacion {

    public Animacion() {
        listeners = new ArrayList<>();
    }

    public void start(Runnable animador) {
        this.animador = animador;
        animacion = new Thread(animador, "Animador");
        animacion.start();
        notificaListeners(new AnimacionEvent(this, 0, "Start Animacion"));
    }

    public void stop() {
        if (animacion != null) {
            animacion.interrupt();
            notificaListeners(new AnimacionEvent(this, 1, "Stop Animacion"));
        }
        animacion = null;
    }

    public void pause() {
        flag = false;
    }

    public synchronized void resume() {
        flag = true;
        animacion.notify();
    }

    public void addAnimacionListener(AnimacionListener anListener) {
        if (listeners != null) {
            listeners.add(anListener);
        }
    }

    public void removeAnimacionListener(AnimacionListener anListener) {
        if (listeners != null) {
            listeners.remove(anListener);
        }
    }

    public Collection getListeners() {
        return listeners;
    }

    private void notificaListeners(AnimacionEvent event) {
        Thread notificador = new Thread(new Runnable() {
            @Override
            public void run() {
                if (event != null) {
                    Iterator it = listeners.iterator();
                    do {
                        if (!it.hasNext()) {
                            break;
                        }
                        AnimacionListener anl = (AnimacionListener) it.next();
                        if (event.getCode() == 0) {
                            anl.animacionStarted(event);
                        } else if (event.getCode() == 1) {
                            anl.animacionStoped(event);
                        }
                    } while (true);
                }
            }
        });
        notificador.start();
    }
    private Thread animacion;
    private Collection listeners;
    private Runnable animador;
    private volatile boolean flag;
}
