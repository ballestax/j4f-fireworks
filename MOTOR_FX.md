# Motor hibrido (JavaFX)

El programa tiene dos motores de dibujo. El de siempre, Java2D, sigue siendo
el motor por defecto: Java 8, cero dependencias, tal y como funcionaba antes
de este documento. El nuevo, hibrido, anade **edificios en 3D de verdad** con
**luz real de las explosiones** sobre sus caras, y necesita JavaFX.

## Como elegirlo

Al arrancar sin `--motor`, la aplicacion pregunta por pantalla que motor usar
(salvo en `--emision`, que siempre usa el clasico: un directo de 24 horas no
puede quedarse esperando un clic).

Para elegirlo desde la linea de ordenes, sin pasar por el dialogo:

```powershell
java -cp build\classes j4f.J4F --motor javafx
java -cp build\classes j4f.J4F --motor java2d
```

`--motor` acepta tambien `fx`/`hibrido` y `clasico`/`classic` como alias.

## Preparacion, una sola vez

El motor hibrido necesita dos cosas que el proyecto no trae por defecto:

1. Los jars de OpenJFX (unos 6 MB), porque ningun JDK los trae ya desde hace
   varias versiones.
2. Un **Java 11 o mas moderno** instalado en la maquina. JavaFX moderno no
   arranca en el Java 8 que usa el resto del proyecto: revienta con
   `UnsupportedClassVersionError` en cuanto toca la primera clase de FX. Si no
   tienes uno, [Eclipse Temurin](https://adoptium.net) es gratuito y sirve.

Con ese Java 11+ instalado, desde la raiz del proyecto:

```powershell
"<esa carpeta>\bin\java" herramientas\PrepararMotorFx.java
```

Java 11 en adelante puede ejecutar un `.java` suelto sin compilarlo aparte,
asi que esto compila y corre el propio `PrepararMotorFx.java` en un paso: baja
los jars a `lib-fx/` y compila `src-fx/` en `build-fx/`, usando el mismo
compilador con el que se ejecuta (por eso hay que darselo al java correcto:
compila con el mismo que va a correr el motor luego).

Ninguno de los dos, `lib-fx/` ni `build-fx/`, se comitea al repositorio;
estan en `.gitignore` igual que el banco de sonido (`sonido/`) y por el mismo
motivo: son un asset que se genera aparte, no codigo fuente.

## Como se arma por dentro

**Un proceso aparte.** `j4f.J4F` (el arranque de siempre, Java 8, cero
dependencias) no importa nada de `javafx.*`. Cuando se pide el motor hibrido,
busca un Java 11+ instalado en la maquina (variable `JAVAFX_JAVA_HOME`,
`JAVA_HOME`, o las carpetas donde los principales distribuidores instalan un
JDK en Windows) y lanza `j4f.fx.Lanzador` como proceso hijo con ese Java,
esperando a que termine. Es la unica forma limpia de mezclar dos maquinas
virtuales de Java distintas en un mismo lanzador.

**Todo el resto de la escena se reutiliza tal cual.** `src-fx/j4f/fx/MotorFx`
construye un `Fireworks` exactamente como los bancos de pruebas del proyecto
(`Ritmo`, `Foto`, `Perfil`): sin anadirlo a ninguna ventana de Swing, solo
como modelo y compositor. Cada fotograma llama a
`Fireworks.avanzarYComponer(dt, destino, w, h)` (la misma simulacion, el mismo
cielo, la misma luna, el mismo agua con su reflejo, los mismos fuegos) y
vuelca el resultado como textura de fondo en un `ImageView`. La musica sale
del mismo `Musica`/`MotorAudio` de siempre, sin tocar nada.

**Lo nuevo es una capa 3D encima.** Una `SubScene` con una `Box` por edificio,
usando la geometria que `Escenario` guarda desde la fase 4 del rediseno
(`getGeometriaEdificios()`), iluminada con una `PointLight` que seigue al
fogonazo de cada estallido (mismo color, misma posicion, misma caida con la
distancia que usa el motor clasico para su lavado de luz). La camara es
`ParallelCamera` (ortografica): sin ella los edificios 3D no coincidirian
pixel a pixel con la silueta ya pintada en el fondo 2D, y se veria un fantasma
doble en cada borde.

## Que no trae esta primera version

- Sin sombras proyectadas: JavaFX no las da sin shaders propios.
- Sin camara movil: es un escaparate frontal fijo, como el motor clasico.
- `--emision` (streaming a OBS) no se ha probado con este motor. Para
  directos, usa el motor clasico, que es el medido y documentado en
  `canal/emitir.md`.
- Las chispas y estelas siguen siendo las del motor clasico (Java2D,
  bajo el 3D): una mezcla aditiva real de las propias chispas en JavaFX
  queda para una siguiente fase, si hace falta.
