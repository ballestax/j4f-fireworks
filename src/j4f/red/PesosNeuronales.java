package j4f.red;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Carga los pesos entrenados desde un recurso del jar.
 *
 * Formato, todo en orden de red:
 *   "J4FW" | version:int | numeroTensores:int
 *   por tensor: largoNombre:int | nombre:bytes | filas:int | columnas:int
 *               escalas:float[filas] | datos:byte[filas*columnas]
 *
 * Los pesos se entrenan fuera de la aplicacion; aqui solo se leen. Eso
 * mantiene la regla de cero dependencias en ejecucion: lo que viaja es un
 * fichero de bytes y este lector.
 *
 * Nunca propaga excepciones. Si el recurso falta o esta corrupto devuelve
 * null y quien llama sigue con el generador de siempre, igual que hace el
 * resto del motor cuando se queda sin sonido.
 *
 * @author ballestas
 */
public final class PesosNeuronales {

    private static final String MAGIA = "J4FW";
    public static final int VERSION = 1;

    private final Map<String, Matriz> tensores;

    private PesosNeuronales(Map<String, Matriz> tensores) {
        this.tensores = tensores;
    }

    public Matriz get(String nombre) {
        return tensores.get(nombre);
    }

    public boolean tiene(String... nombres) {
        for (int i = 0; i < nombres.length; i++) {
            if (tensores.get(nombres[i]) == null) {
                return false;
            }
        }
        return true;
    }

    public int cuantos() {
        return tensores.size();
    }

    /** @return los pesos, o null si no hay recurso utilizable. */
    public static PesosNeuronales cargar(String recurso) {
        InputStream in = null;
        DataInputStream d = null;
        try {
            in = PesosNeuronales.class.getResourceAsStream(recurso);
            if (in == null) {
                return null;
            }
            d = new DataInputStream(new BufferedInputStream(in));
            byte[] magia = new byte[4];
            d.readFully(magia);
            if (magia[0] != MAGIA.charAt(0) || magia[1] != MAGIA.charAt(1)
                    || magia[2] != MAGIA.charAt(2) || magia[3] != MAGIA.charAt(3)) {
                return null;
            }
            int version = d.readInt();
            if (version != VERSION) {
                return null;
            }
            int n = d.readInt();
            if (n <= 0 || n > 256) {
                return null;
            }
            Map<String, Matriz> mapa = new HashMap<String, Matriz>();
            for (int t = 0; t < n; t++) {
                int largo = d.readInt();
                if (largo <= 0 || largo > 128) {
                    return null;
                }
                byte[] nombre = new byte[largo];
                d.readFully(nombre);
                int filas = d.readInt();
                int columnas = d.readInt();
                if (filas <= 0 || columnas <= 0 || (long) filas * columnas > 8000000L) {
                    return null;
                }
                float[] escalas = new float[filas];
                for (int i = 0; i < filas; i++) {
                    escalas[i] = d.readFloat();
                }
                byte[] datos = new byte[filas * columnas];
                d.readFully(datos);
                mapa.put(new String(nombre, "UTF-8"), new Matriz(filas, columnas, datos, escalas));
            }
            return new PesosNeuronales(mapa);
        } catch (Throwable t) {
            // Recurso ausente, truncado o de otra version: se sigue sin red.
            return null;
        } finally {
            try {
                if (d != null) {
                    d.close();
                } else if (in != null) {
                    in.close();
                }
            } catch (Exception e) {
                // Cerrando: nada que hacer.
            }
        }
    }
}
