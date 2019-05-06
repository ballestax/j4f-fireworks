package j4f;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import org.dzur.Util;

/**
 *
 * @author ballestas
 */
public class Esquirla {

    private Point posicion;
    private Point origen;
    private Color color;
    private int radio;
    private double direccion;  //angulo
    private int tiempoDesv;  //Tiempo en desvancerse
    private int velocidad;
    private double rinicial = 0;
    private boolean multicolor;

    public Esquirla(Point posicion, Color color, int radio, double direccion, int velocidad, int tiempoDesv) {
        this.posicion = posicion;
        this.origen = posicion;
        this.color = color;
        this.radio = radio;
        this.velocidad = velocidad;
        this.direccion = direccion;
        this.tiempoDesv = tiempoDesv;
    }

    public Point getPosicion() {
        return posicion;
    }

    public void setPosicion(Point posicion) {
        this.posicion = posicion;
    }

    public Color getColor() {
        return color;
    }

    public void setColor(Color color) {
        this.color = color;
    }

    public int getRadio() {
        return radio;
    }

    public void setRadio(int radio) {
        this.radio = radio;
    }

    public int getTiempoDesv() {
        return tiempoDesv;
    }

    public void setTiempoDesv(int tiempoDesv) {
        this.tiempoDesv = tiempoDesv;
    }

    public boolean isMulticolor() {
        return multicolor;
    }

    public void setMulticolor(boolean multicolor) {
        this.multicolor = multicolor;
    }

    public void mover() {
        int x = posicion.x;
        int y = posicion.y;
        double r = Math.sqrt(x * x + y * y);
        r += velocidad;
        int px = (int) (r * Math.cos(direccion));
        int py = (int) (r * Math.sin(direccion));
        posicion.setLocation(px, py);
    }

    public void pintar(Graphics g) {
        if (isMulticolor()) {
            g.setColor(Util.colorAleatorio());
        } else {
            g.setColor(color);
        }
        g.fillOval(posicion.x, posicion.y, radio * 2, radio * 2);
    }

    @Override
    public String toString() {
        return hashCode() + ": " + posicion.toString();
    }

}
