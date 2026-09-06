package j4f.sonido;

/**
 * Reparte los eventos entre las voces y suma la mezcla del bloque.
 *
 * Vive entero en el hilo de audio. Todo esta preasignado: en render() no se
 * crea ni un objeto.
 *
 * @author ballestas
 */
public final class Mezclador {

    public static final int CAPAS = 6;
    private static final int VOCES = 24;
    /** La percusion es la ultima capa; alli la nota elige la receta. */
    private static final int CAPA_PERCUSION = 5;
    /**
     * Ganancia de compensacion de la mezcla.
     *
     * Las recetas normalizan sus armonicos y la velocidad entra al cuadrado,
     * asi que la suma de voces se queda muy por debajo de fondo de escala. Sin
     * esto la salida medida daba un pico de 0,058, unos veinticinco decibelios
     * por debajo de donde deberia. El limitador se encarga de los picos.
     */
    private static final float GANANCIA_MAESTRA = 11f;

    private final Voz[] voces = new Voz[VOCES];
    private final Instrumento[] instrumentos = new Instrumento[CAPAS];
    private final float[] volumenCapa = new float[CAPAS];
    /** Ultima altura tocada en cada capa, para el ligado. */
    private final float[] incrementoPrevioCapa = new float[CAPAS];

    private final BancoRuido ruido;
    private final Reverberacion reverberacion;
    private final Coro coro;
    private final Saturacion saturacion;
    private final Limitador limitador;

    private final float[] izq;
    private final float[] der;
    private final float[] envIzq;
    private final float[] envDer;

    private final ColaEventos cola;
    /**
     * Estallidos con posicion.
     *
     * Van aparte de las voces musicales y no gastan polifonia: un fuego no
     * tiene por que robarle la voz a un acorde, y al reves tampoco.
     */
    private final Estallidos estallidos;
    private volatile int generacionValida;
    private volatile float volumenGeneral = 1f;
    private int robadas;

    public Mezclador(double frecMuestreo, int maxBloque, ColaEventos cola) {
        this.cola = cola;
        this.estallidos = new Estallidos(frecMuestreo);
        // Las voces de aqui son tenues y el bus las levanta por GANANCIA_MAESTRA.
        // Un estallido sale ya a escala completa, asi que hay que bajarlo en la
        // misma proporcion o entra al limitador multiplicado por once.
        this.estallidos.setNivel(0.42f / GANANCIA_MAESTRA);
        this.ruido = new BancoRuido(frecMuestreo, 0x5DEECE66DL);
        this.reverberacion = new Reverberacion(frecMuestreo);
        this.coro = new Coro(frecMuestreo);
        this.saturacion = new Saturacion();
        this.limitador = new Limitador(frecMuestreo);
        this.izq = new float[maxBloque];
        this.der = new float[maxBloque];
        this.envIzq = new float[maxBloque];
        this.envDer = new float[maxBloque];
        for (int i = 0; i < VOCES; i++) {
            voces[i] = new Voz(frecMuestreo);
        }
        instrumentos[0] = Instrumento.pad();
        instrumentos[1] = Instrumento.bajo();
        instrumentos[2] = Instrumento.motivo();
        instrumentos[3] = Instrumento.contra();
        instrumentos[4] = Instrumento.textura();
        instrumentos[5] = Instrumento.percusion();
        for (int i = 0; i < CAPAS; i++) {
            volumenCapa[i] = 1f;
        }
        reverberacion.ajustar(0.72, 0.35, 0.30);
        coro.ajustar(0.20, 0.15);
        saturacion.ajustar(1.35, 1.25);
        limitador.ajustar(0.92, 90);
    }

    public Estallidos getEstallidos() {
        return estallidos;
    }

    public void setGeneracion(int g) {
        generacionValida = g;
    }

    public void setVolumenGeneral(float v) {
        volumenGeneral = v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /**
     * Cambia el timbre de una capa en caliente.
     *
     * Es lo que hacia falta para que el genero se oiga en el motor propio:
     * antes las seis recetas se fijaban en el constructor y no habia forma de
     * que un cambio de genero las tocara.
     */
    public void setInstrumento(int capa, Instrumento ins) {
        if (capa >= 0 && capa < CAPAS && ins != null) {
            instrumentos[capa] = ins;
        }
    }

    public Instrumento getInstrumento(int capa) {
        return capa >= 0 && capa < CAPAS ? instrumentos[capa] : null;
    }

    public void setVolumenCapa(int capa, float v) {
        if (capa >= 0 && capa < CAPAS) {
            volumenCapa[capa] = v;
        }
    }

    public int vocesActivas() {
        int n = 0;
        for (int i = 0; i < VOCES; i++) {
            if (voces[i].sonando()) {
                n++;
            }
        }
        return n;
    }

    public int getRobadas() {
        return robadas;
    }

    /** Ajustes de la cadena de efectos, para poder afinarla en caliente. */
    public void ajustarEfectos(double revTamano, double revMezcla,
            double coroMezcla, double empuje, double anchura) {
        reverberacion.ajustar(revTamano, 0.35, revMezcla);
        coro.ajustar(coroMezcla, 0.15);
        saturacion.ajustar(empuje, anchura);
    }

    /** Renderiza un bloque entero en las mezclas y lo entrega intercalado. */
    public void render(float[] salidaIzq, float[] salidaDer, int n, long ahoraNs) {
        atenderEventos(ahoraNs);

        for (int i = 0; i < n; i++) {
            izq[i] = 0;
            der[i] = 0;
            envIzq[i] = 0;
            envDer[i] = 0;
        }
        ruido.procesar(n);

        for (int v = 0; v < VOCES; v++) {
            Voz voz = voces[v];
            if (voz.enUso()) {
                // Nota con duracion cumplida: se suelta, no se corta.
                if (voz.getFinNs() != 0 && ahoraNs - voz.getFinNs() >= 0) {
                    voz.soltar();
                }
                // Al cuadrado, que es como venia actuando el volumen de capa
                // cuando se aplicaba escalando la velocidad (amplitud = v*v).
                // Se conserva la curva y solo cambia el cuando: ahora por
                // muestra, no al disparar la nota.
                float gc = volumenCapa[voz.getCapa()];
                voz.render(izq, der, envIzq, envDer, n, ruido, gc * gc);
            }
        }
        // Antes de la reverberacion: los estallidos tambien mandan al bus, y
        // cuanto mas lejos esta el fuego mas manda, que es lo que los coloca
        // al fondo en vez de pegados al oyente.
        estallidos.render(izq, der, envIzq, envDer, n, ahoraNs);

        reverberacion.procesar(envIzq, envDer, n);

        // Cadena del bus: suma, coro, saturacion con anchura y limitador.
        // La saturacion va antes del limitador a proposito: redondea los
        // picos para que este tenga que actuar menos y no se le oiga.
        float g = volumenGeneral * GANANCIA_MAESTRA;
        for (int i = 0; i < n; i++) {
            salidaIzq[i] = (izq[i] + envIzq[i]) * g;
            salidaDer[i] = (der[i] + envDer[i]) * g;
        }
        coro.procesar(salidaIzq, salidaDer, n);
        saturacion.procesar(salidaIzq, salidaDer, n);
        limitador.procesar(salidaIzq, salidaDer, n);
    }

    private void atenderEventos(long ahoraNs) {
        int gen = generacionValida;
        for (;;) {
            int i = cola.siguiente();
            if (i < 0) {
                return;
            }
            // Obsoleto tras un cambio de genero o un silencio: se descarta.
            if (cola.getGeneracion(i) != gen) {
                cola.avanzar();
                continue;
            }
            int tipo = cola.getTipo(i);
            if (tipo == ColaEventos.TIPO_PANICO) {
                for (int v = 0; v < VOCES; v++) {
                    voces[v].cortar();
                }
            } else if (tipo == ColaEventos.TIPO_APAGAR) {
                int capa = cola.getCapa(i);
                int nota = cola.getNota(i);
                for (int v = 0; v < VOCES; v++) {
                    if (voces[v].enUso() && voces[v].getCapa() == capa && voces[v].getNota() == nota) {
                        voces[v].soltar();
                    }
                }
            } else {
                dispararNota(cola.getCapa(i), cola.getNota(i), cola.getVelocidad(i),
                        cola.getDuracionMs(i), ahoraNs);
            }
            cola.avanzar();
        }
    }

    private void dispararNota(int capa, int nota, int velocidad, int duracionMs, long ahoraNs) {
        if (capa < 0 || capa >= CAPAS) {
            return;
        }
        int libre = -1;
        for (int v = 0; v < VOCES; v++) {
            if (!voces[v].enUso()) {
                libre = v;
                break;
            }
        }
        if (libre < 0) {
            // Sin sitio: se roba la que termine antes, no la mas antigua, para
            // no cortar una nota que aun tiene recorrido.
            libre = 0;
            for (int v = 1; v < VOCES; v++) {
                if (voces[v].getFinNs() - voces[libre].getFinNs() < 0) {
                    libre = v;
                }
            }
            voces[libre].cortar();
            robadas++;
        }
        // La velocidad ya no se escala con el volumen de la capa.
        //
        // Lo hacia, y eso ataba el volumen al momento del disparo: una nota
        // sostenida se quedaba con el volumen que hubiera al empezar. El pad
        // aguanta acordes durante segundos, asi que bajar el volumen dejaba
        // el ambiente intacto y solo bajaba lo que iba entrando. Ademas la
        // velocidad no es solo nivel, tambien abre el filtro: bajar el
        // volumen apagaba el timbre de paso.
        int vel = velocidad;
        if (vel < 1) {
            vel = 1;
        }
        long fin = duracionMs > 0 ? ahoraNs + duracionMs * 1000000L : 0;
        Instrumento ins = capa == CAPA_PERCUSION
                ? Instrumento.dePercusion(nota)
                : instrumentos[capa];
        voces[libre].disparar(capa, nota, vel, ins, fin, incrementoPrevioCapa[capa]);
        incrementoPrevioCapa[capa] = voces[libre].getIncrementoDestino();
    }
}
