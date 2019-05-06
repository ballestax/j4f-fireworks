package j4f;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.util.ArrayList;
import org.dzur.Util;

/**
 *
 * @author ballestas
 */
public class Explosion {

    private int radio;
    private int numPuntos;
    private Point posicion;
    private ArrayList<Esquirla> esquirlas;
    public ArrayList<Point> trazas;
    public boolean multicolor;
    public boolean haExplotado;

    public Explosion(int radio, int numPuntos, Point posicion) {
        this.radio = radio;
        this.numPuntos = numPuntos;
        this.posicion = posicion;
        haExplotado = false;
        
        trazas = new ArrayList<>();

        esquirlas = new ArrayList<>();
        inicializarEsquirlas();
        for (int i = 0; i < esquirlas.size(); i++) {
            Esquirla get = esquirlas.get(i);
        }
    }

    private void inicializarEsquirlas() {
        double angulo = 0;
        double inc = 360 / numPuntos;
        Color color = Util.colorAleatorio();
        for (int i = 0; i < numPuntos; i++) {

            esquirlas.add(new Esquirla(
                    new Point(posicion.x, posicion.y),
                    color,
                    radio,
                    Math.toRadians(angulo),
                    i % 2 == 0 ? 4 : 3,
                    5)
            );
            angulo += inc;

        }
    }

    public int getRadio() {
        return radio;
    }

    public void setRadio(int radio) {
        this.radio = radio;
    }

    public int getNumPuntos() {
        return numPuntos;
    }

    public void setNumPuntos(int numPuntos) {
        this.numPuntos = numPuntos;
    }

    public Point getPosicion() {
        return posicion;
    }

    public void setPosicion(Point posicion) {
        this.posicion = posicion;
    }

    public boolean isHaExplotado() {
        return haExplotado;
    }

    public ArrayList<Esquirla> getEsquirlas() {
        return esquirlas;
    }

    public boolean isMulticolor() {
        return multicolor;
    }

    public void setMulticolor(boolean multicolor) {
        this.multicolor = multicolor;
    }

    public void pintar(Graphics g) {
        g.setColor(Color.red);
        g.drawOval(posicion.x, posicion.y, radio * 2, radio * 2);
        
        if (haExplotado) {
            for (int i = 0; i < esquirlas.size(); i++) {
                Esquirla esq = esquirlas.get(i);
                esq.setMulticolor(multicolor);
                esq.pintar(g);
            }
        }
    }

    public void explotar() {
        haExplotado = true;
        for (int j = 0; j < esquirlas.size(); j++) {
            esquirlas.get(j).setPosicion(new Point(posicion.x, posicion.y));
            esquirlas.get(j).mover();
        }
    }

    public void lanzar() {
        posicion.y -= 6;
    }

    public ArrayList<Point> getTrazas() {
        return trazas;
    }

}
