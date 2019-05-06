package j4f;

/**
 *
 * @author ballestas
 */
public class AnimacionEvent{

    public AnimacionEvent(Object source, int code, Object desc) {
        this.source = null;
        this.code = -1;
        description = null;
        this.source = source;
        this.code = code;
        description = desc;
    }

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

    public String toString() {
        String str = "Animacion is: ";
        if (code == 0) {
            str = (new StringBuilder()).append(str).append("STARTED").toString();
        } else if (code == 1) {
            str = (new StringBuilder()).append(str).append("STOPPED").toString();
        }
        return str;
    }
    public static final int unknown = -1;
    public static final int STARTED = 0;
    public static final int STOPPED = 1;
    private Object source;
    private int code;
    private Object description;

}
