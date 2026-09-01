package j4f;

/**
 * Evento inmutable que describe un cambio de estado de una {@link Animacion}.
 *
 * @author ballestas
 */
public class AnimacionEvent {

    /** Codigo desconocido o no especificado. */
    public static final int unknown = -1;
    /** La animacion ha iniciado. */
    public static final int STARTED = 0;
    /** La animacion ha sido detenida. */
    public static final int STOPPED = 1;

    private final Object source;
    private final int code;
    private final Object description;

    /**
     * Crea un evento de animacion.
     *
     * @param source origen del evento.
     * @param code codigo del evento: {@link #unknown}, {@link #STARTED} o
     * {@link #STOPPED}.
     * @param desc descripcion opcional, puede ser nula.
     */
    public AnimacionEvent(Object source, int code, Object desc) {
        this.source = source;
        this.code = code;
        this.description = desc;
    }

    /**
     * Constructor copia.
     *
     * @param event evento a copiar.
     */
    public AnimacionEvent(AnimacionEvent event) {
        this(event.source, event.code, event.description);
    }

    public Object getSource() {
        return source;
    }

    public int getCode() {
        return code;
    }

    public Object getDescription() {
        return description;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Animacion is: ");
        switch (code) {
            case STARTED:
                sb.append("STARTED");
                break;
            case STOPPED:
                sb.append("STOPPED");
                break;
            case unknown:
                sb.append("UNKNOWN");
                break;
            default:
                sb.append("CODE ").append(code);
                break;
        }
        if (description != null) {
            sb.append(" [").append(description).append(']');
        }
        return sb.toString();
    }

}
