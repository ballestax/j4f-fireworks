package j4f;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;

/**
 * Pequeno marco reutilizable para ejecutar el cuerpo de una animacion en un
 * hilo aparte, con soporte de pausa/reanudacion y notificacion de eventos.
 *
 * <p>El cuerpo de la animacion debe consultar {@link #isEjecutando()} en su
 * ciclo principal y llamar a {@link #esperarSiPausado()} en cada iteracion para
 * respetar la pausa.</p>
 *
 * @author ballestas
 */
public class Animacion {

    private final Collection<AnimacionListener> listeners;
    private final Object cerrojo = new Object();
    private volatile Thread animacion;
    private volatile Runnable animador;
    private volatile boolean ejecutando;
    private volatile boolean pausada;

    public Animacion() {
        listeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Inicia la animacion en un hilo demonio llamado "Animador". No hace nada
     * si ya hay una animacion en ejecucion.
     *
     * @param animador cuerpo de la animacion.
     */
    public void start(Runnable animador) {
        if (animador == null) {
            return;
        }
        synchronized (cerrojo) {
            if (ejecutando) {
                return;
            }
            this.animador = animador;
            pausada = false;
            ejecutando = true;
        }
        Thread hilo = new Thread(new Runnable() {
            @Override
            public void run() {
                Runnable cuerpo = Animacion.this.animador;
                try {
                    if (cuerpo != null) {
                        cuerpo.run();
                    }
                } finally {
                    // La animacion termino por si misma: refleja el estado real.
                    if (animacion == Thread.currentThread()) {
                        ejecutando = false;
                        synchronized (cerrojo) {
                            pausada = false;
                            cerrojo.notifyAll();
                        }
                    }
                }
            }
        }, "Animador");
        hilo.setDaemon(true);
        animacion = hilo;
        hilo.start();
        notificaListeners(new AnimacionEvent(this, AnimacionEvent.STARTED, "Start Animacion"));
    }

    /**
     * Detiene la animacion: baja la bandera, despierta al hilo si esta pausado
     * y lo interrumpe.
     */
    public void stop() {
        Thread hilo = animacion;
        ejecutando = false;
        synchronized (cerrojo) {
            pausada = false;
            cerrojo.notifyAll();
        }
        animacion = null;
        animador = null;
        if (hilo != null) {
            hilo.interrupt();
            notificaListeners(new AnimacionEvent(this, AnimacionEvent.STOPPED, "Stop Animacion"));
        }
    }

    /**
     * Pausa la animacion. El hilo queda detenido en
     * {@link #esperarSiPausado()}.
     */
    public void pause() {
        synchronized (cerrojo) {
            pausada = true;
        }
    }

    /**
     * Reanuda la animacion pausada.
     */
    public void resume() {
        synchronized (cerrojo) {
            pausada = false;
            cerrojo.notifyAll();
        }
    }

    /**
     * Alterna entre pausa y reanudacion.
     */
    public void alternarPausa() {
        if (isPausada()) {
            resume();
        } else {
            pause();
        }
    }

    /**
     * Debe llamarse desde DENTRO del ciclo de la animacion. Bloquea mientras la
     * animacion este pausada y retorna de inmediato en caso contrario.
     *
     * @throws InterruptedException si el hilo es interrumpido mientras espera.
     */
    public void esperarSiPausado() throws InterruptedException {
        synchronized (cerrojo) {
            while (pausada && ejecutando) {
                cerrojo.wait();
            }
        }
    }

    /**
     * @return true si la animacion esta activa.
     */
    public boolean isEjecutando() {
        return ejecutando;
    }

    /**
     * @return true si la animacion esta pausada.
     */
    public boolean isPausada() {
        return pausada;
    }

    public void addAnimacionListener(AnimacionListener anListener) {
        if (anListener != null) {
            listeners.add(anListener);
        }
    }

    public void removeAnimacionListener(AnimacionListener anListener) {
        if (anListener != null) {
            listeners.remove(anListener);
        }
    }

    /**
     * @return vista no modificable de los escuchas registrados.
     */
    public Collection<AnimacionListener> getListeners() {
        return Collections.unmodifiableCollection(listeners);
    }

    /**
     * Notifica el evento en el hilo de despacho de Swing, ya que los escuchas
     * suelen tocar la interfaz grafica.
     *
     * @param event evento a difundir.
     */
    private void notificaListeners(final AnimacionEvent event) {
        if (event == null || listeners.isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                for (AnimacionListener anl : listeners) {
                    try {
                        if (event.getCode() == AnimacionEvent.STARTED) {
                            anl.animacionStarted(event);
                        } else if (event.getCode() == AnimacionEvent.STOPPED) {
                            anl.animacionStoped(event);
                        }
                    } catch (RuntimeException e) {
                        // Un escucha defectuoso no debe interrumpir la notificacion.
                        System.err.println("Error notificando a " + anl + ": " + e);
                    }
                }
            }
        });
    }
}
