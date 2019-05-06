package j4f;

import java.awt.BorderLayout;
import javax.swing.JFrame;

/**
 *  
 * @author ballestas
 */
public class J4F {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        JFrame frame = new JFrame("Fireworks");
        frame.setSize(600, 500);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(new Fireworks());
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
