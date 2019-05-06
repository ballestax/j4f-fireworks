package j4f;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JPanel;
import org.balx.Utiles;
import org.dzur.Mat;

/**
 *
 * @author ballestas
 */
public class Fireworks extends JPanel {

    Thread hilo;
    Animacion animacion;
    Explosion explosion;
    Stack<Explosion> pilaExplosions;
    ArrayList<Explosion> explosiones;
    private ArrayList<Esquirla> esquirlas;
    private BufferedImage bim;

    public Fireworks() {
        animacion = new Animacion();
        pilaExplosions = new Stack<>();
        explosiones = new ArrayList<>();
        start();

    }

    public void start() {
        animacion.start(new Runnable() {
            @Override
            public void run() {
                boolean justone = true;
                while (true) {
                    int al1 = Utiles.aleatorio(25, 60);
                    if (Mat.probabilidad(0.5) && justone) {
                        pilaExplosions.push(new Explosion(2, al1, new Point(Utiles.aleatorio(50, 450), 500 + al1)));
                        justone = false;
                    }

                    if (!pilaExplosions.isEmpty()) {
                        Explosion exp = pilaExplosions.pop();
                        exp.setMulticolor(Mat.probabilidad(0.5));
                        if (exp != null) {
                            explosiones.add(exp);
                        }
                    }

                    for (int i = 0; i < explosiones.size(); i++) {
                        Explosion exp = explosiones.get(i);
                        if (exp.getPosicion().y < 200 + al1) {
                            exp.explotar();
                        } else {
                            exp.lanzar();
                        }
                    }

                    repaint();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        Logger.getLogger(Fireworks.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        });

    }

    @Override
    protected void paintComponent(Graphics g) {
        Rectangle rect = getBounds();
        bim = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = (Graphics2D) bim.getGraphics();
        g2.setColor(Color.black);
        g2.fillRect(0, 0, rect.width, rect.height);
        for (int i = 0; i < explosiones.size(); i++) {
            Explosion exp = explosiones.get(i);
            exp.pintar(g2);
        }
        g.drawImage(bim, 0, 0, this);
    }
}
