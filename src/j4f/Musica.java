package j4f;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import j4f.sonido.Estallidos;
import j4f.sonido.Salida;
import j4f.sonido.SalidaSintetizador;
import j4f.sonido.SalidaGervill;
import j4f.red.Improvisador;
import j4f.red.RedImprovisador;
import j4f.red.AgenteMusical;

/**
 * Musica de fondo generativa y percusion de apoyo para el espectaculo de
 * fuegos artificiales.
 *
 * No usa ficheros ni librerias externas: compone en vivo enviando mensajes MIDI
 * crudos (ShortMessage) al receptor por defecto del sistema, que en Windows
 * suele ser el Microsoft GS Wavetable Synth.
 *
 * La musica no se saca de un sorteo nota a nota, que suena a papel pintado.
 * Se construye con material que vuelve y se desarrolla:
 *
 * - Motivos: cada seccion inventa (o recuerda) una frase de 3 a 6 notas y la
 *   va reexponiendo variada (transporte a notas del acorde, inversion,
 *   retrogradacion, ornamentacion, truncamiento, extension, desplazamiento
 *   ritmico, cambio de octava de una nota). Hay memoria de motivos, asi que una
 *   frase de hace minutos puede reaparecer.
 * - Secciones: INTRO, TEMA, DESARROLLO, RESPIRO y TEMA2 se turnan cada 15 a 60
 *   segundos y mandan sobre densidad, velocidad, registro y que capas suenan.
 *   El paso de una a otra se interpola, nunca es un corte.
 * - Conduccion de voces: cada acorde busca la inversion que menos mueve las
 *   voces y las notas comunes se sostienen en vez de volver a atacarse.
 * - Cinco capas: pad, bajo, motivo, contracanto y textura, cada una con su
 *   canal y su papel segun el genero.
 * - Expresion: CC 11 hace reguladores lentos sobre los acordes largos, la
 *   velocidad dibuja el arco de la frase, las duraciones articulan y hay un
 *   pequeno desajuste humano en los ataques.
 * - Largo plazo: modulaciones a tonos vecinos y variantes de la progresion
 *   (sustitucion de tritono en jazz, acordes prestados en clasica, cambio de
 *   modo en chill) para que dos pasadas nunca sean iguales.
 *
 * Reglas de la casa:
 *
 * - Nunca lanza excepciones hacia fuera. Si no hay salida MIDI (equipo sin
 *   dispositivo, entorno sin escritorio, sintetizador ocupado por otra
 *   aplicacion) iniciar() devuelve false y todos los metodos quedan como
 *   no-op silenciosos: el espectaculo funciona igual, solo que sin sonido.
 * - golpe() se invoca desde el hilo de animacion a 60 fps, asi que es barato,
 *   no bloquea y esta limitado en cadencia.
 * - El generador vive en su propio hilo demonio, de modo que la aplicacion
 *   puede terminar aunque nadie llame a detener().
 *
 * @author ballestas
 */
public class Musica {

    /** Generos disponibles. El nombre se muestra en pantalla tal cual. */
    public enum Genero {
        CHILL, JAZZ, CLASICA, CARIBENA, GUITARRA, VIOLIN
    }

    /** Cacheado para no crear un array en cada llamada a siguienteGenero(). */
    private static final Genero[] GENEROS = Genero.values();

    /**
     * Casillas que el vector de contexto reserva al genero.
     *
     * Tiene que cuadrar con lo que espera RedImprovisador.CONTEXTO y con el
     * corpus de entrenamiento. Si se anade un genero al enum hay que subirla,
     * ampliar CONTEXTO y reentrenar; mientras tanto los pesos viejos se
     * rechazan por version y manda el generador de reglas.
     */
    private static final int GENEROS_CONTEXTO = 6;

    // ------------------------------------------------------------------
    // Canales y mezcla
    // ------------------------------------------------------------------

    /** Canal del pad armonico. */
    private static final int CANAL_PAD = 0;
    /** Canal del bajo. */
    private static final int CANAL_BAJO = 1;
    /** Canal del motivo, la voz principal. */
    private static final int CANAL_MOTIVO = 2;
    /** Canal del contracanto: la cuarta capa melodica. */
    private static final int CANAL_CONTRA = 3;
    /** Canal de la textura interior (arpegios y demas relleno). */
    private static final int CANAL_TEXTURA = 4;
    /** Canal 10 del estandar GM: percusion. Reservado para golpe(). */
    private static final int CANAL_PERCUSION = 9;
    /** Canales que hay que configurar, silenciar y apagar. */
    private static final int[] CANALES = {
        CANAL_PAD, CANAL_BAJO, CANAL_MOTIVO, CANAL_CONTRA, CANAL_TEXTURA, CANAL_PERCUSION
    };
    /** Canales que cambian de timbre al cambiar de genero; el 9 nunca se toca. */
    private static final int[] CANALES_MELODICOS = {
        CANAL_PAD, CANAL_BAJO, CANAL_MOTIVO, CANAL_CONTRA, CANAL_TEXTURA
    };

    /** Volumen base (CC 7) y desvios por canal; el resultado se limita a 0..127. */
    private static final int VOLUMEN_MAESTRO = 70;
    private static final int DESVIO_VOLUMEN_PAD = -2;
    private static final int DESVIO_VOLUMEN_BAJO = -12;
    private static final int DESVIO_VOLUMEN_MOTIVO = -6;
    private static final int DESVIO_VOLUMEN_CONTRA = -14;
    private static final int DESVIO_VOLUMEN_TEXTURA = -16;
    private static final int DESVIO_VOLUMEN_PERCUSION = -14;

    /** Reverberacion (CC 91) del canal de percusion; la de los demas la fija el genero. */
    private static final int REVERBERACION_PERCUSION = 60;

    /** Panoramica (CC 10): 64 es el centro. */
    private static final int PAN_PAD = 54;
    private static final int PAN_BAJO = 64;
    private static final int PAN_MOTIVO = 76;
    private static final int PAN_CONTRA = 44;
    private static final int PAN_TEXTURA = 86;
    private static final int PAN_PERCUSION = 64;

    /** Controladores MIDI usados. */
    private static final int CC_VOLUMEN = 7;
    private static final int CC_PAN = 10;
    private static final int CC_EXPRESION = 11;
    private static final int CC_REVERBERACION = 91;
    private static final int CC_CORO = 93;
    private static final int CC_TODO_SONIDO_OFF = 120;
    private static final int CC_RESET_CONTROLADORES = 121;
    private static final int CC_TODAS_NOTAS_OFF = 123;

    private static final long MS_A_NS = 1000000L;

    // ------------------------------------------------------------------
    // Estilos que puede pedir un genero
    // ------------------------------------------------------------------

    /** Acorde largo que se cruza con el siguiente. */
    private static final int ARMONIA_SOSTENIDA = 0;
    /** Acordes cortos y sincopados sobre la rejilla de corcheas. */
    private static final int ARMONIA_COMPING = 1;
    /** Montuno: arpegio sincopado de dos manos alineado con la clave. */
    private static final int ARMONIA_MONTUNO = 2;
    /** Rasgueo: el acorde no ataca en bloque, se desgrana cuerda a cuerda. */
    private static final int ARMONIA_RASGUEO = 3;

    /**
     * Cuerdas de una postura de guitarra: cuantas notas como mucho desgrana
     * un rasgueo. Mas de seis no tendria sentido fisico.
     */
    private static final int CUERDAS_RASGUEO = 6;

    /** Fundamental al cambiar de acorde y a veces a mitad. */
    private static final int BAJO_DISPERSO = 0;
    /** Negras continuas por grados conjuntos y notas del acorde. */
    private static final int BAJO_CAMINANTE = 1;
    /** Fundamental larga, del ancho del acorde. */
    private static final int BAJO_SOSTENIDO = 2;
    /** Tumbao: fundamental y quinta, con la del acorde siguiente anticipada. */
    private static final int BAJO_TUMBAO = 3;

    /** Sin contracanto. */
    private static final int CONTRA_NINGUNO = 0;
    /** Brillo sostenido en el agudo (chill). */
    private static final int CONTRA_BRILLO = 1;
    /** Respuesta al motivo con un fragmento suyo (jazz). */
    private static final int CONTRA_RESPUESTA = 2;
    /** Linea larga en movimiento contrario al motivo (clasica). */
    private static final int CONTRA_LINEA = 3;

    /** Sin textura interior. */
    private static final int TEXTURA_NINGUNA = 0;
    /** Acorde desplegado subiendo y bajando. */
    private static final int TEXTURA_ARPEGIO = 1;

    /** Sin patron de percusion: es lo normal y el valor por defecto. */
    private static final int PERCUSION_NINGUNA = 0;
    /** Clave 3-2 con congas a contratiempo. */
    private static final int PERCUSION_CLAVE = 1;

    /**
     * Clave 3-2, en posiciones de corchea sobre un ciclo de dos compases.
     * Es el esqueleto sobre el que se alinea todo lo demas en el son.
     */
    private static final int[] CLAVE_32 = {0, 3, 6, 10, 12};
    /** Congas: tumbao basico, en los contratiempos que la clave deja libres. */
    private static final int[] CONGA_TUMBAO = {2, 7, 8, 14, 15};
    private static final int CICLO_CLAVE = 16;
    private static final int NOTA_CLAVE = 75;
    private static final int NOTA_CONGA_AGUDA = 63;
    private static final int NOTA_CONGA_GRAVE = 64;
    /**
     * Campana (cowbell). En una descarga es la que empuja: marca todos los
     * tiempos y no para, y sin ella el groove se queda en un esqueleto de
     * clave sin motor.
     */
    private static final int NOTA_CAMPANA = 56;
    private static final int VEL_CLAVE = 42;
    private static final int VEL_CONGA = 34;
    private static final int VEL_CAMPANA = 30;
    /** Corta: la percusion de mano no suena a nota tenida. */
    private static final int DURACION_PERCUSION_MS = 120;

    // ------------------------------------------------------------------
    // Secciones: el arco de la pieza
    // ------------------------------------------------------------------

    private static final int SECCION_INTRO = 0;
    private static final int SECCION_TEMA = 1;
    private static final int SECCION_DESARROLLO = 2;
    private static final int SECCION_RESPIRO = 3;
    private static final int SECCION_TEMA2 = 4;

    /** Nombres para depurar y para las trazas. */
    private static final String[] NOMBRES_SECCION = {
        "INTRO", "TEMA", "DESARROLLO", "RESPIRO", "TEMA2"
    };

    /**
     * Perfil de una seccion. Manda sobre la densidad, la velocidad, el registro
     * y que capas suenan; el genero solo pone el color.
     */
    private static final class Seccion {

        int tipo;
        int duracionMinMs;
        int duracionMaxMs;
        double densidad;
        int desvioVelocidad;
        int desvioRegistro;
        boolean capaBajo;
        boolean capaMotivo;
        boolean capaContra;
        boolean capaTextura;

        Seccion(int tipo, int minMs, int maxMs, double densidad, int desvioVel, int desvioReg,
                boolean bajo, boolean motivo, boolean contra, boolean textura) {
            this.tipo = tipo;
            this.duracionMinMs = minMs;
            this.duracionMaxMs = maxMs;
            this.densidad = densidad;
            this.desvioVelocidad = desvioVel;
            this.desvioRegistro = desvioReg;
            this.capaBajo = bajo;
            this.capaMotivo = motivo;
            this.capaContra = contra;
            this.capaTextura = textura;
        }
    }

    /** Tabla de secciones, indexada por las constantes SECCION_*. */
    private static final Seccion[] SECCIONES = {
        new Seccion(SECCION_INTRO, 16000, 24000, 0.25, -10, 0, false, false, false, false),
        new Seccion(SECCION_TEMA, 34000, 46000, 0.70, 0, 0, true, true, false, true),
        new Seccion(SECCION_DESARROLLO, 38000, 56000, 1.15, 9, 12, true, true, true, true),
        new Seccion(SECCION_RESPIRO, 15000, 23000, 0.15, -14, 0, false, false, false, false),
        new Seccion(SECCION_TEMA2, 40000, 58000, 0.95, 4, 0, true, true, true, true)
    };

    /** Orden de las secciones. Al dar la vuelta se salta el INTRO. */
    private static final int[] SECUENCIA = {
        SECCION_INTRO, SECCION_TEMA, SECCION_DESARROLLO, SECCION_RESPIRO,
        SECCION_TEMA2, SECCION_DESARROLLO, SECCION_RESPIRO, SECCION_TEMA2
    };

    /** Cuanto tarda en interpolarse el paso de una seccion a otra. */
    private static final int TRANSICION_MS = 6000;

    // ------------------------------------------------------------------
    // Motivos: generacion, variacion y memoria
    // ------------------------------------------------------------------

    /** Notas de un motivo recien inventado. */
    private static final int MOTIVO_MIN_NOTAS = 3;
    private static final int MOTIVO_MAX_NOTAS = 6;
    /** Tope duro de notas de una variante (ornamentos y extensiones incluidos). */
    private static final int MOTIVO_TOPE_NOTAS = 12;
    /** Duraciones posibles de una nota del motivo, en unidades ritmicas. */
    private static final int[] MOTIVO_DURACIONES = {1, 1, 2, 2, 3, 4};
    /** Probabilidad de que dos notas seguidas vayan por grados conjuntos. */
    private static final double PROB_GRADO_CONJUNTO = 0.72;
    /** Probabilidad de dejar un hueco extra detras de una nota. */
    private static final double PROB_HUECO_EXTRA = 0.30;
    /** Limites del contorno del motivo, en grados de la escala. */
    private static final int MOTIVO_GRADO_MIN = -3;
    private static final int MOTIVO_GRADO_MAX = 9;

    /** Transformaciones que puede sufrir un motivo al reexponerse. */
    private static final int VAR_INVERSION = 0;
    private static final int VAR_RETROGRADO = 1;
    private static final int VAR_OCTAVA = 2;
    private static final int VAR_TRUNCAMIENTO = 3;
    private static final int VAR_EXTENSION = 4;
    private static final int VAR_ORNAMENTO = 5;
    private static final int VAR_AUMENTACION = 6;
    private static final int VAR_DISMINUCION = 7;
    private static final int NUM_VARIACIONES = 8;

    /** Cuantas transformaciones se aplican a la vez, y cada cuanto son dos. */
    private static final double PROB_SEGUNDA_VARIACION = 0.35;
    /** La primera reexposicion de una seccion se oye literal, para fijar el tema. */
    private static final int REEXPOSICIONES_LITERALES = 1;
    /** Cada cuantas reexposiciones se vuelve a la version literal. */
    private static final int REEXPOSICIONES_POR_CICLO = 4;
    /** Huecos entre reexposiciones, en unidades ritmicas. */
    private static final int PAUSA_MOTIVO_MIN = 2;
    private static final int PAUSA_MOTIVO_MAX = 6;

    /** Motivos que se recuerdan para poder traerlos de vuelta mas adelante. */
    private static final int MEMORIA_MOTIVOS = 4;
    /** Probabilidad de recuperar un motivo antiguo al empezar una seccion. */
    private static final double PROB_RECORDAR_MOTIVO = 0.45;

    /** Un motivo: contorno de grados, duraciones y huecos, en unidades. */
    private static final class Motivo {

        int[] grados;
        int[] duraciones;
        int[] huecos;
        int longitud;

        Motivo(int[] grados, int[] duraciones, int[] huecos, int longitud) {
            this.grados = grados;
            this.duraciones = duraciones;
            this.huecos = huecos;
            this.longitud = longitud;
        }
    }

    /**
     * Reproductor de una frase: sabe que nota toca ahora y cuando toca la
     * siguiente. Lo usan el motivo y su respuesta en el contracanto.
     */
    private static final class Reproductor {

        int[] grados = new int[0];
        int[] duraciones = new int[0];
        int[] huecos = new int[0];
        int longitud;
        int indice;
        long proximaNs;
        int gradoBase;
        int canal;
        int velocidadBase;
        boolean activo;
        long unidad;
    }

    // ------------------------------------------------------------------
    // Expresion y articulacion
    // ------------------------------------------------------------------

    /** Cada cuanto se refresca el regulador de CC 11. */
    private static final int EXPRESION_PASO_MS = 120;
    /** Recorrido del regulador sobre un acorde largo. */
    private static final int EXPRESION_MIN = 62;
    private static final int EXPRESION_MAX = 118;
    /** Expresion fija de las capas que no hacen reguladores. */
    private static final int EXPRESION_PLANA = 104;
    /** Desajuste humano de los ataques, en milisegundos. */
    private static final int JITTER_MS = 9;
    /** Articulaciones: seco, normal y ligado. */
    private static final double[] ARTICULACIONES = {0.55, 0.85, 1.25};
    /** Cuanto sube la velocidad en la cima de la frase. */
    private static final int ARCO_FRASE = 11;
    /** Desviacion tipica de la humanizacion de velocidades. */
    private static final int VARIACION_VELOCIDAD = 4;

    // ------------------------------------------------------------------
    // Largo plazo: modulaciones y variantes de la progresion
    // ------------------------------------------------------------------

    /** Cada cuantas secciones se plantea modular. */
    private static final int SECCIONES_POR_MODULACION = 3;
    /** Probabilidad de modular de verdad cuando toca plantearselo. */
    private static final double PROB_MODULACION = 0.55;
    /** Tonos vecinos: cuarta arriba, quinta arriba, relativo menor y segunda. */
    private static final int[] MODULACIONES = {5, 7, -3, 2, -5};
    /** La tonica no se aleja mas de esto de la original, para no derivar. */
    private static final int MARGEN_TONICA = 7;
    /** Probabilidad de variar la progresion en cada pasada. */
    private static final double PROB_VARIAR_PROGRESION = 0.75;

    /** Sin variantes de progresion. */
    private static final int VARIANTE_NINGUNA = 0;
    /** Sustitucion de tritono sobre los dominantes y giros de vuelta. */
    private static final int VARIANTE_TRITONO = 1;
    /** Acordes prestados: mayor por menor y viceversa. */
    private static final int VARIANTE_PRESTADO = 2;
    /** Cambio de modo de la escala. */
    private static final int VARIANTE_MODAL = 3;

    // ------------------------------------------------------------------
    // Genero CHILL: pentatonica menor, pad calido, vibrafono y brillo
    // ------------------------------------------------------------------

    private static final int CHILL_PROGRAMA_PAD = 89;      // Pad 2 (warm)
    private static final int CHILL_PROGRAMA_BAJO = 38;     // Synth Bass 1
    private static final int CHILL_PROGRAMA_MOTIVO = 11;   // Vibraphone
    private static final int CHILL_PROGRAMA_CONTRA = 98;   // FX 3 (crystal)
    /** Tonica base: A2 = nota MIDI 45. */
    private static final int CHILL_RAIZ = 45;
    private static final int CHILL_OCTAVA_PAD = 12;
    private static final int CHILL_OCTAVA_BAJO = 0;
    private static final int CHILL_OCTAVA_MOTIVO = 24;
    private static final int CHILL_OCTAVA_CONTRA = 36;
    /** Escala pentatonica menor y su alternativa dorica para el cambio de modo. */
    private static final int[] CHILL_ESCALA = {0, 3, 5, 7, 10};
    private static final int[] CHILL_ESCALA_DORICA = {0, 2, 3, 5, 7, 9, 10};
    private static final int[][] CHILL_MODOS_ALTERNATIVOS = {CHILL_ESCALA, CHILL_ESCALA_DORICA};
    /** Acordes cuartales que salen de apilar grados de la pentatonica. */
    private static final int[][] CHILL_TIPOS = {{0, 5, 10}, {0, 4, 9}};
    /** Pasos: {semitonos de la fundamental sobre la tonica, tipo de acorde}. */
    private static final int[][] CHILL_PROGRESION = {{0, 0}, {3, 1}, {5, 0}, {7, 0}};
    private static final int CHILL_ACORDE_MIN_MS = 8000;
    private static final int CHILL_ACORDE_MAX_MS = 14000;
    private static final int CHILL_SOLAPE_MS = 2600;
    private static final int CHILL_VEL_PAD = 52;
    private static final int CHILL_VEL_BAJO = 45;
    private static final int CHILL_VEL_MOTIVO = 48;
    private static final int CHILL_VEL_CONTRA = 34;
    private static final int CHILL_BAJO_DURACION_MS = 3600;
    private static final double CHILL_PROB_BAJO_MEDIO = 0.45;
    /** Unidad ritmica del motivo: muy lenta, que respire. */
    private static final int CHILL_UNIDAD_MS = 700;
    private static final int CHILL_REVERBERACION = 96;
    private static final int CHILL_CORO = 36;

    // ------------------------------------------------------------------
    // Genero JAZZ: ii-V-I, comping de Rhodes, bajo caminante y swing
    // ------------------------------------------------------------------

    private static final int JAZZ_PROGRAMA_PAD = 4;       // Electric Piano 1 (Rhodes)
    private static final int JAZZ_PROGRAMA_BAJO = 32;     // Acoustic Bass
    private static final int JAZZ_PROGRAMA_MOTIVO = 59;   // Muted Trumpet
    private static final int JAZZ_PROGRAMA_CONTRA = 26;   // Electric Guitar (jazz)
    /** Tonica base: C3 = nota MIDI 48. */
    private static final int JAZZ_RAIZ = 48;
    private static final int JAZZ_OCTAVA_PAD = 0;
    private static final int JAZZ_OCTAVA_BAJO = -12;
    private static final int JAZZ_OCTAVA_MOTIVO = 12;
    private static final int JAZZ_OCTAVA_CONTRA = 0;
    /** Escala madre: do mayor. Las alteraciones las trae cada acorde. */
    private static final int[] JAZZ_ESCALA = {0, 2, 4, 5, 7, 9, 11};
    /** Tipos de acorde con septima y novena: 0 = min7, 1 = dom7, 2 = maj7. */
    private static final int[][] JAZZ_TIPOS = {
        {0, 3, 7, 10, 14},
        {0, 4, 7, 10, 14},
        {0, 4, 7, 11, 14}
    };
    /** I - vi - ii - V - I - iii - VI7 - ii - V, que vuelve a resolver en I. */
    private static final int[][] JAZZ_PROGRESION = {
        {0, 2}, {9, 0}, {2, 0}, {7, 1},
        {0, 2}, {4, 0}, {9, 1}, {2, 0}, {7, 1}
    };
    private static final int JAZZ_ACORDE_MIN_MS = 2600;
    private static final int JAZZ_ACORDE_MAX_MS = 4200;
    private static final int JAZZ_VEL_PAD = 44;
    private static final int JAZZ_VEL_BAJO = 50;
    private static final int JAZZ_VEL_MOTIVO = 46;
    private static final int JAZZ_VEL_CONTRA = 38;
    /** Negra de 480 ms: unos 125 pulsos por minuto. */
    private static final int JAZZ_PULSO_MS = 480;
    /** Reparto del par de corcheas: 0.66 deja la primera el doble de larga. */
    private static final double JAZZ_SWING = 0.66;
    /** La unidad del motivo es la corchea, para que le entre el swing. */
    private static final int JAZZ_UNIDAD_MS = 240;
    /** Probabilidad de acorde en parte fuerte y en contratiempo: sincopa. */
    private static final double JAZZ_PROB_COMP_FUERTE = 0.20;
    private static final double JAZZ_PROB_COMP_DEBIL = 0.42;
    private static final int JAZZ_REVERBERACION = 72;
    private static final int JAZZ_CORO = 20;

    // ------------------------------------------------------------------
    // Genero CLASICA: cuerdas, violin, cello y arpegios de arpa
    // ------------------------------------------------------------------

    private static final int CLASICA_PROGRAMA_PAD = 48;      // String Ensemble 1
    private static final int CLASICA_PROGRAMA_BAJO = 43;     // Contrabass
    private static final int CLASICA_PROGRAMA_MOTIVO = 40;   // Violin
    private static final int CLASICA_PROGRAMA_CONTRA = 42;   // Cello
    private static final int CLASICA_PROGRAMA_TEXTURA = 46;  // Orchestral Harp
    /** Tonica base: C3 = nota MIDI 48. */
    private static final int CLASICA_RAIZ = 48;
    private static final int CLASICA_OCTAVA_PAD = 0;
    private static final int CLASICA_OCTAVA_BAJO = -12;
    private static final int CLASICA_OCTAVA_MOTIVO = 24;
    private static final int CLASICA_OCTAVA_CONTRA = 0;
    private static final int CLASICA_OCTAVA_TEXTURA = 12;
    /** Triadas diatonicas: 0 = mayor, 1 = menor, 2 = suspendida de cuarta. */
    private static final int[][] CLASICA_TIPOS = {{0, 4, 7}, {0, 3, 7}, {0, 5, 7}};
    /** Indice del tipo suspendido dentro de CLASICA_TIPOS. */
    private static final int CLASICA_TIPO_SUS4 = 2;
    /** Escala mayor natural. */
    private static final int[] CLASICA_ESCALA = {0, 2, 4, 5, 7, 9, 11};
    /** I - V - vi - IV - ii - V - I: cadencia que resuelve en la tonica. */
    private static final int[][] CLASICA_PROGRESION = {
        {0, 0}, {7, 0}, {9, 1}, {5, 0}, {2, 1}, {7, 0}, {0, 0}
    };
    private static final int CLASICA_ACORDE_MIN_MS = 4000;
    private static final int CLASICA_ACORDE_MAX_MS = 6000;
    private static final int CLASICA_SOLAPE_MS = 1600;
    private static final int CLASICA_VEL_PAD = 46;
    private static final int CLASICA_VEL_BAJO = 42;
    private static final int CLASICA_VEL_MOTIVO = 48;
    private static final int CLASICA_VEL_CONTRA = 40;
    private static final int CLASICA_VEL_TEXTURA = 38;
    /** Negra de 760 ms: el arpegio va en corcheas de 380 ms. */
    private static final int CLASICA_PULSO_MS = 760;
    private static final int CLASICA_UNIDAD_MS = 400;
    private static final double CLASICA_PROB_BAJO_MEDIO = 0.35;
    /** Silencios muy contados: el arpegio debe sonar continuo. */
    private static final double CLASICA_PROB_SILENCIO_ARPEGIO = 0.08;
    /** Probabilidad de retardo sobre la tonica y fraccion del acorde que dura. */
    private static final double CLASICA_PROB_SUSPENSION = 0.45;
    private static final double CLASICA_FRACCION_SUSPENSION = 0.40;
    private static final int CLASICA_REVERBERACION = 100;
    private static final int CLASICA_CORO = 30;

    // ------------------------------------------------------------------
    // Constantes comunes a los tres generos
    // ------------------------------------------------------------------

    /** Probabilidad de que el bajo repita la fundamental a mitad de acorde. */
    private static final double PROB_BAJO_MEDIO_FUNDAMENTAL = 0.70;
    /** Cuanto se rebaja la velocidad del bajo de mitad de acorde. */
    private static final int DESVIO_VEL_BAJO_MEDIO = -4;
    /** Notas por acorde del comping y cuanto dura cada ataque, en pulsos. */
    private static final int COMP_NOTAS = 3;
    private static final double COMP_DURACION_PULSOS = 1.10;
    /** Los contratiempos se atacan un poco mas flojo que las partes fuertes. */
    private static final int COMP_DESVIO_DEBIL = -4;
    /** Duracion de la negra del bajo caminante, en fraccion de pulso. */
    private static final double BAJO_CAMINANTE_DURACION = 0.90;
    /** Probabilidad de que el bajo caminante salte a una nota del acorde. */
    private static final double PROB_BAJO_TONO_ACORDE = 0.55;
    /** Semitonos que se permite alejarse al bajo caminante de su centro. */
    private static final int BAJO_CAMINANTE_MARGEN = 7;
    /** Octavas que recorre el arpegio y cuanto dura cada nota, en pulsos. */
    private static final int ARPEGIO_OCTAVAS = 2;
    private static final double ARPEGIO_DURACION_PULSOS = 0.80;
    /** Semitonos de la tercera y de la cuarta, para resolver el retardo. */
    private static final int INTERVALO_TERCERA = 4;
    private static final int INTERVALO_CUARTA = 5;
    /** Margen del ajuste de registro: las notas se pliegan a mas o menos una cuarta. */
    private static final int MARGEN_REGISTRO = 6;
    /** Notas del contracanto de brillo y cuanto duran respecto al acorde. */
    private static final int BRILLO_NOTAS = 2;
    /** Cuantas notas del motivo se devuelven como respuesta. */
    private static final int RESPUESTA_NOTAS = 3;
    /** Retardo de la respuesta, en unidades ritmicas. */
    private static final int RESPUESTA_RETARDO = 2;

    /** Percusion de estallido: tenue y limitada en cadencia. */
    private static final long GOLPE_INTERVALO_MIN_MS = 90;
    /**
     * Limite entre estallidos espaciales.
     *
     * Mucho mas corto que el de la percusion, y a proposito. Los 90 ms de
     * arriba existen para que una traca no suene a ametralladora, cuando todos
     * los golpes salian del mismo sitio y a la vez. Con retardo de propagacion
     * eso ya no pasa: cada estallido llega cuando le toca segun su distancia,
     * asi que separarlos a mano solo tira los que dan la sensacion de traca.
     */
    private static final long ESTALLIDO_INTERVALO_MIN_MS = 18;
    private static final int VEL_GOLPE_MIN = 20;
    private static final int VEL_GOLPE_MAX = 58;
    private static final int NOTA_BOMBO = 36;   // Bass Drum 1
    private static final int NOTA_TOM = 41;     // Low Floor Tom
    private static final double PROB_TOM = 0.35;
    private static final double FUERZA_MINIMA_TOM = 0.60;
    private static final int GOLPE_DURACION_MS = 260;

    /** Rebanada de sueno del hilo generador: define lo rapido que reacciona. */
    /** Si el motor propio manda sobre MIDI. Se puede conmutar en caliente. */
    private static final boolean USAR_SINTETIZADOR_PROPIO = true;
    private static final int PASO_MS = 25;
    /** Voces simultaneas con apagado programado: cinco capas piden sitio. */
    private static final int MAX_VOCES = 48;
    /** Espera maxima al hilo generador dentro de detener(). */
    private static final long ESPERA_PARADA_MS = 500;

    // ------------------------------------------------------------------
    // Ajustes por genero
    // ------------------------------------------------------------------

    /**
     * Todo lo que distingue a un genero. Los campos los rellena la factoria
     * correspondiente y despues no se vuelven a tocar: cada instancia se trata
     * como inmutable y se publica entera en un campo volatil.
     */
    private static final class Ajustes {

        int programaPad;
        int programaBajo;
        int programaMotivo;
        int programaContra;
        int programaTextura;
        int raizBase;
        int octavaPad;
        int octavaBajo;
        int octavaMotivo;
        int octavaContra;
        int octavaTextura;
        int[] escala;
        int[][] modosAlternativos;
        int[][] tiposAcorde;
        int[][] progresion;
        boolean normalizarRegistro;
        int acordeMinMs;
        int acordeMaxMs;
        int solapeMs;
        int estiloArmonia;
        int estiloBajo;
        int estiloContra;
        int estiloTextura;
        int varianteProgresion;
        int velPad;
        int velBajo;
        int velMotivo;
        int velContra;
        int velTextura;
        int reverberacion;
        int coro;
        boolean usaPulso;
        int pulsoMs;
        double swing;
        int unidadMs;
        int bajoDuracionMs;
        double probBajoMedioAcorde;
        int gradoBajoMedio;
        double probSilencioTextura;
        double probCompFuerte;
        double probCompDebil;
        boolean usaSuspension;
        double probSuspension;
        double fraccionSuspension;
        int tipoSus4;
        boolean expresionRegulada;
        int estiloPercusion;
        /**
         * Silencio entre una frase y la siguiente, en unidades.
         *
         * Era una constante global de 2 a 6 unidades, y ahi estaba el problema
         * de que la melodia sonara a pitos sueltos: tres o seis notas y hasta
         * segundo y medio callada. Por genero, porque un pad ambiental quiere
         * ese aire y una trompeta de salsa no.
         */
        int pausaMotivoMin;
        int pausaMotivoMax;
        /** Notas por frase. Tambien por genero: un riff no dura tres notas. */
        int motivoMinNotas;
        int motivoMaxNotas;
        /**
         * Tope del hueco entre notas de una frase, en unidades. Cero = sin
         * tope. Es lo que impide que una nota larga abra un silencio largo y
         * parta la linea en notas sueltas.
         */
        int maxHueco;

        /**
         * Ajustes de partida con valores sensatos.
         *
         * Las tres fabricas originales rellenan los cuarenta y seis campos a
         * mano, y ese patron ya mostro su coste: un campo olvidado hereda el
         * cero de Java en silencio, sin aviso de nadie. Los generos nuevos
         * parten de aqui y solo declaran en que se diferencian, asi que lo que
         * no se toca queda en un valor tocable y no en cero.
         *
         * Las fabricas viejas no se migran a proposito: estan verificadas y
         * reescribirlas solo arriesgaria cambiar el sonido sin ganar nada.
         */
        static Ajustes base() {
            Ajustes a = new Ajustes();
            a.programaPad = 89;
            a.programaBajo = 38;
            a.programaMotivo = 11;
            a.programaContra = 98;
            a.programaTextura = 46;
            a.raizBase = 48;
            a.octavaPad = 0;
            a.octavaBajo = -12;
            a.octavaMotivo = 12;
            a.octavaContra = 0;
            a.octavaTextura = 12;
            a.escala = new int[]{0, 2, 4, 5, 7, 9, 11};
            a.modosAlternativos = new int[][]{{0, 2, 4, 5, 7, 9, 11}};
            a.tiposAcorde = new int[][]{{0, 4, 7}, {0, 3, 7}};
            a.progresion = new int[][]{{0, 0}, {7, 0}, {9, 1}, {5, 0}};
            a.normalizarRegistro = true;
            a.acordeMinMs = 4000;
            a.acordeMaxMs = 6000;
            a.solapeMs = 1200;
            a.estiloArmonia = ARMONIA_SOSTENIDA;
            a.estiloBajo = BAJO_SOSTENIDO;
            a.estiloContra = CONTRA_NINGUNO;
            a.estiloTextura = TEXTURA_NINGUNA;
            a.varianteProgresion = VARIANTE_NINGUNA;
            a.velPad = 46;
            a.velBajo = 44;
            a.velMotivo = 48;
            a.velContra = 38;
            a.velTextura = 38;
            a.reverberacion = 90;
            a.coro = 30;
            a.usaPulso = true;
            a.pulsoMs = 600;
            a.swing = 0.5;
            a.unidadMs = 400;
            a.bajoDuracionMs = 0;
            a.probBajoMedioAcorde = 0.30;
            a.gradoBajoMedio = 1;
            a.probSilencioTextura = 0.05;
            a.probCompFuerte = 0.20;
            a.probCompDebil = 0.40;
            a.usaSuspension = false;
            a.probSuspension = 0.0;
            a.fraccionSuspension = 0.0;
            a.tipoSus4 = -1;
            a.expresionRegulada = true;
            a.estiloPercusion = PERCUSION_NINGUNA;
            return a;
        }
    }

    private static Ajustes ajustesChill() {
        Ajustes a = new Ajustes();
        a.programaPad = CHILL_PROGRAMA_PAD;
        a.programaBajo = CHILL_PROGRAMA_BAJO;
        a.programaMotivo = CHILL_PROGRAMA_MOTIVO;
        a.programaContra = CHILL_PROGRAMA_CONTRA;
        a.programaTextura = CHILL_PROGRAMA_CONTRA;
        a.raizBase = CHILL_RAIZ;
        a.octavaPad = CHILL_OCTAVA_PAD;
        a.octavaBajo = CHILL_OCTAVA_BAJO;
        a.octavaMotivo = CHILL_OCTAVA_MOTIVO;
        a.octavaContra = CHILL_OCTAVA_CONTRA;
        a.octavaTextura = CHILL_OCTAVA_MOTIVO;
        a.escala = CHILL_ESCALA;
        a.modosAlternativos = CHILL_MODOS_ALTERNATIVOS;
        a.tiposAcorde = CHILL_TIPOS;
        a.progresion = CHILL_PROGRESION;
        a.normalizarRegistro = false;
        a.acordeMinMs = CHILL_ACORDE_MIN_MS;
        a.acordeMaxMs = CHILL_ACORDE_MAX_MS;
        a.solapeMs = CHILL_SOLAPE_MS;
        a.estiloArmonia = ARMONIA_SOSTENIDA;
        a.estiloBajo = BAJO_DISPERSO;
        a.estiloContra = CONTRA_BRILLO;
        a.estiloTextura = TEXTURA_NINGUNA;
        a.varianteProgresion = VARIANTE_MODAL;
        a.velPad = CHILL_VEL_PAD;
        a.velBajo = CHILL_VEL_BAJO;
        a.velMotivo = CHILL_VEL_MOTIVO;
        a.velContra = CHILL_VEL_CONTRA;
        a.velTextura = CHILL_VEL_CONTRA;
        a.reverberacion = CHILL_REVERBERACION;
        a.coro = CHILL_CORO;
        a.usaPulso = false;
        a.pulsoMs = CHILL_UNIDAD_MS;
        a.swing = 0.5;
        a.unidadMs = CHILL_UNIDAD_MS;
        a.bajoDuracionMs = CHILL_BAJO_DURACION_MS;
        a.probBajoMedioAcorde = CHILL_PROB_BAJO_MEDIO;
        a.gradoBajoMedio = 1;
        a.probSilencioTextura = 0.0;
        a.usaSuspension = false;
        a.tipoSus4 = -1;
        a.expresionRegulada = true;
        return a;
    }

    private static Ajustes ajustesJazz() {
        Ajustes a = new Ajustes();
        a.programaPad = JAZZ_PROGRAMA_PAD;
        a.programaBajo = JAZZ_PROGRAMA_BAJO;
        a.programaMotivo = JAZZ_PROGRAMA_MOTIVO;
        a.programaContra = JAZZ_PROGRAMA_CONTRA;
        a.programaTextura = JAZZ_PROGRAMA_CONTRA;
        a.raizBase = JAZZ_RAIZ;
        a.octavaPad = JAZZ_OCTAVA_PAD;
        a.octavaBajo = JAZZ_OCTAVA_BAJO;
        a.octavaMotivo = JAZZ_OCTAVA_MOTIVO;
        a.octavaContra = JAZZ_OCTAVA_CONTRA;
        a.octavaTextura = JAZZ_OCTAVA_CONTRA;
        a.escala = JAZZ_ESCALA;
        a.modosAlternativos = new int[][]{JAZZ_ESCALA};
        a.tiposAcorde = JAZZ_TIPOS;
        a.progresion = JAZZ_PROGRESION;
        a.normalizarRegistro = true;
        a.acordeMinMs = JAZZ_ACORDE_MIN_MS;
        a.acordeMaxMs = JAZZ_ACORDE_MAX_MS;
        a.solapeMs = 0;
        a.estiloArmonia = ARMONIA_COMPING;
        a.estiloBajo = BAJO_CAMINANTE;
        a.estiloContra = CONTRA_RESPUESTA;
        a.estiloTextura = TEXTURA_NINGUNA;
        a.varianteProgresion = VARIANTE_TRITONO;
        a.velPad = JAZZ_VEL_PAD;
        a.velBajo = JAZZ_VEL_BAJO;
        a.velMotivo = JAZZ_VEL_MOTIVO;
        a.velContra = JAZZ_VEL_CONTRA;
        a.velTextura = JAZZ_VEL_CONTRA;
        a.reverberacion = JAZZ_REVERBERACION;
        a.coro = JAZZ_CORO;
        a.usaPulso = true;
        a.pulsoMs = JAZZ_PULSO_MS;
        a.swing = JAZZ_SWING;
        a.unidadMs = JAZZ_UNIDAD_MS;
        a.bajoDuracionMs = 0;
        a.probBajoMedioAcorde = 0.0;
        a.gradoBajoMedio = 1;
        a.probSilencioTextura = 0.0;
        a.probCompFuerte = JAZZ_PROB_COMP_FUERTE;
        a.probCompDebil = JAZZ_PROB_COMP_DEBIL;
        a.usaSuspension = false;
        a.tipoSus4 = -1;
        a.expresionRegulada = false;
        a.maxHueco = 3;
        a.pausaMotivoMin = 1;
        a.pausaMotivoMax = 3;
        return a;
    }

    private static Ajustes ajustesClasica() {
        Ajustes a = new Ajustes();
        a.programaPad = CLASICA_PROGRAMA_PAD;
        a.programaBajo = CLASICA_PROGRAMA_BAJO;
        a.programaMotivo = CLASICA_PROGRAMA_MOTIVO;
        a.programaContra = CLASICA_PROGRAMA_CONTRA;
        a.programaTextura = CLASICA_PROGRAMA_TEXTURA;
        a.raizBase = CLASICA_RAIZ;
        a.octavaPad = CLASICA_OCTAVA_PAD;
        a.octavaBajo = CLASICA_OCTAVA_BAJO;
        a.octavaMotivo = CLASICA_OCTAVA_MOTIVO;
        a.octavaContra = CLASICA_OCTAVA_CONTRA;
        a.octavaTextura = CLASICA_OCTAVA_TEXTURA;
        a.escala = CLASICA_ESCALA;
        a.modosAlternativos = new int[][]{CLASICA_ESCALA};
        a.tiposAcorde = CLASICA_TIPOS;
        a.progresion = CLASICA_PROGRESION;
        a.normalizarRegistro = true;
        a.acordeMinMs = CLASICA_ACORDE_MIN_MS;
        a.acordeMaxMs = CLASICA_ACORDE_MAX_MS;
        a.solapeMs = CLASICA_SOLAPE_MS;
        a.estiloArmonia = ARMONIA_SOSTENIDA;
        a.estiloBajo = BAJO_SOSTENIDO;
        a.estiloContra = CONTRA_LINEA;
        a.estiloTextura = TEXTURA_ARPEGIO;
        a.varianteProgresion = VARIANTE_PRESTADO;
        a.velPad = CLASICA_VEL_PAD;
        a.velBajo = CLASICA_VEL_BAJO;
        a.velMotivo = CLASICA_VEL_MOTIVO;
        a.velContra = CLASICA_VEL_CONTRA;
        a.velTextura = CLASICA_VEL_TEXTURA;
        a.reverberacion = CLASICA_REVERBERACION;
        a.coro = CLASICA_CORO;
        a.usaPulso = true;
        a.pulsoMs = CLASICA_PULSO_MS;
        a.swing = 0.5;
        a.unidadMs = CLASICA_UNIDAD_MS;
        a.bajoDuracionMs = 0;
        a.probBajoMedioAcorde = CLASICA_PROB_BAJO_MEDIO;
        a.gradoBajoMedio = 2;
        a.probSilencioTextura = CLASICA_PROB_SILENCIO_ARPEGIO;
        a.usaSuspension = true;
        a.probSuspension = CLASICA_PROB_SUSPENSION;
        a.fraccionSuspension = CLASICA_FRACCION_SUSPENSION;
        a.tipoSus4 = CLASICA_TIPO_SUS4;
        a.expresionRegulada = true;
        a.maxHueco = 4;
        a.pausaMotivoMin = 1;
        a.pausaMotivoMax = 3;
        return a;
    }

    /**
     * Son cubano.
     *
     * Parte de Ajustes.base() y solo declara en que se diferencia, que es
     * justo para lo que se creo base(): rellenar cuarenta y seis campos a mano
     * es como se cuelan los ceros silenciosos.
     *
     * El pulso de 500 ms deja la corchea en 250, o sea unos 120 por minuto:
     * tempo de son y, sobre todo, por encima del suelo de resolucion del
     * motor, que con un sondeo de 25 ms empieza a temblar por debajo de los
     * 120 ms por celda.
     */
    private static Ajustes ajustesCaribena() {
        Ajustes a = Ajustes.base();
        a.programaPad = 0;        // piano acustico para el montuno
        a.programaBajo = 33;      // bajo electrico con dedos
        a.programaMotivo = 56;    // trompeta
        a.programaContra = 114;   // steel drum
        a.programaTextura = 0;
        a.raizBase = 48;
        a.octavaPad = 0;
        a.octavaBajo = -12;
        a.octavaMotivo = 19;   // la trompeta de una descarga grita, no acompana
        a.octavaContra = 12;
        a.escala = new int[]{0, 2, 4, 5, 7, 9, 10};   // mixolidio
        a.modosAlternativos = new int[][]{
            {0, 2, 4, 5, 7, 9, 10},
            {0, 2, 4, 5, 7, 9, 11}};
        a.tiposAcorde = new int[][]{{0, 4, 7}, {0, 4, 7, 10}, {0, 3, 7, 10}};
        // I - IV - V7 - IV, con el II-7 asomando: el ciclo del son.
        a.progresion = new int[][]{{0, 0}, {5, 0}, {7, 1}, {5, 0}, {2, 2}, {7, 1}};
        a.acordeMinMs = 3000;
        a.acordeMaxMs = 4200;
        a.solapeMs = 0;
        a.estiloArmonia = ARMONIA_MONTUNO;
        a.estiloBajo = BAJO_TUMBAO;
        a.estiloContra = CONTRA_RESPUESTA;
        a.estiloTextura = TEXTURA_NINGUNA;
        a.estiloPercusion = PERCUSION_CLAVE;
        // La trompeta de una descarga no dice tres notas y calla: hace un
        // guajeo que vuelve una y otra vez. Sin pausa entre frases y con
        // frases largas, la reexposicion encadena y suena a riff.
        a.pausaMotivoMin = 0;
        a.pausaMotivoMax = 1;
        a.motivoMinNotas = 6;
        a.motivoMaxNotas = 10;
        a.maxHueco = 2;
        a.varianteProgresion = VARIANTE_NINGUNA;
        a.velPad = 48;
        a.velBajo = 54;
        a.velMotivo = 50;
        a.velContra = 42;
        a.reverberacion = 58;     // seco: el son no vive en una catedral
        a.coro = 18;
        a.usaPulso = true;
        a.pulsoMs = 420;
        a.swing = 0.5;            // recto, sin swing
        a.unidadMs = 210;
        a.bajoDuracionMs = 0;
        a.probBajoMedioAcorde = 0.0;   // el tumbao ya pone el bajo
        a.probSilencioTextura = 0.0;
        a.expresionRegulada = false;
        return a;
    }

    /**
     * Guitarra sola.
     *
     * Todo el genero cabe en dos ideas: el acorde se desgrana en vez de
     * atacar en bloque, y no hay nadie mas tocando. Sin percusion, sin
     * contracanto y con la textura pulsando el arpegio, que es el
     * fingerstyle. El modo frigio de alternativa da el color espanol sin
     * comprometer el genero entero a el.
     */
    private static Ajustes ajustesGuitarra() {
        Ajustes a = Ajustes.base();
        a.programaPad = 24;       // guitarra de nailon
        a.programaBajo = 24;      // sus propios bajos, no otro instrumento
        a.programaMotivo = 24;
        a.programaContra = 25;    // acero, para el contraste del contracanto
        a.programaTextura = 24;
        a.raizBase = 45;
        a.octavaPad = 0;
        a.octavaBajo = -12;
        a.octavaMotivo = 12;
        a.octavaTextura = 12;
        a.escala = new int[]{0, 2, 3, 5, 7, 8, 10};        // menor natural
        a.modosAlternativos = new int[][]{
            {0, 2, 3, 5, 7, 8, 10},
            {0, 1, 3, 5, 7, 8, 10}};                       // frigio: color espanol
        a.tiposAcorde = new int[][]{{0, 3, 7}, {0, 4, 7}, {0, 3, 7, 10}};
        a.progresion = new int[][]{{0, 0}, {8, 1}, {5, 0}, {7, 1}, {3, 1}, {0, 0}};
        a.acordeMinMs = 4500;
        a.acordeMaxMs = 7000;
        a.solapeMs = 400;
        a.estiloArmonia = ARMONIA_RASGUEO;
        a.estiloBajo = BAJO_DISPERSO;
        a.estiloContra = CONTRA_NINGUNO;
        a.estiloTextura = TEXTURA_ARPEGIO;
        a.varianteProgresion = VARIANTE_MODAL;
        a.velPad = 50;
        a.velBajo = 46;
        a.velMotivo = 50;
        a.velTextura = 38;
        a.reverberacion = 74;
        a.coro = 14;
        a.usaPulso = true;
        a.pulsoMs = 640;
        a.unidadMs = 320;
        a.bajoDuracionMs = 1400;
        a.probBajoMedioAcorde = 0.35;
        a.probSilencioTextura = 0.22;   // respira: no es una caja de musica
        a.maxHueco = 3;
        a.pausaMotivoMin = 1;
        a.pausaMotivoMax = 3;
        return a;
    }

    /**
     * Violin con cuerdas.
     *
     * Aqui el trabajo pesado lo hacen el vibrato y el ligado de la Fase 1: sin
     * ellos esto seria un organo con nombre de violin. Las notas son largas y
     * el contracanto va en violonchelo por movimiento contrario.
     */
    private static Ajustes ajustesViolin() {
        Ajustes a = Ajustes.base();
        a.programaPad = 49;       // cuerdas en conjunto
        a.programaBajo = 43;      // contrabajo
        a.programaMotivo = 40;    // violin
        a.programaContra = 42;    // violonchelo
        a.programaTextura = 46;   // arpa
        a.raizBase = 48;
        a.octavaPad = 0;
        a.octavaBajo = -12;
        a.octavaMotivo = 24;
        a.octavaContra = -5;
        a.octavaTextura = 12;
        a.escala = new int[]{0, 2, 4, 5, 7, 9, 11};
        a.modosAlternativos = new int[][]{
            {0, 2, 4, 5, 7, 9, 11},
            {0, 2, 3, 5, 7, 8, 10}};
        a.tiposAcorde = new int[][]{{0, 4, 7}, {0, 3, 7}, {0, 5, 7}};
        a.progresion = new int[][]{{0, 0}, {9, 1}, {5, 0}, {2, 1}, {7, 0}, {0, 0}};
        a.acordeMinMs = 5000;
        a.acordeMaxMs = 8000;
        a.solapeMs = 2000;        // las cuerdas se solapan, no se cortan
        a.estiloArmonia = ARMONIA_SOSTENIDA;
        a.estiloBajo = BAJO_SOSTENIDO;
        a.estiloContra = CONTRA_LINEA;
        a.estiloTextura = TEXTURA_ARPEGIO;
        a.varianteProgresion = VARIANTE_PRESTADO;
        a.velPad = 42;
        a.velBajo = 40;
        a.velMotivo = 52;
        a.velContra = 40;
        a.velTextura = 32;
        a.reverberacion = 108;    // sala grande: es donde vive una cuerda
        a.coro = 34;
        a.usaPulso = true;
        a.pulsoMs = 840;
        a.unidadMs = 520;         // notas largas: el arco no corre
        a.probBajoMedioAcorde = 0.20;
        a.probSilencioTextura = 0.14;
        a.usaSuspension = true;
        a.probSuspension = 0.40;
        a.fraccionSuspension = 0.45;
        a.tipoSus4 = 2;
        a.maxHueco = 4;
        a.pausaMotivoMin = 1;
        a.pausaMotivoMax = 2;
        return a;
    }

    /**
     * Red de seguridad para los campos que no admiten cero.
     *
     * Las tres fabricas originales no pasan por base() y rellenan a mano, asi
     * que cada campo nuevo les queda en el cero de Java. Con una longitud de
     * frase eso no es un ajuste raro: es una frase de cero notas. En vez de
     * confiar en acordarse de tocar cuatro fabricas, se completa aqui.
     */
    private static Ajustes completar(Ajustes a) {
        if (a.motivoMinNotas <= 0) {
            a.motivoMinNotas = MOTIVO_MIN_NOTAS;
        }
        if (a.motivoMaxNotas < a.motivoMinNotas) {
            a.motivoMaxNotas = Math.max(MOTIVO_MAX_NOTAS, a.motivoMinNotas);
        }
        if (a.pausaMotivoMax <= 0) {
            a.pausaMotivoMin = PAUSA_MOTIVO_MIN;
            a.pausaMotivoMax = PAUSA_MOTIVO_MAX;
        }
        return a;
    }

    private static final Ajustes AJUSTES_GUITARRA = completar(ajustesGuitarra());
    private static final Ajustes AJUSTES_VIOLIN = completar(ajustesViolin());
    private static final Ajustes AJUSTES_CARIBENA = completar(ajustesCaribena());
    private static final Ajustes AJUSTES_CHILL = completar(ajustesChill());
    private static final Ajustes AJUSTES_JAZZ = completar(ajustesJazz());
    private static final Ajustes AJUSTES_CLASICA = completar(ajustesClasica());

    /**
     * Ajustes de cada genero.
     *
     * Antes esto era una cadena de if que acababa devolviendo CHILL para
     * cualquier constante no contemplada: anadir un genero al enum y olvidarse
     * de esta funcion daba un genero que sonaba a chill sin que nada fallara.
     * Ahora el switch es exhaustivo y lo que falta se ve.
     */
    private static Ajustes ajustesDe(Genero g) {
        if (g == null) {
            return AJUSTES_CHILL;
        }
        switch (g) {
            case CHILL:
                return AJUSTES_CHILL;
            case JAZZ:
                return AJUSTES_JAZZ;
            case CLASICA:
                return AJUSTES_CLASICA;
            case CARIBENA:
                return AJUSTES_CARIBENA;
            case GUITARRA:
                return AJUSTES_GUITARRA;
            case VIOLIN:
                return AJUSTES_VIOLIN;
            default:
                // Genero anadido al enum sin su fabrica. Suena a chill, pero
                // deja rastro en vez de disimularlo.
                System.err.println("j4f.Musica: sin ajustes para el genero " + g
                        + "; se usa CHILL");
                return AJUSTES_CHILL;
        }
    }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    /** Cerrojo corto y exclusivo del envio MIDI; jamas se retiene durante una espera. */
    private final Object cerrojoMidi = new Object();

    private volatile Receiver receptor;
    /**
     * Sintetizador propio. Cuando esta abierto se lleva todo el sonido y el
     * receptor MIDI ni se abre; si falla, se cae a MIDI y no se nota.
     */
    private volatile Salida sintetizadorPropio;
    /**
     * Red que propone frases. Null si no hay pesos, y entonces manda
     * generarMotivo() de siempre. Solo la toca el hilo generador.
     */
    private Improvisador improvisador;
    private final float[] contextoRed = new float[RedImprovisador.CONTEXTO];
    private int frasesDeRed;
    private int frasesDeReglas;
    /**
     * Cuanta pirotecnia hay ahora mismo, de 0 a 1.
     *
     * Media movil alimentada por golpe(), que ya se llama desde el hilo de
     * animacion en cada estallido. Es la unica via por la que el espectaculo
     * entra en la musica; alimenta el condicionamiento de la red.
     */
    private volatile double energiaVisual;
    /** Politica que decide la forma de la pieza. Solo la toca el generador. */
    private final AgenteMusical agente = new AgenteMusical();
    /** Notas emitidas en la seccion, para medirle el resultado al agente. */
    private int notasDeSeccion;

    // Rasgueo pendiente: las cuerdas se emiten de una en una por vuelta del
    // bucle, que es lo que separa una guitarra de un organo.
    private int[] rasgueoNotas;
    private int rasgueoIndice;
    private int rasgueoVelocidad;
    private int rasgueoDuracionMs;
    /** Solo se rellena si hubo que recurrir al sintetizador de respaldo. */
    private Synthesizer sintetizador;

    private volatile boolean disponible;
    private volatile boolean silenciada;
    /** Volumen general, de 0 a 1. Multiplica al volumen base de cada canal. */
    private volatile double volumen = 1.0;
    private volatile boolean enMarcha;
    private volatile Thread hilo;

    /** Genero en curso y sus ajustes; se publican juntos y se leen sin bloquear. */
    private volatile Genero genero = Genero.CHILL;
    private volatile Ajustes ajustes = AJUSTES_CHILL;
    /** Aviso al hilo generador de que debe rehacer su estado musical. */
    private volatile boolean cambioPendiente;

    /** Ultimo golpe atendido, para el limitador de cadencia. */
    private volatile long ultimoGolpeNs;
    private volatile long ultimoEstallidoNs;
    /** Notas de percusion pendientes de apagar (-1 = ninguna). */
    private volatile int notaPercusionA = -1;
    private volatile int notaPercusionB = -1;
    private volatile long finPercusionNs;

    /** Acorde sostenido que esta sonando; se republica entero en cada cambio. */
    private volatile int[] acordeSonando = new int[0];

    // Tabla de voces con apagado programado. Solo la toca el hilo generador.
    private final int[] vozNota = new int[MAX_VOCES];
    private final int[] vozCanal = new int[MAX_VOCES];
    private final long[] vozFinNs = new long[MAX_VOCES];
    private final boolean[] vozActiva = new boolean[MAX_VOCES];

    // Estado armonico. Solo lo toca el hilo generador.
    private int tonicaActual = CHILL_RAIZ;
    private int[] modoActual = CHILL_ESCALA;
    private int[][] progresionActual = CHILL_PROGRESION;
    private int pasoProgresion;
    private int tipoAcordeActual;
    private int gradoRaizAcorde;
    private int raizAcordeAbs;
    private int basePadActual;
    private int[] notasPadPrevias = new int[0];
    private long proximoAcordeNs;
    private long inicioAcordeNs;
    private int duracionAcordeMs;
    private int pasadasProgresion;
    // Alteraciones que trae el acorde y que la melodia debe respetar.
    private final int[] alteracionOrigen = new int[4];
    private int alteracionCuenta;

    // Estado de seccion.
    /** El INTRO solo se oye al arrancar, no en cada cambio de genero. */
    private boolean arrancarEnIntro = true;
    private int indiceSecuencia;
    private int seccionActual = SECCION_INTRO;
    private int seccionPrevia = SECCION_INTRO;
    private long inicioSeccionNs;
    private long finSeccionNs;
    private int seccionesTocadas;

    // Estado del bajo y del pulso.
    private long bajoMedioAcordeNs;
    private boolean bajoMedioPendiente;
    private long proximoPulsoNs;
    private int indiceCorchea;
    private int pulsoEnAcorde;
    private int notaBajoActual;

    // Estado de la textura y del retardo.
    private int[] arpegioNotas = new int[0];
    private int indiceArpegio;
    private boolean suspensionPendiente;
    private long resolucionNs;
    private int notaSuspension;
    private int notaResolucion;

    // Estado de los motivos.
    private final Motivo[] memoriaMotivos = new Motivo[MEMORIA_MOTIVOS];
    private int memoriaUsada;
    private Motivo motivoSeccion;
    private final Reproductor repMotivo = new Reproductor();
    private final Reproductor repContra = new Reproductor();
    private int reexposicion;
    private int ultimaDireccionMotivo = 1;

    // Estado de la expresion.
    private long proximaExpresionNs;

    public Musica() {
        ultimoGolpeNs = System.nanoTime() - 10000L * MS_A_NS;
    }

    // ------------------------------------------------------------------
    // API publica
    // ------------------------------------------------------------------

    /**
     * Abre la salida MIDI y arranca el generador.
     *
     * @return true si hay sonido; false si el equipo no ofrece salida MIDI, en
     *         cuyo caso la clase queda inerte y no molesta a nadie.
     */
    public boolean iniciar() {
        try {
            if (enMarcha) {
                return disponible;
            }
            if (!abrirSalida()) {
                cerrarSalida();
                disponible = false;
                return false;
            }
            disponible = true;
            silenciada = false;
            // El banco de estallidos nace con sus valores por defecto: hay que
            // ponerle los que tenga puestos quien escucha.
            aplicarEstallidos();
            // Si no hay pesos, improvisador queda null y manda el generador
            // de reglas. La aplicacion no se entera.
            improvisador = Improvisador.crear();
            configurarCanales();
            ultimoGolpeNs = System.nanoTime() - 10000L * MS_A_NS;
            enMarcha = true;
            Thread nuevo = new Thread(new Generador(), "Musica");
            nuevo.setDaemon(true);
            nuevo.setPriority(Thread.MIN_PRIORITY);
            hilo = nuevo;
            nuevo.start();
            return true;
        } catch (Exception e) {
            // Cualquier sorpresa deja la musica desactivada, nunca rompe la aplicacion.
            enMarcha = false;
            disponible = false;
            cerrarSalida();
            return false;
        }
    }

    /** Para el generador, apaga todas las notas y cierra el dispositivo. */
    public void detener() {
        try {
            enMarcha = false;
            Thread h = hilo;
            hilo = null;
            if (h != null && h != Thread.currentThread()) {
                h.interrupt();
                try {
                    h.join(ESPERA_PARADA_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            apagarTodo();
        } catch (Exception e) {
            // Ignorado a proposito: detener() nunca debe estorbar al cierre.
        } finally {
            disponible = false;
            cerrarSalida();
        }
    }

    /** @return true si hay salida MIDI abierta y sonando. */
    /** Nombre del motor de sonido en uso, para mostrarlo en pantalla. */
    public String nombreSalida() {
        Salida propio = sintetizadorPropio;
        if (propio != null) {
            return propio.nombre();
        }
        return receptor != null ? "MIDI del sistema" : "sin audio";
    }

    public boolean estaDisponible() {
        return disponible;
    }

    /** @return true si la musica esta muteada. */
    public boolean isSilenciada() {
        return silenciada;
    }

    /** @return el genero que se esta tocando. */
    public Genero getGenero() {
        return genero;
    }

    /**
     * Cambia de genero en caliente, sin cortar la reproduccion ni parar el hilo
     * generador. Se puede llamar desde el hilo de Swing: solo publica los nuevos
     * ajustes y manda unos pocos mensajes MIDI, no espera a nada.
     *
     * Apaga las notas de los canales melodicos para que el pad del genero
     * anterior no se quede colgado, y vuelve a aplicar timbres y controladores
     * respetando el volumen y el silencio que hubiera. El canal de percusion no
     * se toca: golpe() sigue funcionando igual en los tres generos.
     */
    public void setGenero(Genero g) {
        if (g == null || g == genero) {
            return;
        }
        try {
            genero = g;
            ajustes = ajustesDe(g);
            cambioPendiente = true;
            // El agente vuelve al prior: lo aprendido con un genero no tiene
            // por que valer para otro, y arrastrarlo lo dejaria descolocado.
            agente.reiniciar();
            if (!disponible) {
                return;
            }
            for (int i = 0; i < CANALES_MELODICOS.length; i++) {
                int canal = CANALES_MELODICOS[i];
                enviarControl(canal, CC_TODAS_NOTAS_OFF, 0);
                enviarControl(canal, CC_TODO_SONIDO_OFF, 0);
            }
            // El canal de percusion tambien: desde que hay generos con patron
            // ritmico propio, dejarlo sin barrer arrastraria las claves del
            // genero anterior al siguiente. Los estallidos no se ven afectados
            // porque su nota se dispara de nuevo en cada fuego.
            enviarControl(CANAL_PERCUSION, CC_TODAS_NOTAS_OFF, 0);
            enviarControl(CANAL_PERCUSION, CC_TODO_SONIDO_OFF, 0);
            acordeSonando = new int[0];
            aplicarTimbres();
        } catch (Exception e) {
            // Como mucho se queda con el timbre anterior; nunca rompe nada.
        }
    }

    /**
     * Pasa al siguiente genero del ciclo CHILL - JAZZ - CLASICA - CHILL.
     *
     * @return el genero que queda sonando.
     */
    public Genero siguienteGenero() {
        Genero siguiente = GENEROS[(genero.ordinal() + 1) % GENEROS.length];
        setGenero(siguiente);
        return siguiente;
    }

    /** @return el volumen general, de 0 a 1. */
    public double getVolumen() {
        return volumen;
    }

    /**
     * Ajusta el volumen general (0 a 1) y lo aplica en caliente.
     *
     * Si esta silenciada no se envia nada: el nuevo valor queda guardado y
     * entra en vigor al quitar el silencio.
     */
    public void setVolumen(double v) {
        volumen = v < 0 ? 0 : (v > 1 ? 1 : v);
        if (!disponible || silenciada) {
            return;
        }
        for (int i = 0; i < CANALES.length; i++) {
            enviarControl(CANALES[i], CC_VOLUMEN, volumenDeCanal(CANALES[i]));
        }
    }

    /** Sube el volumen un paso y devuelve el valor resultante. */
    public double subirVolumen(double paso) {
        setVolumen(volumen + paso);
        return volumen;
    }

    /** Baja el volumen un paso y devuelve el valor resultante. */
    public double bajarVolumen(double paso) {
        setVolumen(volumen - paso);
        return volumen;
    }

    // ------------------------------------------------------------------
    // Estallidos: mando propio, separado del de la musica
    // ------------------------------------------------------------------

    private volatile boolean estallidosSilenciados;
    private volatile double volumenEstallidos = 1.0;

    /** @return el banco de estallidos de la salida activa, o null si no hay. */
    private Estallidos bancoEstallidos() {
        Salida s = sintetizadorPropio;
        return s == null ? null : s.estallidos();
    }

    /**
     * Vuelve a aplicar volumen y silencio de los estallidos.
     *
     * Hace falta llamarlo al abrir la salida: el banco nace con sus valores
     * por defecto y hay que ponerle los que el usuario tuviera puestos.
     */
    private void aplicarEstallidos() {
        Estallidos b = bancoEstallidos();
        if (b != null) {
            b.setVolumen((float) (estallidosSilenciados ? 0 : volumenEstallidos));
        }
    }

    /**
     * Calla o devuelve el sonido de los fuegos, al instante.
     *
     * Corta tambien lo que este sonando y lo que ya estuviera programado: con
     * el retardo de propagacion puede haber casi un segundo de truenos en el
     * aire, y al pulsar callar se espera silencio, no una cola.
     *
     * @return true si quedan callados.
     */
    public boolean alternarSilencioEstallidos() {
        estallidosSilenciados = !estallidosSilenciados;
        Estallidos b = bancoEstallidos();
        if (b != null) {
            if (estallidosSilenciados) {
                b.panico();
            }
            aplicarEstallidos();
        }
        return estallidosSilenciados;
    }

    public boolean isEstallidosSilenciados() {
        return estallidosSilenciados;
    }

    public double getVolumenEstallidos() {
        return volumenEstallidos;
    }

    /**
     * Ajusta el volumen de los fuegos, de 0 a 1.
     *
     * Es independiente del de la musica: no pasa por los canales MIDI, sino
     * por el banco de estallidos, que se mezcla aparte.
     */
    public void setVolumenEstallidos(double v) {
        volumenEstallidos = v < 0 ? 0 : (v > 1 ? 1 : v);
        aplicarEstallidos();
    }

    public double subirVolumenEstallidos(double paso) {
        setVolumenEstallidos(volumenEstallidos + paso);
        return volumenEstallidos;
    }

    public double bajarVolumenEstallidos(double paso) {
        setVolumenEstallidos(volumenEstallidos - paso);
        return volumenEstallidos;
    }

    /** Mutea o desmutea al instante, sin parar el generador. */
    public void alternarSilencio() {
        if (!disponible) {
            return;
        }
        try {
            boolean nuevoEstado = !silenciada;
            silenciada = nuevoEstado;
            if (nuevoEstado) {
                for (int i = 0; i < CANALES.length; i++) {
                    int canal = CANALES[i];
                    enviarControl(canal, CC_VOLUMEN, 0);
                    enviarControl(canal, CC_TODO_SONIDO_OFF, 0);
                    enviarControl(canal, CC_TODAS_NOTAS_OFF, 0);
                }
                notaPercusionA = -1;
                notaPercusionB = -1;
            } else {
                for (int i = 0; i < CANALES.length; i++) {
                    int canal = CANALES[i];
                    enviarControl(canal, CC_VOLUMEN, volumenDeCanal(canal));
                }
                // Recupera el acorde sostenido que estaba sonando para no dejar
                // un hueco hasta el siguiente cambio armonico. En el comping de
                // jazz no se hace: son ataques cortos y volverian a sonar solos.
                Ajustes a = ajustes;
                if (a.estiloArmonia == ARMONIA_SOSTENIDA) {
                    int[] acorde = acordeSonando;
                    for (int i = 0; i < acorde.length; i++) {
                        enviar(ShortMessage.NOTE_ON, CANAL_PAD, acorde[i], a.velPad);
                    }
                }
            }
        } catch (Exception e) {
            // Sin efecto: como mucho se queda como estaba.
        }
    }

    /**
     * Percusion grave y tenue para un estallido. Se llama desde el hilo de
     * animacion dentro del bucle de 60 fps: no bloquea, no duerme y descarta
     * los golpes demasiado seguidos (tracas de varios cohetes a la vez).
     *
     * Funciona igual en los tres generos: vive en el canal 9 y ningun cambio
     * de genero ni de seccion lo toca.
     *
     * @param fuerza 0..1, intensidad del estallido.
     */
    public void golpe(double fuerza) {
        golpe(fuerza, 0.0, 0.5);
    }

    /**
     * Estallido con sitio en el espacio.
     *
     * @param fuerza de 0 a 1
     * @param pan    -1 izquierda, 0 centro, 1 derecha
     * @param lejos  0 delante, 1 al fondo
     */
    public void golpe(double fuerza, double pan, double lejos) {
        if (!disponible) {
            return;
        }
        // Ojo con lo que NO se mira aqui: el silencio de la musica.
        //
        // Antes se miraba, y por eso callar la musica callaba tambien los
        // fuegos. Son dos cosas distintas: se puede querer el espectaculo con
        // sus truenos y sin musica, o al reves. Cada uno tiene su interruptor.
        long ahora = System.nanoTime();

        // Via espacial: la que de verdad coloca el estallido. Va antes del
        // limitador de abajo y con un limite mucho mas corto, porque el
        // retardo de propagacion ya separa los estallidos por si solo: dos
        // fuegos simultaneos a distinta distancia llegan a los oidos con
        // decimas de diferencia. Aplicarles el limite de 90 ms pensado para
        // la percusion se comeria justamente los que mas informacion dan.
        Salida propio = sintetizadorPropio;
        Estallidos banco = propio == null ? null : propio.estallidos();
        if (banco != null) {
            if (estallidosSilenciados) {
                return;
            }
            if (ahora - ultimoEstallidoNs >= ESTALLIDO_INTERVALO_MIN_MS * MS_A_NS) {
                ultimoEstallidoNs = ahora;
                banco.programar(fuerza, pan, lejos, ahora);
            }
            // La energia visual sube igual: la usa el generador musical.
            double ev = energiaVisual
                    + 0.16 * (fuerza < 0 ? 0 : (fuerza > 1 ? 1 : fuerza));
            energiaVisual = ev > 1 ? 1 : ev;
            return;
        }

        // Sin banco de estallidos (salida MIDI a un aparato de fuera) queda el
        // golpe de percusion de siempre, en el centro y sin retardo.
        if (ahora - ultimoGolpeNs < GOLPE_INTERVALO_MIN_MS * MS_A_NS) {
            return;
        }
        ultimoGolpeNs = ahora;

        double f = fuerza;
        if (f < 0.0) {
            f = 0.0;
        } else if (f > 1.0) {
            f = 1.0;
        }
        int velocidad = VEL_GOLPE_MIN + (int) Math.round((VEL_GOLPE_MAX - VEL_GOLPE_MIN) * f);

        cerrarPercusion();
        enviar(ShortMessage.NOTE_ON, CANAL_PERCUSION, NOTA_BOMBO, velocidad);
        notaPercusionA = NOTA_BOMBO;
        int segunda = -1;
        if (f >= FUERZA_MINIMA_TOM && Azar.probabilidad(PROB_TOM)) {
            enviar(ShortMessage.NOTE_ON, CANAL_PERCUSION, NOTA_TOM, Math.max(1, velocidad - 12));
            segunda = NOTA_TOM;
        }
        notaPercusionB = segunda;
        finPercusionNs = ahora + GOLPE_DURACION_MS * MS_A_NS;

        // Sube deprisa con cada estallido; el hilo generador la va bajando.
        double e = energiaVisual + 0.16 * f;
        energiaVisual = e > 1 ? 1 : e;
    }

    public double getEnergiaVisual() {
        return energiaVisual;
    }

    // ------------------------------------------------------------------
    // Salida MIDI
    // ------------------------------------------------------------------

    /**
     * Busca una salida MIDI. Primero el receptor por defecto del sistema (en
     * Windows llega al Microsoft GS Wavetable Synth y suena de verdad) y, si
     * falla, el sintetizador software del JDK.
     */
    private boolean abrirSalida() {
        // Orden de preferencia: muestras reales por Gervill si hay banco de
        // sonido, luego el motor propio, luego el MIDI del sistema. El
        // sintetizador GS de Windows tiene un banco de 3,4 MB: es el suelo.
        if (Runtime.getRuntime().availableProcessors() >= 3) {
            try {
                Salida g = new SalidaGervill();
                if (g.abrir()) {
                    sintetizadorPropio = g;
                    return true;
                }
            } catch (Throwable t) {
                // Sin banco de sonido en sonido/, o sin la API interna del
                // JDK: se sigue con el motor propio y no se nota.
                sintetizadorPropio = null;
            }
        }
        if (USAR_SINTETIZADOR_PROPIO && Runtime.getRuntime().availableProcessors() >= 3) {
            try {
                Salida s = new SalidaSintetizador();
                if (s.abrir()) {
                    sintetizadorPropio = s;
                    return true;
                }
            } catch (Throwable t) {
                // Sin audio PCM utilizable: sigue por MIDI.
                sintetizadorPropio = null;
            }
        }
        try {
            Receiver r = MidiSystem.getReceiver();
            if (r != null) {
                receptor = r;
                return true;
            }
        } catch (MidiUnavailableException e) {
            // Sin receptor por defecto: se prueba el sintetizador.
        } catch (Exception e) {
            // Idem: se prueba el respaldo.
        }
        try {
            Synthesizer s = MidiSystem.getSynthesizer();
            if (s != null) {
                if (!s.isOpen()) {
                    s.open();
                }
                Receiver r = s.getReceiver();
                if (r != null) {
                    sintetizador = s;
                    receptor = r;
                    return true;
                }
                s.close();
            }
        } catch (MidiUnavailableException e) {
            // No hay sonido en este equipo.
        } catch (Exception e) {
            // No hay sonido en este equipo.
        }
        return false;
    }

    private void cerrarSalida() {
        Salida propio = sintetizadorPropio;
        sintetizadorPropio = null;
        if (propio != null) {
            try {
                propio.cerrar();
            } catch (Exception e) {
                // Cerrando: nada que hacer.
            }
        }
        Receiver r = receptor;
        receptor = null;
        if (r != null) {
            try {
                r.close();
            } catch (Exception e) {
                // Ignorado.
            }
        }
        Synthesizer s = sintetizador;
        sintetizador = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                // Ignorado.
            }
        }
    }

    /** Configuracion inicial completa, incluido el canal de percusion. */
    private void configurarCanales() {
        for (int i = 0; i < CANALES.length; i++) {
            enviarControl(CANALES[i], CC_RESET_CONTROLADORES, 0);
        }
        aplicarTimbres();
        enviarControl(CANAL_PERCUSION, CC_VOLUMEN, volumenVigente(CANAL_PERCUSION));
        enviarControl(CANAL_PERCUSION, CC_PAN, panDeCanal(CANAL_PERCUSION));
        enviarControl(CANAL_PERCUSION, CC_REVERBERACION, REVERBERACION_PERCUSION);
        enviarControl(CANAL_PERCUSION, CC_EXPRESION, 127);
        // El canal 9 es percusion por definicion en GM: no lleva cambio de programa.
    }

    /**
     * Timbres, volumen, panoramica y efectos de los canales melodicos segun el
     * genero en curso. El volumen se recalcula siempre a partir del volumen
     * general y del silencio, de modo que cambiar de genero no desmutea ni
     * devuelve el volumen al maximo.
     */
    private void aplicarTimbres() {
        Ajustes a = ajustes;
        for (int i = 0; i < CANALES_MELODICOS.length; i++) {
            int canal = CANALES_MELODICOS[i];
            enviarControl(canal, CC_VOLUMEN, volumenVigente(canal));
            enviarControl(canal, CC_PAN, panDeCanal(canal));
            enviarControl(canal, CC_REVERBERACION, a.reverberacion);
            enviarControl(canal, CC_CORO, a.coro);
            enviarControl(canal, CC_EXPRESION, EXPRESION_PLANA);
        }
        enviar(ShortMessage.PROGRAM_CHANGE, CANAL_PAD, a.programaPad, 0);
        enviar(ShortMessage.PROGRAM_CHANGE, CANAL_BAJO, a.programaBajo, 0);
        enviar(ShortMessage.PROGRAM_CHANGE, CANAL_MOTIVO, a.programaMotivo, 0);
        enviar(ShortMessage.PROGRAM_CHANGE, CANAL_CONTRA, a.programaContra, 0);
        enviar(ShortMessage.PROGRAM_CHANGE, CANAL_TEXTURA, a.programaTextura, 0);
    }

    /** Volumen base del canal escalado por el volumen general. */
    private int volumenDeCanal(int canal) {
        int desvio;
        if (canal == CANAL_BAJO) {
            desvio = DESVIO_VOLUMEN_BAJO;
        } else if (canal == CANAL_MOTIVO) {
            desvio = DESVIO_VOLUMEN_MOTIVO;
        } else if (canal == CANAL_CONTRA) {
            desvio = DESVIO_VOLUMEN_CONTRA;
        } else if (canal == CANAL_TEXTURA) {
            desvio = DESVIO_VOLUMEN_TEXTURA;
        } else if (canal == CANAL_PERCUSION) {
            desvio = DESVIO_VOLUMEN_PERCUSION;
        } else {
            desvio = DESVIO_VOLUMEN_PAD;
        }
        return limitar((int) Math.round((VOLUMEN_MAESTRO + desvio) * volumen), 0, 127);
    }

    /** Volumen que le toca al canal ahora mismo, contando el silencio. */
    private int volumenVigente(int canal) {
        return silenciada ? 0 : volumenDeCanal(canal);
    }

    private static int panDeCanal(int canal) {
        if (canal == CANAL_BAJO) {
            return PAN_BAJO;
        }
        if (canal == CANAL_MOTIVO) {
            return PAN_MOTIVO;
        }
        if (canal == CANAL_CONTRA) {
            return PAN_CONTRA;
        }
        if (canal == CANAL_TEXTURA) {
            return PAN_TEXTURA;
        }
        if (canal == CANAL_PERCUSION) {
            return PAN_PERCUSION;
        }
        return PAN_PAD;
    }

    private void enviarControl(int canal, int controlador, int valor) {
        enviar(ShortMessage.CONTROL_CHANGE, canal, controlador, valor);
    }

    /** Unico punto de envio. Nunca propaga excepciones. */
    private void enviar(int comando, int canal, int dato1, int dato2) {
        Salida propio = sintetizadorPropio;
        if (propio != null) {
            propio.enviar(comando, canal, limitar(dato1, 0, 127), limitar(dato2, 0, 127));
            return;
        }
        Receiver r = receptor;
        if (r == null) {
            return;
        }
        try {
            ShortMessage mensaje = new ShortMessage();
            mensaje.setMessage(comando, canal, limitar(dato1, 0, 127), limitar(dato2, 0, 127));
            synchronized (cerrojoMidi) {
                r.send(mensaje, -1L);
            }
        } catch (InvalidMidiDataException e) {
            // Mensaje mal formado: se descarta.
        } catch (Exception e) {
            // Dispositivo cerrado o en mal estado: se apaga la musica en caliente.
            disponible = false;
        }
    }

    /** Apaga cuanto pueda estar sonando en los canales usados. */
    private void apagarTodo() {
        for (int i = 0; i < MAX_VOCES; i++) {
            if (vozActiva[i]) {
                vozActiva[i] = false;
                enviar(ShortMessage.NOTE_OFF, vozCanal[i], vozNota[i], 0);
            }
        }
        notaPercusionA = -1;
        notaPercusionB = -1;
        acordeSonando = new int[0];
        notasPadPrevias = new int[0];
        for (int i = 0; i < CANALES.length; i++) {
            int canal = CANALES[i];
            enviarControl(canal, CC_TODAS_NOTAS_OFF, 0);
            enviarControl(canal, CC_TODO_SONIDO_OFF, 0);
        }
    }

    // ------------------------------------------------------------------
    // Voces con apagado programado
    // ------------------------------------------------------------------

    private void notaOn(int canal, int nota, int velocidad, int duracionMs, long ahoraNs) {
        int libre = -1;
        for (int i = 0; i < MAX_VOCES; i++) {
            if (!vozActiva[i]) {
                libre = i;
                break;
            }
        }
        if (libre < 0) {
            // Sin sitio: se roba la voz mas antigua para no dejar notas colgadas.
            libre = 0;
            for (int i = 1; i < MAX_VOCES; i++) {
                if (vozFinNs[i] - vozFinNs[libre] < 0) {
                    libre = i;
                }
            }
            enviar(ShortMessage.NOTE_OFF, vozCanal[libre], vozNota[libre], 0);
        }
        vozNota[libre] = nota;
        vozCanal[libre] = canal;
        vozFinNs[libre] = ahoraNs + (long) duracionMs * MS_A_NS;
        vozActiva[libre] = true;
        notasDeSeccion++;
        enviar(ShortMessage.NOTE_ON, canal, nota, limitar(velocidad, 1, 127));
    }

    private void apagarVencidas(long ahoraNs) {
        for (int i = 0; i < MAX_VOCES; i++) {
            if (vozActiva[i] && ahoraNs - vozFinNs[i] >= 0) {
                vozActiva[i] = false;
                enviar(ShortMessage.NOTE_OFF, vozCanal[i], vozNota[i], 0);
            }
        }
    }

    /** Apaga una nota concreta antes de tiempo (lo usa la resolucion del retardo). */
    private void apagarNota(int canal, int nota) {
        for (int i = 0; i < MAX_VOCES; i++) {
            if (vozActiva[i] && vozCanal[i] == canal && vozNota[i] == nota) {
                vozActiva[i] = false;
            }
        }
        enviar(ShortMessage.NOTE_OFF, canal, nota, 0);
    }

    /** @return true si esa nota sigue sonando en ese canal. */
    private boolean estaSonando(int canal, int nota) {
        for (int i = 0; i < MAX_VOCES; i++) {
            if (vozActiva[i] && vozCanal[i] == canal && vozNota[i] == nota) {
                return true;
            }
        }
        return false;
    }

    /**
     * Alarga una nota que ya suena en vez de volver a atacarla. Es lo que
     * permite que las notas comunes de dos acordes seguidos se mantengan.
     */
    private boolean prolongarNota(int canal, int nota, long nuevoFinNs) {
        for (int i = 0; i < MAX_VOCES; i++) {
            if (vozActiva[i] && vozCanal[i] == canal && vozNota[i] == nota) {
                if (nuevoFinNs - vozFinNs[i] > 0) {
                    vozFinNs[i] = nuevoFinNs;
                }
                return true;
            }
        }
        return false;
    }

    /** Cierra el golpe de percusion pendiente, si lo hay. */
    private void cerrarPercusion() {
        int a = notaPercusionA;
        int b = notaPercusionB;
        notaPercusionA = -1;
        notaPercusionB = -1;
        if (a >= 0) {
            enviar(ShortMessage.NOTE_OFF, CANAL_PERCUSION, a, 0);
        }
        if (b >= 0) {
            enviar(ShortMessage.NOTE_OFF, CANAL_PERCUSION, b, 0);
        }
    }

    private void cerrarPercusionVencida(long ahoraNs) {
        if (notaPercusionA >= 0 && ahoraNs - finPercusionNs >= 0) {
            cerrarPercusion();
        }
    }

    // ------------------------------------------------------------------
    // Utilidades musicales
    // ------------------------------------------------------------------

    /** Nota de un modo para un grado dado, con octavas hacia arriba o abajo. */
    private static int notaDeModo(int raiz, int grado, int[] modo) {
        int tamano = modo.length;
        if (tamano == 0) {
            return limitar(raiz, 0, 127);
        }
        int octava = (int) Math.floor((double) grado / (double) tamano);
        int indice = grado - octava * tamano;
        return limitar(raiz + octava * 12 + modo[indice], 0, 127);
    }

    /** Pliega la nota por octavas hasta dejarla cerca del centro del registro. */
    private static int ajustarRegistro(int nota, int centro) {
        int n = nota;
        while (n - centro > MARGEN_REGISTRO) {
            n -= 12;
        }
        while (centro - n > MARGEN_REGISTRO) {
            n += 12;
        }
        return n;
    }

    /** Grado del modo que corresponde a ese intervalo, o -1 si no esta. */
    private static int gradoDeSemitono(int semitonos, int[] modo) {
        int clase = ((semitonos % 12) + 12) % 12;
        for (int i = 0; i < modo.length; i++) {
            if (modo[i] == clase) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Sube un semitono las notas que chocan con una alteracion del acorde. Es
     * lo que evita que la melodia diatonica muerda un dominante secundario.
     */
    private int corregirAlAcorde(int nota) {
        int clase = ((nota % 12) + 12) % 12;
        for (int i = 0; i < alteracionCuenta; i++) {
            if (alteracionOrigen[i] == clase) {
                return limitar(nota + 1, 0, 127);
            }
        }
        return nota;
    }

    /** Guarda que notas del acorde no estan en la escala, para corregir la melodia. */
    private void calcularAlteraciones(int[] intervalos) {
        alteracionCuenta = 0;
        for (int i = 0; i < intervalos.length && alteracionCuenta < alteracionOrigen.length; i++) {
            int clase = (((raizAcordeAbs + intervalos[i] - tonicaActual) % 12) + 12) % 12;
            if (gradoDeSemitono(clase, modoActual) < 0) {
                int natural = ((clase - 1) + 12) % 12;
                alteracionOrigen[alteracionCuenta] = ((tonicaActual + natural) % 12 + 12) % 12;
                alteracionCuenta++;
            }
        }
    }

    private static int velocidadHumana(int base) {
        return limitar(base + (int) Math.round(Azar.gauss() * VARIACION_VELOCIDAD), 1, 127);
    }

    /** Velocidad dibujando el arco de la frase: la cima suena mas fuerte. */
    private static int velocidadDeFrase(int base, int indice, int longitud) {
        if (longitud <= 1) {
            return velocidadHumana(base);
        }
        double posicion = (double) indice / (double) (longitud - 1);
        double arco = Math.sin(posicion * Math.PI);
        return velocidadHumana(base + (int) Math.round(arco * ARCO_FRASE));
    }

    /** Pequeno desajuste humano de los ataques. */
    private static long jitterNs() {
        return (long) (Azar.entre(-JITTER_MS, JITTER_MS) * MS_A_NS);
    }

    private static int limitar(int valor, int min, int max) {
        if (valor < min) {
            return min;
        }
        return valor > max ? max : valor;
    }

    private static double limitarReal(double valor, double min, double max) {
        if (valor < min) {
            return min;
        }
        return valor > max ? max : valor;
    }

    // ------------------------------------------------------------------
    // Secciones: el arco de la pieza
    // ------------------------------------------------------------------

    /** Cuanto ha avanzado la transicion entre la seccion anterior y la actual. */
    private double progresoTransicion(long ahoraNs) {
        long transcurrido = ahoraNs - inicioSeccionNs;
        if (transcurrido <= 0) {
            return 0.0;
        }
        double p = (double) transcurrido / (double) (TRANSICION_MS * MS_A_NS);
        return limitarReal(p, 0.0, 1.0);
    }

    /** Densidad efectiva, interpolada para que el cambio de seccion no sea un corte. */
    private double densidad(long ahoraNs) {
        double p = progresoTransicion(ahoraNs);
        return SECCIONES[seccionPrevia].densidad * (1.0 - p) + SECCIONES[seccionActual].densidad * p;
    }

    /** Desvio de velocidad efectivo, tambien interpolado. */
    private int desvioVelocidad(long ahoraNs) {
        double p = progresoTransicion(ahoraNs);
        double v = SECCIONES[seccionPrevia].desvioVelocidad * (1.0 - p)
                + SECCIONES[seccionActual].desvioVelocidad * p;
        return (int) Math.round(v);
    }

    /** Desvio de registro efectivo, en semitonos. */
    private int desvioRegistro(long ahoraNs) {
        double p = progresoTransicion(ahoraNs);
        double v = SECCIONES[seccionPrevia].desvioRegistro * (1.0 - p)
                + SECCIONES[seccionActual].desvioRegistro * p;
        return ((int) Math.round(v / 12.0)) * 12;
    }

    /**
     * Una capa se apaga con cola: durante la transicion sigue sonando la de la
     * seccion anterior, de modo que nunca desaparece de golpe.
     */
    private boolean capaBajoActiva(long ahoraNs) {
        if (progresoTransicion(ahoraNs) < 1.0) {
            return SECCIONES[seccionActual].capaBajo || SECCIONES[seccionPrevia].capaBajo;
        }
        return SECCIONES[seccionActual].capaBajo;
    }

    private boolean capaMotivoActiva() {
        return SECCIONES[seccionActual].capaMotivo;
    }

    private boolean capaContraActiva() {
        return SECCIONES[seccionActual].capaContra;
    }

    private boolean capaTexturaActiva() {
        return SECCIONES[seccionActual].capaTextura;
    }

    /**
     * Pasa a la siguiente seccion del arco. Solo se llama al cambiar de acorde,
     * asi que el corte cae siempre en una junta de la armonia.
     */
    private void avanzarSeccion(Ajustes a, long ahoraNs) {
        // Cierra el lazo de la seccion que termina: cuanta musica hubo frente
        // a cuanta pirotecnia pedia la pantalla.
        cerrarSeccionAnteAgente(ahoraNs);

        indiceSecuencia++;
        if (indiceSecuencia >= SECUENCIA.length) {
            // Al dar la vuelta se salta el INTRO: solo se oye al principio.
            indiceSecuencia = 1;
        }
        seccionPrevia = seccionActual;
        seccionActual = SECUENCIA[indiceSecuencia];
        Seccion s = SECCIONES[seccionActual];
        inicioSeccionNs = ahoraNs;
        finSeccionNs = ahoraNs + (long) Azar.entre(s.duracionMinMs, s.duracionMaxMs) * MS_A_NS;
        seccionesTocadas++;
        notasDeSeccion = 0;

        agente.observar(energiaVisual, densidad(ahoraNs),
                (double) indiceSecuencia / SECUENCIA.length);

        // Cada pocas secciones se plantea una modulacion a un tono vecino.
        if (seccionesTocadas % SECCIONES_POR_MODULACION == 0
                && Azar.probabilidad(agente.ajustar(AgenteMusical.MODULAR, PROB_MODULACION))) {
            modular(a);
        }
        // Material nuevo para la seccion: motivo inventado o recordado.
        prepararMotivoDeSeccion(a);
    }

    /**
     * Le pasa al agente el resultado de la seccion que acaba.
     *
     * La actividad se mide en notas por segundo normalizadas; la recompensa es
     * alta cuando la musica acompaña a lo que estaba pasando en pantalla.
     */
    private void cerrarSeccionAnteAgente(long ahoraNs) {
        if (seccionesTocadas == 0) {
            return;
        }
        double segundos = (ahoraNs - inicioSeccionNs) / 1e9;
        if (segundos < 1) {
            return;
        }
        // Diez notas por segundo se considera actividad plena.
        double actividad = (notasDeSeccion / segundos) / 10.0;
        agente.recompensar(energiaVisual, actividad);
    }

    public String estadoAgente() {
        return agente.estado();
    }

    /** Modula a un tono vecino sin alejarse demasiado de la tonalidad original. */
    private void modular(Ajustes a) {
        int salto = MODULACIONES[Azar.entre(0, MODULACIONES.length - 1)];
        int nueva = tonicaActual + salto;
        if (nueva - a.raizBase > MARGEN_TONICA || a.raizBase - nueva > MARGEN_TONICA) {
            nueva = a.raizBase;
        }
        tonicaActual = nueva;
        // En chill la modulacion puede venir con cambio de modo.
        if (a.varianteProgresion == VARIANTE_MODAL && a.modosAlternativos.length > 1) {
            modoActual = a.modosAlternativos[Azar.entre(0, a.modosAlternativos.length - 1)];
        }
    }

    // ------------------------------------------------------------------
    // Motivos: inventar, recordar, variar y exponer
    // ------------------------------------------------------------------

    /**
     * Inventa una frase.
     *
     * Si hay red cargada, propone ella y las guardas armonicas la contienen;
     * si no hay pesos, o si la frase no pasa el veto, decide el generador de
     * reglas de siempre. Ese respaldo es lo que hace que la red nunca pueda
     * dejar la musica peor de lo que ya estaba.
     */
    private Motivo generarMotivo() {
        if (improvisador != null) {
            Motivo m = generarMotivoConRed();
            if (m != null) {
                frasesDeRed++;
                return m;
            }
        }
        frasesDeReglas++;
        return generarMotivoConReglas();
    }

    /** Estadistica de cuanto manda la red frente a las reglas. */
    public String reparteFrases() {
        int total = frasesDeRed + frasesDeReglas;
        if (total == 0) {
            return "sin frases todavia";
        }
        return frasesDeRed + " de red y " + frasesDeReglas + " de reglas ("
                + (100 * frasesDeRed / total) + "% red)";
    }

    private Motivo generarMotivoConRed() {
        montarContexto();
        int mascara = mascaraDelAcorde();
        if (!improvisador.generar(contextoRed, mascara, modoActual)) {
            return null;
        }
        int n = improvisador.getLongitud();
        int[] grados = new int[n];
        int[] duraciones = new int[n];
        int[] huecos = new int[n];
        System.arraycopy(improvisador.getGrados(), 0, grados, 0, n);
        System.arraycopy(improvisador.getDuraciones(), 0, duraciones, 0, n);
        System.arraycopy(improvisador.getHuecos(), 0, huecos, 0, n);
        return new Motivo(grados, duraciones, huecos, n);
    }

    /** Notas del acorde actual como mascara de doce bits por clase de altura. */
    private int mascaraDelAcorde() {
        int[] intervalos = ajustes.tiposAcorde[tipoValido(ajustes)];
        int mascara = 0;
        int raiz = ((raizAcordeAbs - tonicaActual) % 12 + 12) % 12;
        for (int i = 0; i < intervalos.length; i++) {
            mascara |= 1 << ((raiz + intervalos[i]) % 12);
        }
        return mascara;
    }

    /**
     * Monta el vector de condicionamiento con el estado que el motor ya
     * tiene. Las dos mascaras de doce bits son lo mas valioso que lleva:
     * permiten que la red aprenda comportamiento relativo al acorde y a la
     * escala, que es lo que hace que doscientos mil parametros basten.
     */
    private void montarContexto() {
        float[] c = contextoRed;
        for (int i = 0; i < c.length; i++) {
            c[i] = 0;
        }
        int p = 0;
        Genero g = genero;
        // Una casilla por genero. Antes eran tres con un ternario que hacia
        // caer todo lo que no fuera chill ni jazz en la misma, asi que
        // caribena, guitarra y violin se condicionaban como si fueran
        // clasica. Va por ordinal: anadir un genero al enum solo obliga a
        // subir GENEROS_CONTEXTO y reentrenar.
        c[p + limitar(g.ordinal(), 0, GENEROS_CONTEXTO - 1)] = 1;
        p += GENEROS_CONTEXTO;
        c[p + limitar(seccionActual, 0, 4)] = 1;
        p += 5;
        c[p + limitar(tipoAcordeActual, 0, 7)] = 1;
        p += 8;
        c[p + (((raizAcordeAbs - tonicaActual) % 12 + 12) % 12)] = 1;
        p += 12;
        c[p + limitar(modoActual.length - 5, 0, 5)] = 1;
        p += 6;
        int mascaraAcorde = mascaraDelAcorde();
        for (int i = 0; i < 12; i++) {
            c[p + i] = ((mascaraAcorde >> i) & 1);
        }
        p += 12;
        for (int i = 0; i < modoActual.length && i < 12; i++) {
            c[p + (modoActual[i] % 12)] = 1;
        }
        p += 12;
        c[p + (((indiceCorchea % 8) + 8) % 8)] = 1;
        p += 8;
        long ahora = System.nanoTime();
        c[p] = (float) densidad(ahora);
        c[p + 1] = desvioRegistro(ahora) / 12f;
        c[p + 2] = desvioVelocidad(ahora) / 16f;
        p += 3;
        c[p] = limitar(reexposicion, 0, 8) / 8f;
        c[p + 1] = ultimaDireccionMotivo;
        p += 2;
        c[p] = (float) energiaVisual;
    }

    private Motivo generarMotivoConReglas() {
        Ajustes aj = ajustes;
        int n = Azar.entre(aj.motivoMinNotas, aj.motivoMaxNotas);
        int[] grados = new int[n];
        int[] duraciones = new int[n];
        int[] huecos = new int[n];
        int g = Azar.entre(0, 2);
        for (int i = 0; i < n; i++) {
            grados[i] = g;
            int salto = Azar.probabilidad(PROB_GRADO_CONJUNTO) ? Azar.entre(1, 2) : Azar.entre(3, 4);
            if (Azar.probabilidad(0.45)) {
                salto = -salto;
            }
            g = limitar(g + salto, MOTIVO_GRADO_MIN, MOTIVO_GRADO_MAX);
            duraciones[i] = MOTIVO_DURACIONES[Azar.entre(0, MOTIVO_DURACIONES.length - 1)];
            huecos[i] = duraciones[i];
            if (Azar.probabilidad(PROB_HUECO_EXTRA)) {
                huecos[i] += Azar.entre(1, 2);
            }
        }
        return new Motivo(grados, duraciones, huecos, n);
    }

    /** Guarda el motivo en la memoria circular para poder recuperarlo luego. */
    private void recordarMotivo(Motivo m) {
        if (memoriaUsada < MEMORIA_MOTIVOS) {
            memoriaMotivos[memoriaUsada] = m;
            memoriaUsada++;
        } else {
            memoriaMotivos[Azar.entre(0, MEMORIA_MOTIVOS - 1)] = m;
        }
    }

    /**
     * Elige el material de la seccion: unas veces inventa una frase nueva y
     * otras rescata una de antes, que es lo que da sensacion de obra y no de
     * sucesion de ideas sueltas.
     */
    private void prepararMotivoDeSeccion(Ajustes a) {
        if (memoriaUsada > 0
                && Azar.probabilidad(agente.ajustar(AgenteMusical.RECORDAR, PROB_RECORDAR_MOTIVO))) {
            motivoSeccion = memoriaMotivos[Azar.entre(0, memoriaUsada - 1)];
        } else {
            motivoSeccion = generarMotivo();
            recordarMotivo(motivoSeccion);
        }
        reexposicion = 0;
    }

    /** Copia de trabajo de un array de enteros. */
    private static int[] copiar(int[] origen, int longitud) {
        int[] destino = new int[longitud];
        for (int i = 0; i < longitud; i++) {
            destino[i] = origen[i];
        }
        return destino;
    }

    /**
     * Construye la variante que toca ahora sobre el reproductor indicado.
     * La primera exposicion de cada ciclo va literal, para que el oido fije la
     * frase; a partir de ahi se le aplican una o dos transformaciones.
     */
    private void construirVariante(Reproductor r, Motivo m, int numero) {
        int n = m.longitud;
        int[] g = copiar(m.grados, n);
        int[] d = copiar(m.duraciones, n);
        int[] h = copiar(m.huecos, n);

        boolean literal = (numero % REEXPOSICIONES_POR_CICLO) < REEXPOSICIONES_LITERALES;
        int cuantas = literal ? 0 : (Azar.probabilidad(PROB_SEGUNDA_VARIACION) ? 2 : 1);
        for (int paso = 0; paso < cuantas; paso++) {
            int tipo = Azar.entre(0, NUM_VARIACIONES - 1);
            if (tipo == VAR_INVERSION) {
                int eje = g[0];
                for (int i = 0; i < n; i++) {
                    g[i] = limitar(eje - (g[i] - eje), MOTIVO_GRADO_MIN, MOTIVO_GRADO_MAX);
                }
            } else if (tipo == VAR_RETROGRADO) {
                for (int i = 0; i < n / 2; i++) {
                    int t = g[i];
                    g[i] = g[n - 1 - i];
                    g[n - 1 - i] = t;
                    t = d[i];
                    d[i] = d[n - 1 - i];
                    d[n - 1 - i] = t;
                    t = h[i];
                    h[i] = h[n - 1 - i];
                    h[n - 1 - i] = t;
                }
            } else if (tipo == VAR_OCTAVA) {
                int cual = Azar.entre(0, n - 1);
                int direccion = Azar.probabilidad(0.5) ? 1 : -1;
                g[cual] = limitar(g[cual] + direccion * modoActual.length,
                        MOTIVO_GRADO_MIN - 7, MOTIVO_GRADO_MAX + 7);
            } else if (tipo == VAR_TRUNCAMIENTO) {
                if (n > 2) {
                    n = Math.max(2, n - Azar.entre(1, 2));
                }
            } else if (tipo == VAR_EXTENSION) {
                int extra = Azar.entre(1, 2);
                if (n + extra <= MOTIVO_TOPE_NOTAS) {
                    int[] g2 = new int[n + extra];
                    int[] d2 = new int[n + extra];
                    int[] h2 = new int[n + extra];
                    for (int i = 0; i < n; i++) {
                        g2[i] = g[i];
                        d2[i] = d[i];
                        h2[i] = h[i];
                    }
                    int direccion = (n > 1 && g[n - 1] >= g[n - 2]) ? 1 : -1;
                    for (int i = 0; i < extra; i++) {
                        g2[n + i] = limitar(g2[n + i - 1] + direccion * Azar.entre(1, 2),
                                MOTIVO_GRADO_MIN, MOTIVO_GRADO_MAX);
                        d2[n + i] = MOTIVO_DURACIONES[Azar.entre(0, MOTIVO_DURACIONES.length - 1)];
                        h2[n + i] = d2[n + i];
                    }
                    g = g2;
                    d = d2;
                    h = h2;
                    n = n + extra;
                }
            } else if (tipo == VAR_ORNAMENTO) {
                if (n + 1 <= MOTIVO_TOPE_NOTAS) {
                    int cual = Azar.entre(0, n - 1);
                    int[] g2 = new int[n + 1];
                    int[] d2 = new int[n + 1];
                    int[] h2 = new int[n + 1];
                    for (int i = 0; i <= cual; i++) {
                        g2[i] = g[i];
                        d2[i] = d[i];
                        h2[i] = h[i];
                    }
                    // Nota de paso o bordadura, corta, robada a la nota anterior.
                    g2[cual + 1] = limitar(g[cual] + (Azar.probabilidad(0.5) ? 1 : -1),
                            MOTIVO_GRADO_MIN, MOTIVO_GRADO_MAX);
                    d2[cual + 1] = 1;
                    h2[cual + 1] = 1;
                    if (h2[cual] > 1) {
                        h2[cual] = h2[cual] - 1;
                    }
                    for (int i = cual + 1; i < n; i++) {
                        g2[i + 1] = g[i];
                        d2[i + 1] = d[i];
                        h2[i + 1] = h[i];
                    }
                    g = g2;
                    d = d2;
                    h = h2;
                    n = n + 1;
                }
            } else if (tipo == VAR_AUMENTACION) {
                for (int i = 0; i < n; i++) {
                    d[i] = Math.min(8, d[i] * 2);
                    h[i] = Math.min(10, h[i] * 2);
                }
            } else if (tipo == VAR_DISMINUCION) {
                for (int i = 0; i < n; i++) {
                    d[i] = Math.max(1, d[i] / 2);
                    h[i] = Math.max(1, h[i] / 2);
                }
            }
        }
        r.grados = g;
        r.duraciones = d;
        r.huecos = h;
        r.longitud = n;
        r.indice = 0;
    }

    /** Duracion de una unidad ritmica, con swing si el genero lo pide. */
    private static long unidadNs(Ajustes a, long contador) {
        double fraccion = 1.0;
        if (a.swing != 0.5) {
            fraccion = (contador % 2 == 0) ? a.swing * 2.0 : (1.0 - a.swing) * 2.0;
        }
        long ns = (long) (a.unidadMs * fraccion * MS_A_NS);
        return ns > 0 ? ns : MS_A_NS;
    }

    /**
     * Arranca una reexposicion del motivo: elige sobre que nota del acorde se
     * apoya, la registra segun la seccion y prepara la variante que toca.
     */
    private void nuevaExposicionMotivo(Ajustes a, long ahoraNs) {
        if (motivoSeccion == null) {
            prepararMotivoDeSeccion(a);
        }
        construirVariante(repMotivo, motivoSeccion, reexposicion);
        // Transporte a una nota del acorde: fundamental, tercera o quinta
        // diatonicas, siempre dentro de la escala en curso.
        int apoyo = 2 * Azar.entre(0, 2);
        repMotivo.gradoBase = gradoRaizAcorde + apoyo;
        repMotivo.canal = CANAL_MOTIVO;
        repMotivo.velocidadBase = a.velMotivo;
        repMotivo.activo = true;
        // Desplazamiento ritmico: a veces la frase entra un poco mas tarde.
        long retraso = 0;
        if (reexposicion > 0 && Azar.probabilidad(0.35)) {
            retraso = unidadNs(a, repMotivo.unidad);
        }
        repMotivo.proximaNs = ahoraNs + retraso;
        reexposicion++;
    }

    /**
     * Toca la nota que le toca al reproductor y programa la siguiente.
     *
     * @return true si la frase ha terminado con esta nota.
     */
    private boolean avanzarReproductor(Ajustes a, Reproductor r, long ahoraNs, int registro) {
        if (r.indice >= r.longitud) {
            r.activo = false;
            return true;
        }
        int i = r.indice;
        int raiz = tonicaActual + registro;
        int nota = notaDeModo(raiz, r.gradoBase + r.grados[i], modoActual);
        nota = corregirAlAcorde(nota);
        double articulacion = ARTICULACIONES[Azar.entre(0, ARTICULACIONES.length - 1)];
        int duracion = (int) (r.duraciones[i] * a.unidadMs * articulacion);
        if (duracion < PASO_MS * 2) {
            duracion = PASO_MS * 2;
        }
        int velocidad = velocidadDeFrase(r.velocidadBase + desvioVelocidad(ahoraNs), i, r.longitud);
        notaOn(r.canal, nota, velocidad, duracion, ahoraNs);
        if (i > 0) {
            ultimaDireccionMotivo = r.grados[i] >= r.grados[i - 1] ? 1 : -1;
        }
        // El hueco hasta la nota siguiente sale de duracion mas extra, asi que
        // una nota larga arrastra un silencio largo. En una linea que debe
        // fluir eso la parte en notas sueltas, de ahi el tope por genero.
        int hueco = r.huecos[i];
        if (a.maxHueco > 0 && hueco > a.maxHueco) {
            hueco = a.maxHueco;
        }
        r.proximaNs = ahoraNs + sumaUnidadesNs(a, r.unidad, hueco);
        r.unidad += hueco;
        r.indice++;
        if (r.indice >= r.longitud) {
            r.activo = false;
            return true;
        }
        return false;
    }

    /** Suma la duracion de varias unidades seguidas, respetando el swing. */
    private static long sumaUnidadesNs(Ajustes a, long desde, int cuantas) {
        long total = 0;
        int n = cuantas > 0 ? cuantas : 1;
        for (int i = 0; i < n; i++) {
            total += unidadNs(a, desde + i);
        }
        return total;
    }

    /**
     * Motor del motivo: si hay frase en curso toca su nota, y si ha terminado
     * programa la siguiente reexposicion tras una pausa que depende de lo densa
     * que sea la seccion.
     */
    private void motorMotivo(Ajustes a, long ahoraNs) {
        if (!repMotivo.activo) {
            // Fuera de las secciones con motivo no se empieza ninguna frase
            // nueva, pero la que estuviera en curso siempre se deja acabar.
            if (!capaMotivoActiva()) {
                return;
            }
            if (repMotivo.proximaNs == 0 || ahoraNs - repMotivo.proximaNs >= 0) {
                nuevaExposicionMotivo(a, ahoraNs);
            }
            return;
        }
        if (ahoraNs - repMotivo.proximaNs < 0) {
            return;
        }
        int registro = a.octavaMotivo + desvioRegistro(ahoraNs);
        boolean fin = avanzarReproductor(a, repMotivo, ahoraNs + jitterNs(), registro);
        if (fin) {
            // La respuesta del contracanto sale del final de la frase recien dicha.
            if (a.estiloContra == CONTRA_RESPUESTA && capaContraActiva()) {
                prepararRespuesta(a, ahoraNs);
            }
            double d = densidad(ahoraNs);
            int pausa = (int) Math.round(a.pausaMotivoMax - (a.pausaMotivoMax - a.pausaMotivoMin) * d);
            pausa = limitar(pausa, a.pausaMotivoMin, a.pausaMotivoMax + 2);
            repMotivo.proximaNs = ahoraNs + sumaUnidadesNs(a, repMotivo.unidad, pausa);
            repMotivo.unidad += pausa;
        }
    }

    // ------------------------------------------------------------------
    // Contracanto: la cuarta capa melodica
    // ------------------------------------------------------------------

    /** Prepara la respuesta del jazz: las ultimas notas del motivo, contestadas. */
    private void prepararRespuesta(Ajustes a, long ahoraNs) {
        int total = repMotivo.longitud;
        if (total <= 0) {
            return;
        }
        int cuantas = Math.min(RESPUESTA_NOTAS, total);
        int desde = total - cuantas;
        int[] g = new int[cuantas];
        int[] d = new int[cuantas];
        int[] h = new int[cuantas];
        for (int i = 0; i < cuantas; i++) {
            g[i] = repMotivo.grados[desde + i];
            d[i] = repMotivo.duraciones[desde + i];
            h[i] = repMotivo.huecos[desde + i];
        }
        repContra.grados = g;
        repContra.duraciones = d;
        repContra.huecos = h;
        repContra.longitud = cuantas;
        repContra.indice = 0;
        // El registro ya baja una octava por octavaContra; restar otra dejaria
        // la respuesta metida dentro del bajo caminante.
        repContra.gradoBase = repMotivo.gradoBase;
        repContra.canal = CANAL_CONTRA;
        repContra.velocidadBase = a.velContra;
        repContra.unidad = repMotivo.unidad;
        repContra.activo = true;
        repContra.proximaNs = ahoraNs + sumaUnidadesNs(a, repContra.unidad, RESPUESTA_RETARDO);
    }

    /** Motor del contracanto por frases (solo lo usa la respuesta del jazz). */
    private void motorContra(Ajustes a, long ahoraNs) {
        if (!repContra.activo) {
            return;
        }
        // La respuesta ya empezada se deja terminar aunque la seccion cambie.
        if (ahoraNs - repContra.proximaNs < 0) {
            return;
        }
        avanzarReproductor(a, repContra, ahoraNs + jitterNs(), a.octavaContra + desvioRegistro(ahoraNs));
    }

    /**
     * Contracanto ligado al acorde: brillo sostenido en chill y linea larga en
     * movimiento contrario en clasica. Se llama al cambiar de acorde.
     */
    private void contracantoDeAcorde(Ajustes a, int[] intervalos, int duracion, long ahoraNs) {
        if (!capaContraActiva()) {
            return;
        }
        int velocidad = a.velContra + desvioVelocidad(ahoraNs);
        if (a.estiloContra == CONTRA_BRILLO) {
            // Dos notas altas del acorde, muy flojas: solo un halo.
            int base = raizAcordeAbs + a.octavaContra;
            for (int i = 0; i < BRILLO_NOTAS && i < intervalos.length; i++) {
                int cual = intervalos[(i * 2) % intervalos.length];
                int nota = limitar(base + cual, 0, 127);
                notaOn(CANAL_CONTRA, nota, velocidadHumana(velocidad),
                        duracion + a.solapeMs, ahoraNs + jitterNs());
            }
        } else if (a.estiloContra == CONTRA_LINEA) {
            // Linea que va al reves que el motivo, buscando una nota del acorde.
            int centro = tonicaActual + a.octavaContra;
            int direccion = -ultimaDireccionMotivo;
            int grado = gradoRaizAcorde + (direccion > 0 ? 2 : -2);
            int nota = ajustarRegistro(notaDeModo(tonicaActual + a.octavaContra, grado, modoActual), centro);
            nota = corregirAlAcorde(nota);
            notaOn(CANAL_CONTRA, nota, velocidadHumana(velocidad),
                    (int) (duracion * 0.85), ahoraNs + jitterNs());
        }
    }

    // ------------------------------------------------------------------
    // Armonia: progresion, variantes y conduccion de voces
    // ------------------------------------------------------------------

    /** Copia de trabajo de la progresion del genero, que luego se varia por pasadas. */
    private void reiniciarProgresion(Ajustes a) {
        int n = a.progresion.length;
        int[][] copia = new int[n][2];
        for (int i = 0; i < n; i++) {
            copia[i][0] = a.progresion[i][0];
            copia[i][1] = a.progresion[i][1];
        }
        progresionActual = copia;
    }

    /**
     * Retoca la progresion al empezar cada pasada, de manera que dos vueltas
     * nunca sean iguales: sustitucion de tritono en jazz, acordes prestados en
     * clasica y cambio de modo en chill.
     */
    private void variarProgresion(Ajustes a) {
        reiniciarProgresion(a);
        if (a.varianteProgresion == VARIANTE_NINGUNA || !Azar.probabilidad(PROB_VARIAR_PROGRESION)) {
            return;
        }
        int n = progresionActual.length;
        if (a.varianteProgresion == VARIANTE_TRITONO) {
            // Un dominante al azar se sustituye por el que esta a un tritono.
            for (int intento = 0; intento < n; intento++) {
                int i = Azar.entre(0, n - 1);
                if (progresionActual[i][1] == 1) {
                    progresionActual[i][0] = (progresionActual[i][0] + 6) % 12;
                    break;
                }
            }
            // Y de vez en cuando un giro de vuelta: el ultimo acorde se hace dominante.
            if (Azar.probabilidad(0.40) && a.tiposAcorde.length > 1) {
                progresionActual[n - 1][1] = 1;
            }
        } else if (a.varianteProgresion == VARIANTE_PRESTADO) {
            // Un acorde cambia de modo: mayor por menor o al reves.
            int i = Azar.entre(0, n - 2);
            if (progresionActual[i][1] == 0) {
                progresionActual[i][1] = 1;
            } else if (progresionActual[i][1] == 1) {
                progresionActual[i][1] = 0;
            }
        } else if (a.varianteProgresion == VARIANTE_MODAL) {
            // En chill la variante es de color: se cambia el modo de la escala.
            if (a.modosAlternativos.length > 1 && Azar.probabilidad(0.50)) {
                modoActual = a.modosAlternativos[Azar.entre(0, a.modosAlternativos.length - 1)];
            }
        }
    }

    /** Indice de paso valido, por si un cambio de genero dejo uno de la progresion anterior. */
    private int pasoValido() {
        int p = pasoProgresion;
        return (p >= 0 && p < progresionActual.length) ? p : 0;
    }

    private int tipoValido(Ajustes a) {
        int t = tipoAcordeActual;
        return (t >= 0 && t < a.tiposAcorde.length) ? t : 0;
    }

    /**
     * Coloca el acorde nuevo buscando la inversion que menos mueve las voces
     * respecto al anterior. Es lo que separa un encadenado profesional de un
     * salto en bloque.
     */
    private int[] conducirVoces(int[] intervalos, int baseIdeal, int[] previas) {
        int n = intervalos.length;
        if (n == 0) {
            return new int[0];
        }
        if (previas.length == 0) {
            int[] simple = new int[n];
            for (int i = 0; i < n; i++) {
                simple[i] = limitar(baseIdeal + intervalos[i], 0, 127);
            }
            ordenarNotas(simple);
            return simple;
        }
        // Se prueban todas las inversiones: cada una reparte las notas del
        // acorde entre las voces de otra manera. Gana la que menos las mueve.
        int[] mejor = null;
        int mejorCoste = Integer.MAX_VALUE;
        for (int rotacion = 0; rotacion < n; rotacion++) {
            int[] intento = new int[n];
            int coste = 0;
            for (int voz = 0; voz < n; voz++) {
                int referencia = previas[Math.min(voz, previas.length - 1)];
                int nota = baseIdeal + intervalos[(voz + rotacion) % n];
                while (nota - referencia > 6) {
                    nota -= 12;
                }
                while (referencia - nota > 6) {
                    nota += 12;
                }
                intento[voz] = limitar(nota, 0, 127);
                coste += Math.abs(intento[voz] - referencia);
            }
            if (coste < mejorCoste) {
                mejorCoste = coste;
                mejor = intento;
            }
        }
        ordenarNotas(mejor);
        return mejor;
    }

    /** Ordena de grave a agudo para que las voces no se crucen. */
    private static void ordenarNotas(int[] notas) {
        for (int i = 1; i < notas.length; i++) {
            int valor = notas[i];
            int j = i - 1;
            while (j >= 0 && notas[j] > valor) {
                notas[j + 1] = notas[j];
                j--;
            }
            notas[j + 1] = valor;
        }
    }

    /** Avanza la progresion, monta el acorde nuevo y arranca lo que dependa de el. */
    private void cambiarAcorde(Ajustes a, long ahoraNs) {
        // La seccion solo cambia en una junta de la armonia, nunca a media frase.
        if (ahoraNs - finSeccionNs >= 0) {
            avanzarSeccion(a, ahoraNs);
        }

        int paso = pasoValido() + 1;
        if (paso >= progresionActual.length) {
            paso = 0;
            pasadasProgresion++;
            variarProgresion(a);
        }
        pasoProgresion = paso;

        int gradoRaiz = progresionActual[paso][0];
        int tipo = progresionActual[paso][1];
        if (tipo < 0 || tipo >= a.tiposAcorde.length) {
            tipo = 0;
        }
        tipoAcordeActual = tipo;
        raizAcordeAbs = tonicaActual + gradoRaiz;
        int gradoEscala = gradoDeSemitono(gradoRaiz, modoActual);
        gradoRaizAcorde = gradoEscala >= 0 ? gradoEscala : 0;
        pulsoEnAcorde = 0;

        int duracion = Azar.entre(a.acordeMinMs, a.acordeMaxMs);
        duracionAcordeMs = duracion;
        inicioAcordeNs = ahoraNs;
        proximoAcordeNs = ahoraNs + (long) duracion * MS_A_NS;

        int basePad = raizAcordeAbs + a.octavaPad;
        if (a.normalizarRegistro) {
            basePad = ajustarRegistro(basePad, a.raizBase + a.octavaPad);
        }
        basePadActual = basePad;

        // Retardo: sobre la tonica se suspende la tercera y se resuelve dentro
        // del propio acorde.
        int[] intervalos = a.tiposAcorde[tipo];
        boolean suspendido = a.usaSuspension && gradoRaiz == 0 && a.tipoSus4 >= 0
                && a.tipoSus4 < a.tiposAcorde.length && Azar.probabilidad(a.probSuspension);
        if (suspendido) {
            intervalos = a.tiposAcorde[a.tipoSus4];
        }
        calcularAlteraciones(intervalos);

        if (a.estiloArmonia == ARMONIA_RASGUEO) {
            // Nada suena aqui: se deja preparado y lo desgrana el bucle.
            acordeSonando = new int[0];
            notasPadPrevias = conducirVoces(intervalos, basePad, notasPadPrevias);
            prepararRasgueo(notasPadPrevias, a.velPad + desvioVelocidad(ahoraNs),
                    (int) (duracion * 0.9), Azar.probabilidad(0.72));
        } else if (a.estiloArmonia == ARMONIA_SOSTENIDA) {
            tocarAcordeConducido(a, intervalos, basePad, duracion, ahoraNs);
        } else {
            // El comping no sostiene nada: los ataques van por la rejilla.
            acordeSonando = new int[0];
            notasPadPrevias = conducirVoces(intervalos, basePad, notasPadPrevias);
        }

        if (suspendido) {
            notaSuspension = limitar(basePad + INTERVALO_CUARTA, 0, 127);
            notaResolucion = limitar(basePad + INTERVALO_TERCERA, 0, 127);
            resolucionNs = ahoraNs + (long) (duracion * a.fraccionSuspension) * MS_A_NS;
            suspensionPendiente = true;
        } else {
            suspensionPendiente = false;
        }

        if (a.estiloBajo != BAJO_CAMINANTE && capaBajoActiva(ahoraNs)) {
            tocarBajoDeAcorde(a, ahoraNs, duracion);
        }
        if (a.estiloTextura == TEXTURA_ARPEGIO) {
            construirArpegio(a, intervalos);
        }
        contracantoDeAcorde(a, intervalos, duracion, ahoraNs);
    }

    /**
     * Ataca el acorde sostenido con conduccion de voces: las notas que ya
     * sonaban se prolongan en lugar de volver a atacarse, y las nuevas entran
     * con su reguladorcito de entrada.
     */
    private void tocarAcordeConducido(Ajustes a, int[] intervalos, int basePad, int duracion, long ahoraNs) {
        int[] notas = conducirVoces(intervalos, basePad, notasPadPrevias);
        long finNs = ahoraNs + (long) (duracion + a.solapeMs) * MS_A_NS;
        int velocidad = a.velPad + desvioVelocidad(ahoraNs);
        for (int i = 0; i < notas.length; i++) {
            if (estaSonando(CANAL_PAD, notas[i])) {
                // Nota comun: se sostiene, no se vuelve a percutir.
                prolongarNota(CANAL_PAD, notas[i], finNs);
            } else {
                notaOn(CANAL_PAD, notas[i], velocidadHumana(velocidad),
                        duracion + a.solapeMs, ahoraNs + jitterNs());
            }
        }
        acordeSonando = notas;
        notasPadPrevias = notas;
    }

    /** Cambia la cuarta suspendida por la tercera sin cortar el resto del acorde. */
    private void resolverSuspension(Ajustes a, long ahoraNs) {
        suspensionPendiente = false;
        apagarNota(CANAL_PAD, notaSuspension);
        long restanteNs = proximoAcordeNs - ahoraNs + (long) a.solapeMs * MS_A_NS;
        int restanteMs = (int) (restanteNs / MS_A_NS);
        if (restanteMs < PASO_MS) {
            restanteMs = PASO_MS;
        }
        notaOn(CANAL_PAD, notaResolucion, velocidadHumana(a.velPad + desvioVelocidad(ahoraNs)),
                restanteMs, ahoraNs);
        int[] acorde = acordeSonando;
        int[] nuevo = new int[acorde.length];
        for (int i = 0; i < acorde.length; i++) {
            nuevo[i] = acorde[i] == notaSuspension ? notaResolucion : acorde[i];
        }
        acordeSonando = nuevo;
        notasPadPrevias = nuevo;
    }

    // ------------------------------------------------------------------
    // Bajo
    // ------------------------------------------------------------------

    /** Bajo de los generos sin caminante: fundamental al empezar el acorde. */
    private void tocarBajoDeAcorde(Ajustes a, long ahoraNs, int duracionAcorde) {
        int centro = a.raizBase + a.octavaBajo;
        int nota = raizAcordeAbs + a.octavaBajo;
        if (a.normalizarRegistro) {
            nota = ajustarRegistro(nota, centro);
        }
        int duracion = a.bajoDuracionMs > 0 ? a.bajoDuracionMs : duracionAcorde;
        notaBajoActual = nota;
        notaOn(CANAL_BAJO, limitar(nota, 0, 127),
                velocidadHumana(a.velBajo + desvioVelocidad(ahoraNs)), duracion, ahoraNs + jitterNs());
        bajoMedioPendiente = Azar.probabilidad(a.probBajoMedioAcorde * densidad(ahoraNs));
        bajoMedioAcordeNs = ahoraNs + (long) (duracionAcorde / 2) * MS_A_NS;
    }

    /** Segundo apoyo del bajo a mitad de acorde: fundamental u otra nota del acorde. */
    private void tocarBajoMedioAcorde(Ajustes a, long ahoraNs) {
        bajoMedioPendiente = false;
        int[] tonos = a.tiposAcorde[tipoValido(a)];
        int grado = a.gradoBajoMedio < tonos.length ? a.gradoBajoMedio : tonos.length - 1;
        int intervalo = Azar.probabilidad(PROB_BAJO_MEDIO_FUNDAMENTAL) ? tonos[0] : tonos[grado];
        int centro = a.raizBase + a.octavaBajo;
        int nota = raizAcordeAbs + a.octavaBajo + intervalo;
        if (a.normalizarRegistro) {
            nota = ajustarRegistro(nota, centro);
        }
        int duracion = a.bajoDuracionMs > 0 ? a.bajoDuracionMs : duracionAcordeMs / 2;
        notaOn(CANAL_BAJO, limitar(nota, 0, 127),
                velocidadHumana(a.velBajo + DESVIO_VEL_BAJO_MEDIO + desvioVelocidad(ahoraNs)),
                duracion, ahoraNs + jitterNs());
    }

    /** Negra del bajo caminante: fundamental, nota del acorde o grado conjunto. */
    /**
     * Tumbao: el bajo del son.
     *
     * Lo que lo define no son las notas sino donde NO caen. El tiempo fuerte
     * se calla y el peso va al cuatro y al "y de dos"; ademas la nota del
     * acorde siguiente se adelanta medio compas, que es la anticipacion que
     * empuja la musica hacia delante. Sin eso son notas correctas sin sabor.
     */
    private void tocarBajoTumbao(Ajustes a, long ahoraNs) {
        int celda = indiceCorchea % 8;
        // Silencio en el uno, ataque en el "y de dos" (3) y en el cuatro (6).
        if (celda != 3 && celda != 6) {
            return;
        }
        int base = tonicaActual + a.octavaBajo;
        int nota;
        if (celda == 3) {
            nota = base + gradoRaizAcorde12();
        } else {
            // El cuatro anticipa la fundamental del acorde que viene.
            int paso = (pasoProgresion + 1) % a.progresion.length;
            nota = base + a.progresion[paso][0];
        }
        if (a.normalizarRegistro) {
            nota = ajustarRegistro(nota, base);
        }
        int dur = (int) (a.pulsoMs * 0.85);
        notaOn(CANAL_BAJO, nota, velocidadHumana(a.velBajo + desvioVelocidad(ahoraNs)),
                dur, ahoraNs);
        notaBajoActual = nota;
    }

    /**
     * Prepara un rasgueo: guarda el acorde para desgranarlo cuerda a cuerda.
     *
     * No se puede atacar y ya esta: lo que distingue a una guitarra de un
     * organo es que las cuerdas no suenan a la vez. El motor sondea cada
     * 25 ms, que resulta ser justo el extremo rapido de un rasgueo de verdad
     * (los rapidos van sobre 30 ms por cuerda), asi que se emite una cuerda
     * por vuelta del bucle en vez de intentar un retardo mas fino que la
     * rejilla no puede dar.
     *
     * @param arriba true de grave a agudo, que es el golpe hacia abajo.
     */
    private void prepararRasgueo(int[] voces, int velocidad, int duracionMs,
            boolean arriba) {
        int n = Math.min(voces.length, CUERDAS_RASGUEO);
        if (n <= 0) {
            return;
        }
        rasgueoNotas = new int[n];
        for (int i = 0; i < n; i++) {
            rasgueoNotas[i] = arriba ? voces[i] : voces[n - 1 - i];
        }
        rasgueoIndice = 0;
        rasgueoVelocidad = velocidad;
        rasgueoDuracionMs = duracionMs;
    }

    /** Emite la siguiente cuerda del rasgueo pendiente, si la hay. */
    private void avanzarRasgueo(long ahoraNs) {
        if (rasgueoNotas == null || rasgueoIndice >= rasgueoNotas.length) {
            return;
        }
        // Las cuerdas graves del golpe pegan algo mas fuerte.
        int vel = rasgueoVelocidad - rasgueoIndice;
        notaOn(CANAL_PAD, rasgueoNotas[rasgueoIndice],
                velocidadHumana(Math.max(1, vel)), rasgueoDuracionMs, ahoraNs);
        rasgueoIndice++;
        if (rasgueoIndice >= rasgueoNotas.length) {
            rasgueoNotas = null;
        }
    }

    /** Semitonos de la fundamental del acorde respecto de la tonica. */
    private int gradoRaizAcorde12() {
        return ((raizAcordeAbs - tonicaActual) % 12 + 12) % 12;
    }

    /**
     * Montuno: el piano del son.
     *
     * Arpegio de dos manos sobre el acorde ya conducido, con las notas
     * repartidas en octavas y cayendo en contratiempo. Se apoya en
     * notasPadPrevias, que ya trae la conduccion de voces hecha, asi que el
     * montuno hereda gratis el movimiento minimo entre acordes.
     */
    private void montuno(Ajustes a, long ahoraNs) {
        int[] voces = notasPadPrevias;
        if (voces == null || voces.length == 0) {
            return;
        }
        int celda = indiceCorchea % 8;
        // Patron clasico: las corcheas de contratiempo mas el uno.
        if (celda != 0 && celda != 3 && celda != 5 && celda != 6) {
            return;
        }
        int indice;
        int octava;
        switch (celda) {
            case 0:
                indice = 0;
                octava = 0;
                break;
            case 3:
                indice = 1;
                octava = 12;
                break;
            case 5:
                indice = 2;
                octava = 0;
                break;
            default:
                indice = 1;
                octava = 12;
                break;
        }
        int nota = voces[indice % voces.length] + octava;
        int vel = velocidadHumana(a.velPad + desvioVelocidad(ahoraNs)
                + (celda == 0 ? 4 : -3));
        notaOn(CANAL_PAD, nota, vel, (int) (a.pulsoMs * 0.55), ahoraNs);
        // La segunda voz, una tercera por encima, da el sonido de dos manos.
        if (celda == 3 || celda == 6) {
            int otra = voces[(indice + 1) % voces.length] + octava;
            notaOn(CANAL_PAD, otra, Math.max(1, vel - 6),
                    (int) (a.pulsoMs * 0.45), ahoraNs);
        }
    }

    /**
     * Clave y congas.
     *
     * Va por notaOn() y no por la via rapida de golpe(): asi entra en la tabla
     * de voces y apagarVencidas() le manda su NOTE_OFF. Es lo que mantiene
     * este patron separado de la maquinaria de los estallidos, que usa dos
     * ranuras globales sin exclusion y las pisaria.
     *
     * Las notas tampoco chocan: claves en 75 y congas en 63 y 64, frente al
     * bombo 36 y el tom 41 de los fuegos.
     */
    private void percusionClave(Ajustes a, long ahoraNs) {
        int celda = indiceCorchea % CICLO_CLAVE;
        for (int i = 0; i < CLAVE_32.length; i++) {
            if (CLAVE_32[i] == celda) {
                notaOn(CANAL_PERCUSION, NOTA_CLAVE, velocidadHumana(VEL_CLAVE),
                        DURACION_PERCUSION_MS, ahoraNs);
                break;
            }
        }
        // Campana en cada tiempo: es el motor de la descarga.
        if (celda % 2 == 0) {
            // El primero de cada compas pega mas: marca el ciclo.
            int vel = (celda % 8 == 0) ? VEL_CAMPANA + 8 : VEL_CAMPANA;
            notaOn(CANAL_PERCUSION, NOTA_CAMPANA, velocidadHumana(vel),
                    DURACION_PERCUSION_MS, ahoraNs);
        }
        for (int i = 0; i < CONGA_TUMBAO.length; i++) {
            if (CONGA_TUMBAO[i] == celda) {
                // Las dos alturas alternan: es lo que hace que suene a tumbao
                // y no a un solo parche repetido.
                int nota = (i % 2 == 0) ? NOTA_CONGA_GRAVE : NOTA_CONGA_AGUDA;
                notaOn(CANAL_PERCUSION, nota, velocidadHumana(VEL_CONGA),
                        DURACION_PERCUSION_MS, ahoraNs);
                break;
            }
        }
    }

    private void tocarBajoCaminante(Ajustes a, long ahoraNs) {
        int centro = a.raizBase + a.octavaBajo;
        int fundamental = ajustarRegistro(raizAcordeAbs + a.octavaBajo, centro);
        int nota;
        if (pulsoEnAcorde == 0 || notaBajoActual <= 0) {
            nota = fundamental;
        } else if (Azar.probabilidad(PROB_BAJO_TONO_ACORDE)) {
            int[] tonos = a.tiposAcorde[tipoValido(a)];
            nota = ajustarRegistro(fundamental + tonos[Azar.entre(0, tonos.length - 1)], centro);
        } else {
            // Aproximacion por grados conjuntos a la fundamental del acorde siguiente.
            int[] siguiente = progresionActual[(pasoValido() + 1) % progresionActual.length];
            int destino = ajustarRegistro(tonicaActual + siguiente[0] + a.octavaBajo, centro);
            int direccion = destino >= notaBajoActual ? 1 : -1;
            nota = notaBajoActual + direccion * Azar.entre(1, 2);
            nota = limitar(nota, centro - BAJO_CAMINANTE_MARGEN, centro + BAJO_CAMINANTE_MARGEN);
        }
        notaBajoActual = nota;
        pulsoEnAcorde++;
        int duracion = (int) (a.pulsoMs * BAJO_CAMINANTE_DURACION);
        notaOn(CANAL_BAJO, limitar(nota, 0, 127),
                velocidadHumana(a.velBajo + desvioVelocidad(ahoraNs)), duracion, ahoraNs + jitterNs());
    }

    // ------------------------------------------------------------------
    // Textura interior y comping
    // ------------------------------------------------------------------

    /** Acorde desplegado en dos octavas, subiendo y bajando sin repetir extremos. */
    private void construirArpegio(Ajustes a, int[] intervalos) {
        int n = intervalos.length;
        if (n == 0) {
            arpegioNotas = new int[0];
            indiceArpegio = 0;
            return;
        }
        int base = ajustarRegistro(raizAcordeAbs + a.octavaTextura, a.raizBase + a.octavaTextura);
        int subida = n * ARPEGIO_OCTAVAS + 1;
        int[] notas = new int[subida * 2 - 2];
        int k = 0;
        for (int octava = 0; octava < ARPEGIO_OCTAVAS; octava++) {
            for (int i = 0; i < n; i++) {
                notas[k++] = limitar(base + octava * 12 + intervalos[i], 0, 127);
            }
        }
        notas[k++] = limitar(base + ARPEGIO_OCTAVAS * 12, 0, 127);
        for (int i = subida - 2; i >= 1; i--) {
            notas[k++] = notas[i];
        }
        arpegioNotas = notas;
        indiceArpegio = 0;
    }

    /** Una nota del arpegio por corchea, solo en las secciones que lo piden. */
    private void arpegiar(Ajustes a, long ahoraNs) {
        int[] notas = arpegioNotas;
        if (notas.length == 0 || !capaTexturaActiva()) {
            return;
        }
        int nota = notas[indiceArpegio % notas.length];
        indiceArpegio++;
        double silencio = a.probSilencioTextura + (1.0 - limitarReal(densidad(ahoraNs), 0.0, 1.0)) * 0.5;
        if (Azar.probabilidad(silencio)) {
            return;
        }
        int duracion = (int) (a.pulsoMs * ARPEGIO_DURACION_PULSOS);
        notaOn(CANAL_TEXTURA, nota,
                velocidadHumana(a.velTextura + desvioVelocidad(ahoraNs)), duracion, ahoraNs + jitterNs());
    }

    /**
     * Ataque de comping: tres notas seguidas del acorde sin la fundamental, que
     * ya lleva el bajo. Cae mas veces en contratiempo que en parte fuerte, que
     * es lo que da la sensacion de sincopa.
     */
    private void compear(Ajustes a, long ahoraNs, boolean fuerte) {
        double prob = (fuerte ? a.probCompFuerte : a.probCompDebil) * densidad(ahoraNs);
        if (!Azar.probabilidad(prob)) {
            return;
        }
        // Se toca sobre el acorde ya conducido, no sobre el bloque en paralelo:
        // asi las posiciones tambien se enlazan de un acorde al siguiente.
        int[] tonos = notasPadPrevias;
        if (tonos.length == 0) {
            return;
        }
        int inicio = 0;
        if (tonos.length - COMP_NOTAS >= 1) {
            inicio = Azar.entre(1, tonos.length - COMP_NOTAS);
        }
        int cuantas = Math.min(COMP_NOTAS, tonos.length - inicio);
        int duracion = (int) (a.pulsoMs * COMP_DURACION_PULSOS);
        int base = a.velPad + desvioVelocidad(ahoraNs) + (fuerte ? 0 : COMP_DESVIO_DEBIL);
        int velocidad = velocidadHumana(base);
        for (int i = 0; i < cuantas; i++) {
            int nota = limitar(tonos[inicio + i], 0, 127);
            notaOn(CANAL_PAD, nota, velocidad, duracion, ahoraNs + jitterNs());
        }
    }

    /** Duracion de la corchea segun el swing: la primera del par es la larga. */
    private static long duracionCorcheaNs(Ajustes a, int indice) {
        double fraccion = (indice % 2 == 0) ? a.swing : 1.0 - a.swing;
        long ns = (long) (a.pulsoMs * fraccion * MS_A_NS);
        return ns > 0 ? ns : PASO_MS * MS_A_NS;
    }

    /** Lo que toca hacer en cada corchea de la rejilla. */
    private void pulso(Ajustes a, long ahoraNs) {
        boolean fuerte = (indiceCorchea % 2) == 0;
        if (a.estiloBajo == BAJO_CAMINANTE && fuerte && capaBajoActiva(ahoraNs)) {
            tocarBajoCaminante(a, ahoraNs);
        }
        if (a.estiloBajo == BAJO_TUMBAO && capaBajoActiva(ahoraNs)) {
            tocarBajoTumbao(a, ahoraNs);
        }
        if (a.estiloArmonia == ARMONIA_COMPING) {
            compear(a, ahoraNs, fuerte);
        }
        if (a.estiloArmonia == ARMONIA_MONTUNO) {
            montuno(a, ahoraNs);
        }
        if (a.estiloPercusion == PERCUSION_CLAVE) {
            percusionClave(a, ahoraNs);
        }
        if (a.estiloTextura == TEXTURA_ARPEGIO) {
            arpegiar(a, ahoraNs);
        }
        long duracion = duracionCorcheaNs(a, indiceCorchea);
        indiceCorchea++;
        proximoPulsoNs += duracion;
        if (ahoraNs - proximoPulsoNs > 0) {
            // Se ha quedado atras (arranque o cambio de genero): se resincroniza.
            proximoPulsoNs = ahoraNs + duracion;
        }
    }

    // ------------------------------------------------------------------
    // Expresion: reguladores lentos sobre los acordes largos
    // ------------------------------------------------------------------

    /**
     * Dibuja un regulador (CC 11) sobre el acorde en curso: entra creciendo,
     * llega a su cima hacia la mitad y afloja al final. La seccion decide cuanto
     * cuerpo tiene el regulador, de modo que un RESPIRO suena mucho mas tenue.
     */
    private void actualizarExpresion(Ajustes a, long ahoraNs) {
        if (ahoraNs - proximaExpresionNs < 0) {
            return;
        }
        proximaExpresionNs = ahoraNs + (long) EXPRESION_PASO_MS * MS_A_NS;
        if (!a.expresionRegulada || duracionAcordeMs <= 0) {
            return;
        }
        long transcurrido = ahoraNs - inicioAcordeNs;
        double posicion = (double) transcurrido / (double) ((long) duracionAcordeMs * MS_A_NS);
        posicion = limitarReal(posicion, 0.0, 1.0);
        double curva = Math.sin(posicion * Math.PI);
        double d = limitarReal(densidad(ahoraNs), 0.0, 1.3);
        int valor = (int) Math.round(EXPRESION_MIN + (EXPRESION_MAX - EXPRESION_MIN) * curva * (0.55 + 0.45 * d));
        enviarControl(CANAL_PAD, CC_EXPRESION, limitar(valor, 0, 127));
        // El brillo del contracanto respira con el pad, un poco por detras.
        if (a.estiloContra == CONTRA_BRILLO) {
            enviarControl(CANAL_CONTRA, CC_EXPRESION, limitar(valor - 12, 0, 127));
        }
    }

    // ------------------------------------------------------------------
    // Arranque del estado musical
    // ------------------------------------------------------------------

    /** Deja el estado musical listo para empezar (arranque o cambio de genero). */
    private void reiniciarEstadoMusical(long ahoraNs) {
        for (int i = 0; i < MAX_VOCES; i++) {
            if (vozActiva[i]) {
                vozActiva[i] = false;
                enviar(ShortMessage.NOTE_OFF, vozCanal[i], vozNota[i], 0);
            }
        }
        Ajustes a = ajustes;
        acordeSonando = new int[0];
        notasPadPrevias = new int[0];
        tonicaActual = a.raizBase;
        modoActual = a.escala;
        reiniciarProgresion(a);
        pasoProgresion = progresionActual.length - 1;
        pasadasProgresion = 0;
        tipoAcordeActual = 0;
        gradoRaizAcorde = 0;
        raizAcordeAbs = a.raizBase;
        basePadActual = a.raizBase + a.octavaPad;
        alteracionCuenta = 0;
        duracionAcordeMs = a.acordeMinMs;
        inicioAcordeNs = ahoraNs;
        proximoAcordeNs = ahoraNs;

        // Solo la primera vez se abre con INTRO; al cambiar de genero se entra
        // directamente en TEMA, para que el cambio se note al instante.
        indiceSecuencia = arrancarEnIntro ? 0 : 1;
        arrancarEnIntro = false;
        seccionActual = SECUENCIA[indiceSecuencia];
        seccionPrevia = seccionActual;
        inicioSeccionNs = ahoraNs;
        finSeccionNs = ahoraNs + (long) Azar.entre(SECCIONES[seccionActual].duracionMinMs,
                SECCIONES[seccionActual].duracionMaxMs) * MS_A_NS;
        seccionesTocadas = 0;

        bajoMedioPendiente = false;
        proximoPulsoNs = ahoraNs;
        indiceCorchea = 0;
        pulsoEnAcorde = 0;
        notaBajoActual = 0;
        arpegioNotas = new int[0];
        indiceArpegio = 0;
        suspensionPendiente = false;
        proximaExpresionNs = ahoraNs;

        // El material se conserva entre generos: la memoria de motivos no se borra.
        motivoSeccion = null;
        reexposicion = 0;
        repMotivo.activo = false;
        repMotivo.proximaNs = ahoraNs;
        repMotivo.unidad = 0;
        repContra.activo = false;
        repContra.proximaNs = ahoraNs;
        repContra.unidad = 0;
        prepararMotivoDeSeccion(a);
        enviarControl(CANAL_PAD, CC_EXPRESION, EXPRESION_PLANA);
        enviarControl(CANAL_CONTRA, CC_EXPRESION, EXPRESION_PLANA);
    }

    // ------------------------------------------------------------------
    // Hilo generador
    // ------------------------------------------------------------------

    /**
     * Bucle del generador. Despierta cada PASO_MS para que detener() y los
     * cambios de genero sean inmediatos: nunca duerme una frase entera de una
     * sola vez. Lee los ajustes una vez por vuelta, de modo que dentro de una
     * misma iteracion todo el material sale del mismo genero.
     */
    private final class Generador implements Runnable {

        @Override
        public void run() {
            try {
                long ahora = System.nanoTime();
                reiniciarEstadoMusical(ahora);
                while (enMarcha) {
                    ahora = System.nanoTime();
                    if (cambioPendiente) {
                        cambioPendiente = false;
                        reiniciarEstadoMusical(ahora);
                    }
                    Ajustes a = ajustes;
                    // Decaimiento de la energia visual: sin estallidos vuelve
                    // a cero en unos tres segundos y medio.
                    double ev = energiaVisual;
                    if (ev > 0) {
                        ev -= PASO_MS / 3500.0;
                        energiaVisual = ev < 0 ? 0 : ev;
                    }
                    if (disponible) {
                        // Una cuerda por vuelta: a 25 ms de sondeo sale un
                        // rasgueo de los rapidos, que es lo que se busca.
                        avanzarRasgueo(ahora);
                        apagarVencidas(ahora);
                        cerrarPercusionVencida(ahora);
                        actualizarExpresion(a, ahora);
                        if (ahora - proximoAcordeNs >= 0) {
                            cambiarAcorde(a, ahora);
                        }
                        if (suspensionPendiente && ahora - resolucionNs >= 0) {
                            resolverSuspension(a, ahora);
                        }
                        if (a.estiloBajo != BAJO_CAMINANTE && bajoMedioPendiente
                                && ahora - bajoMedioAcordeNs >= 0) {
                            tocarBajoMedioAcorde(a, ahora);
                        }
                        if (a.usaPulso && ahora - proximoPulsoNs >= 0) {
                            pulso(a, ahora);
                        }
                        motorMotivo(a, ahora);
                        motorContra(a, ahora);
                    }
                    try {
                        Thread.sleep(PASO_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                // El generador se retira en silencio; la aplicacion sigue.
            } finally {
                try {
                    apagarTodo();
                } catch (Exception e) {
                    // Ignorado.
                }
            }
        }
    }
}
