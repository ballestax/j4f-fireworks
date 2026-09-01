package j4f;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Una sola chispa de la explosion.
 *
 * Toda la fisica se expresa en pixeles y segundos, nunca en fotogramas, de modo
 * que la animacion se ve igual a 30 o a 120 fps.
 *
 * @author ballestas
 */
public class Esquirla {

    /** Posicion en pixeles. */
    private double x;
    private double y;
    /** Velocidad en pixeles por segundo. */
    private double vx;
    private double vy;

    private Color color;
    /** Radio base del nucleo en pixeles. */
    private double radio;
    /** Vida restante: empieza en 1 y muere en 0. */
    private double vida;
    /** Vida que se pierde por segundo. */
    private double decaimiento;
    /** Aceleracion vertical en px/s^2. */
    private double gravedad;
    /** Fraccion de la velocidad que se CONSERVA cada segundo (0.30 = frena mucho). */
    private double arrastre;
    /** Parpadeo tipo purpurina. */
    private boolean centella;
    /** Fase del parpadeo, para que no titilen todas a la vez. */
    private double fase;
    /** Veces que aun puede subdividirse (0 = nunca). */
    private int divisiones;
    /** Multiplicador de brillo por profundidad (~0.6 al fondo, ~1.0 al frente). */
    private double brillo;

    public Esquirla(double x, double y, double vx, double vy, Color color,
            double radio, double decaimiento, double gravedad, double arrastre) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.color = color;
        this.radio = radio;
        this.decaimiento = decaimiento;
        this.gravedad = gravedad;
        this.arrastre = arrastre;
        this.vida = 1.0;
        this.centella = false;
        this.fase = Azar.angulo();
        this.divisiones = 0;
        this.brillo = 1.0;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getVx() {
        return vx;
    }

    public double getVy() {
        return vy;
    }

    public Color getColor() {
        return color;
    }

    public double getVida() {
        return vida;
    }

    public void setVida(double vida) {
        this.vida = vida;
    }

    public double getRadio() {
        return radio;
    }

    public void setCentella(boolean centella) {
        this.centella = centella;
    }

    public void setDivisiones(int divisiones) {
        this.divisiones = divisiones;
    }

    public int getDivisiones() {
        return divisiones;
    }

    public void setBrillo(double brillo) {
        this.brillo = brillo;
    }

    /** Integra un paso de dt segundos. */
    public void actualizar(double dt) {
        double k = Math.pow(arrastre, dt);
        vx *= k;
        vy = vy * k + gravedad * dt;
        x += vx * dt;
        y += vy * dt;
        vida -= decaimiento * dt;
        fase += dt * 22;
    }

    public boolean viva() {
        return vida > 0;
    }

    /** Solo se parte cuando ya lleva recorrido buena parte de su trayecto. */
    public boolean debeDividirse() {
        return divisiones > 0 && vida < 0.55;
    }

    public void consumirDivision() {
        divisiones--;
    }

    /**
     * Dibuja el halo. No restaura el Composite: de eso se encarga quien pinta,
     * que asi evita cientos de cambios de estado por fotograma.
     */
    public void pintar(Graphics2D g) {
        double v = vida;
        if (v < 0) {
            v = 0;
        } else if (v > 1) {
            v = 1;
        }
        // La caida cuadratica se lee como una brasa apagandose, no como un corte.
        double a = v * v * brillo;
        if (centella) {
            a *= 0.30 + 0.70 * Math.abs(Math.sin(fase));
        }
        if (a <= 0.02) {
            return;
        }
        if (a > 1) {
            a = 1;
        }
        BufferedImage sprite = Destello.de(color);
        // El halo encoge conforme muere.
        double d = radio * (2.4 + 2.4 * v);
        g.setComposite(Destello.mezcla(a));
        g.drawImage(sprite,
                (int) Math.round(x - d), (int) Math.round(y - d),
                (int) Math.round(d * 2), (int) Math.round(d * 2), null);
    }
}
