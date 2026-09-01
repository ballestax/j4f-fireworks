package j4f.sonido;

/**
 * Destino del sonido que produce el motor de composicion.
 *
 * Deliberadamente habla en terminos MIDI y no en terminos musicales. La razon
 * es de riesgo: Musica ya emite todo por un unico metodo, asi que con esta
 * forma la integracion es cambiar ese metodo y nada mas, en vez de reescribir
 * los treinta y cinco puntos donde se construyen mensajes. La traduccion a
 * eventos del sintetizador ocurre al otro lado de la interfaz.
 *
 * Ninguna implementacion puede propagar excepciones: si la salida se rompe,
 * se apaga sola y la aplicacion sigue corriendo en silencio.
 *
 * @author ballestas
 */
public interface Salida {

    /** @return false si no hay salida utilizable. */
    boolean abrir();

    void cerrar();

    boolean disponible();

    /** Mensaje corto de MIDI: NOTE_ON, NOTE_OFF, CONTROL_CHANGE o PROGRAM_CHANGE. */
    void enviar(int comando, int canal, int dato1, int dato2);

    /** Corta cuanto este sonando. */
    void panico();

    /** Nombre corto para mostrar en pantalla. */
    String nombre();
}
