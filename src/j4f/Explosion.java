package j4f;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Un cohete completo: ascenso con estela, estallido y rescoldos.
 *
 * Todas las velocidades y gravedades se escalan con el alto de la escena, de
 * modo que el espectaculo se ve identico a cualquier resolucion.
 *
 * La version original nunca terminaba: dejaba un drawOval rojo permanente en la
 * posicion del cohete y jamas se declaraba muerta, asi que la lista de
 * explosiones crecia sin limite. Aqui viva() acaba devolviendo false y quien
 * anima puede descartarla.
 *
 * @author ballestas
 */
public class Explosion {

    /** Familias de estallido disponibles. */
    public enum Forma {
        ESFERA, ANILLO, CRISANTEMO, SAUCE, PALMERA, CROSETA, DOBLE
    }

    /** Intervalo entre chispas de estela, en segundos. */
    private static final double PASO_ESTELA = 0.012;
    /** Duracion del fogonazo, en segundos. */
    private static final double DUR_FOGONAZO = 0.16;

    private double x;
    private double y;
    private double vx;
    private double vy;

    private final double yObjetivo;
    private final int alto;
    /** Gravedad del cohete durante el ascenso, en px/s^2. */
    private final double gCohete;
    /** Velocidad de referencia del estallido. */
    private final double base;
    /** Radio de referencia de cada chispa. */
    private final double r;

    private Forma forma;
    private Color primario;
    private Color secundario;
    private Color calido;

    private boolean ascendiendo;
    private boolean haExplotado;
    /** Bandera de un solo uso: avisa del estallido a quien anima. */
    private boolean estallidoSinConsumir;

    private final List<Esquirla> esquirlas;
    private double acumEstela;
    /** Fogonazo: 1 al estallar, 0 cuando ya se apago. */
    private double fogonazo;

    public Explosion(double x, double yInicio, double yObjetivo, int alto) {
        this.x = x;
        this.y = yInicio;
        this.yObjetivo = yObjetivo;
        this.alto = alto;

        this.gCohete = 0.55 * alto;
        this.base = alto * 0.34;
        this.r = alto / 400.0;

        // Velocidad inicial justa para llegar al objetivo y quedarse sin impulso.
        this.vy = -Math.sqrt(2 * gCohete * Math.max(1, (yInicio - yObjetivo)));
        this.vx = Azar.entre(-0.02, 0.02) * alto;

        Forma[] formas = Forma.values();
        this.forma = formas[Azar.entre(0, formas.length - 1)];

        float h = Azar.matiz();
        this.primario = Azar.hsb(h, (float) Azar.entre(0.75, 1.0), 1f);
        this.secundario = Azar.hsb(h + (float) Azar.entre(0.25, 0.55), 0.9f, 1f);
        this.calido = Azar.hsb((float) Azar.entre(0.08, 0.13), 0.85f, 1f);

        this.ascendiendo = true;
        this.haExplotado = false;
        this.esquirlas = new ArrayList<Esquirla>();
        this.acumEstela = 0;
        this.fogonazo = 0;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public Forma getForma() {
        return forma;
    }

    /** Se fija antes de lanzar, para funciones guionizadas. */
    public void setForma(Forma f) {
        if (f != null) {
            this.forma = f;
        }
    }

    public boolean haExplotado() {
        return haExplotado;
    }

    /**
     * Devuelve true una sola vez, en el fotograma del estallido. Sirve para
     * disparar el golpe de sonido y el resplandor sobre la escena sin que
     * quien anima tenga que recordar el estado anterior de cada cohete.
     */
    public boolean consumirEstallido() {
        if (!estallidoSinConsumir) {
            return false;
        }
        estallidoSinConsumir = false;
        return true;
    }

    /** Color dominante del estallido. */
    public Color getPrimario() {
        return primario;
    }

    /** Velocidad de referencia del estallido, proporcional al tamano. */
    public double getBase() {
        return base;
    }

    /** Mientras siga subiendo, queden brasas o no se haya apagado el fogonazo. */
    public boolean viva() {
        return ascendiendo || !esquirlas.isEmpty() || fogonazo > 0;
    }

    public void actualizar(double dt) {
        if (dt <= 0) {
            return;
        }
        if (ascendiendo) {
            vy += gCohete * dt;
            x += vx * dt;
            y += vy * dt;

            // Estela por acumulador: el numero de chispas depende del tiempo,
            // no de cuantos fotogramas haya dado la maquina.
            acumEstela += dt;
            while (acumEstela >= PASO_ESTELA) {
                acumEstela -= PASO_ESTELA;
                emitirEstela();
            }
            if (y <= yObjetivo || vy >= -alto * 0.05) {
                estallar();
            }
        }

        // Nunca se toca la lista mientras se recorre: las hijas van aparte.
        List<Esquirla> nuevas = null;
        for (int i = esquirlas.size() - 1; i >= 0; i--) {
            Esquirla e = esquirlas.get(i);
            e.actualizar(dt);
            if (e.debeDividirse()) {
                if (nuevas == null) {
                    nuevas = new ArrayList<Esquirla>();
                }
                dividir(e, nuevas);
                e.consumirDivision();
            }
            if (!e.viva()) {
                esquirlas.remove(i);
            }
        }
        if (nuevas != null) {
            esquirlas.addAll(nuevas);
        }

        if (fogonazo > 0) {
            fogonazo -= dt / DUR_FOGONAZO;
            if (fogonazo < 0) {
                fogonazo = 0;
            }
        }
    }

    /** Chispita dorada que queda flotando detras del cohete. */
    private void emitirEstela() {
        Color c = Azar.hsb((float) Azar.entre(0.08, 0.13), 0.9f, 1f);
        double dvx = Azar.gauss() * alto * 0.012 - vx * 0.18;
        double dvy = Azar.gauss() * alto * 0.012 - vy * 0.18;
        Esquirla e = new Esquirla(x, y, dvx, dvy, c,
                r * Azar.entre(0.55, 0.9),
                Azar.entre(3.2, 3.8),
                0.10 * alto,
                0.25);
        e.setCentella(Azar.probabilidad(0.35));
        esquirlas.add(e);
    }

    private void estallar() {
        ascendiendo = false;
        haExplotado = true;
        estallidoSinConsumir = true;
        fogonazo = 1;
        acumEstela = 0;

        switch (forma) {
            case ANILLO:
                crearAnillo();
                break;
            case CRISANTEMO:
                crearCrisantemo();
                break;
            case SAUCE:
                crearSauce();
                break;
            case PALMERA:
                crearPalmera();
                break;
            case CROSETA:
                crearCroseta();
                break;
            case DOBLE:
                crearEsferica(true);
                break;
            case ESFERA:
            default:
                crearEsferica(false);
                break;
        }
    }

    /**
     * Esfera de verdad: se sortea una direccion en 3D y se proyecta a 2D. Asi el
     * borde queda mas denso que el centro (se lee como volumen) y la coordenada
     * z sirve gratis para oscurecer y encoger lo que va hacia el fondo.
     */
    private void crearEsferica(boolean doble) {
        int n = 130;
        for (int i = 0; i < n; i++) {
            double z = Azar.entre(-1.0, 1.0);
            double th = Azar.angulo();
            double s = Math.sqrt(1 - z * z);
            double dx = s * Math.cos(th);
            double dy = s * Math.sin(th);
            double prof = 0.62 + 0.38 * (z + 1) / 2;

            double vel = base * Azar.entre(0.88, 1.0);
            Color c = doble ? (i % 2 == 0 ? primario : secundario) : tinte(primario);
            Esquirla e = new Esquirla(x, y, dx * vel, dy * vel, c,
                    r * prof * Azar.entre(0.9, 1.25),
                    Azar.entre(0.55, 0.75),
                    0.16 * alto,
                    0.30);
            e.setBrillo(prof);
            e.setCentella(Azar.probabilidad(0.20));
            esquirlas.add(e);
        }
    }

    /** Corona nitida: angulos repartidos por igual y muy poca dispersion. */
    private void crearAnillo() {
        int n = 120;
        for (int i = 0; i < n; i++) {
            double th = 2 * Math.PI * i / n + Azar.entre(-0.025, 0.025);
            // Con la misma velocidad exacta el aro sale de compas; hay que
            // darle grosor sin perder la forma.
            double vel = base * 0.85 * Azar.entre(0.88, 1.12);
            Esquirla e = new Esquirla(x, y,
                    Math.cos(th) * vel, Math.sin(th) * vel, tinte(primario),
                    r * Azar.entre(0.95, 1.2),
                    Azar.entre(0.6, 0.72),
                    0.16 * alto,
                    0.24);
            e.setCentella(Azar.probabilidad(0.15));
            esquirlas.add(e);
        }
    }

    /** Dos capas concentricas, la interior lenta, y purpurina en casi todo. */
    private void crearCrisantemo() {
        capaEsferica(70, base * 0.5, primario);
        capaEsferica(110, base * 0.95, secundario);
    }

    private void capaEsferica(int n, double vel, Color c) {
        for (int i = 0; i < n; i++) {
            double z = Azar.entre(-1.0, 1.0);
            double th = Azar.angulo();
            double s = Math.sqrt(1 - z * z);
            double dx = s * Math.cos(th);
            double dy = s * Math.sin(th);
            double prof = 0.62 + 0.38 * (z + 1) / 2;

            double v = vel * Azar.entre(0.92, 1.08);
            Esquirla e = new Esquirla(x, y, dx * v, dy * v, tinte(c),
                    r * prof * Azar.entre(0.9, 1.2),
                    Azar.entre(0.42, 0.58),
                    0.20 * alto,
                    0.30);
            e.setBrillo(prof);
            e.setCentella(Azar.probabilidad(0.85));
            esquirlas.add(e);
        }
    }

    /** Sauce: poca gravedad, mucho arrastre y vida larga, para que cuelgue. */
    private void crearSauce() {
        int n = 80;
        for (int i = 0; i < n; i++) {
            double z = Azar.entre(-1.0, 1.0);
            double th = Azar.angulo();
            double s = Math.sqrt(1 - z * z);
            double dx = s * Math.cos(th);
            double dy = s * Math.sin(th);
            double prof = 0.62 + 0.38 * (z + 1) / 2;

            double vel = base * 0.85 * Azar.entre(0.9, 1.1);
            Color c = Azar.hsb((float) Azar.entre(0.09, 0.13), 0.75f, 1f);
            Esquirla e = new Esquirla(x, y, dx * vel, dy * vel, c,
                    r * 1.15 * prof,
                    Azar.entre(0.28, 0.34),
                    0.10 * alto,
                    0.12);
            e.setBrillo(prof);
            e.setCentella(Azar.probabilidad(0.25));
            esquirlas.add(e);
        }
    }

    /** Palmera: pocas serpentinas gruesas abiertas hacia arriba. */
    private void crearPalmera() {
        int n = 16;
        for (int i = 0; i < n; i++) {
            double th = 2 * Math.PI * i / n + Azar.entre(-0.06, 0.06);
            double dx = Math.cos(th);
            double dy = Math.sin(th) * 0.55 - 0.60;
            double norma = Math.sqrt(dx * dx + dy * dy);
            if (norma > 0) {
                dx /= norma;
                dy /= norma;
            }
            double vel = base * 1.05 * Azar.entre(0.9, 1.1);
            Esquirla e = new Esquirla(x, y, dx * vel, dy * vel, tinte(primario),
                    r * 2.4,
                    0.32,
                    0.22 * alto,
                    0.30);
            e.setCentella(true);
            esquirlas.add(e);
        }
    }

    /** Croseta: pocas chispas gordas que se parten a media vida. */
    private void crearCroseta() {
        int n = 26;
        for (int i = 0; i < n; i++) {
            double th = 2 * Math.PI * i / n + Azar.entre(-0.05, 0.05);
            double vel = base * 0.8 * Azar.entre(0.9, 1.1);
            Esquirla e = new Esquirla(x, y,
                    Math.cos(th) * vel, Math.sin(th) * vel, tinte(primario),
                    r * 1.4,
                    Azar.entre(0.45, 0.55),
                    0.18 * alto,
                    0.30);
            e.setDivisiones(1);
            esquirlas.add(e);
        }
    }

    /**
     * Reparte cuatro hijas en abanico de mas/menos 45 grados sobre el rumbo de
     * la madre, arrastrando parte de su velocidad y con vida mas corta.
     */
    private void dividir(Esquirla madre, List<Esquirla> destino) {
        double pvx = madre.getVx();
        double pvy = madre.getVy();
        double rapidez = Math.sqrt(pvx * pvx + pvy * pvy);
        double rumbo = Math.atan2(pvy, pvx);
        double vel = 0.45 * rapidez;
        for (int i = 0; i < 4; i++) {
            double th = rumbo + Math.toRadians(-45 + i * 30);
            Esquirla h = new Esquirla(madre.getX(), madre.getY(),
                    Math.cos(th) * vel + pvx * 0.35,
                    Math.sin(th) * vel + pvy * 0.35,
                    Destello.mezclar(madre.getColor(), secundario, 0.35f),
                    madre.getRadio() * 0.7,
                    Azar.entre(0.9, 1.2),
                    0.18 * alto,
                    0.30);
            h.setDivisiones(0);
            h.setCentella(Azar.probabilidad(0.5));
            destino.add(h);
        }
    }

    /** Aclara un poco el color para que no salgan todas las chispas planas. */
    private Color tinte(Color c) {
        return Destello.mezclar(c, Color.WHITE, (float) Azar.entre(0.0, 0.28));
    }

    public void pintar(Graphics2D g) {
        // 1. El cohete es solo una brasa calida muy brillante.
        if (ascendiendo) {
            double d = r * 2.2;
            g.setComposite(Destello.mezcla(1f));
            g.drawImage(Destello.de(calido),
                    (int) Math.round(x - d), (int) Math.round(y - d),
                    (int) Math.round(d * 2), (int) Math.round(d * 2), null);
        }

        // 2. Fogonazo del estallido.
        if (fogonazo > 0) {
            // Un destello breve y cenido: con radios grandes se comia la escena.
            double d = base * 0.34;
            g.setComposite(Destello.mezcla(alfa(0.60 * fogonazo)));
            g.drawImage(Destello.de(primario),
                    (int) Math.round(x - d), (int) Math.round(y - d),
                    (int) Math.round(d * 2), (int) Math.round(d * 2), null);
        }

        // 3. Las chispas.
        for (int i = 0; i < esquirlas.size(); i++) {
            esquirlas.get(i).pintar(g);
        }

        // Se devuelve el contexto a un estado neutro para el siguiente que pinte.
        g.setComposite(AlphaComposite.SrcOver);
    }

    /** AlphaComposite lanza excepcion fuera de [0,1]: se recorta siempre. */
    private static float alfa(double a) {
        if (a < 0) {
            return 0f;
        }
        if (a > 1) {
            return 1f;
        }
        return (float) a;
    }
}
