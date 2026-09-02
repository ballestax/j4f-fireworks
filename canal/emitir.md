# Cómo emitir Sonnus Flare

## 1. Arrancar la app en modo emisión

```powershell
cd c:\DEV\SideFun\j4f-fireworks
javaw -cp build\classes j4f.J4F --emision
```

Arranca en pantalla completa, sin rótulos y con la música sonando. Cambia de
género solo cada 9 minutos. Para salir, **ESC** dos veces (la primera sale de
pantalla completa, la segunda cierra).

Usa `javaw` y no `java` para que no quede una consola por detrás.

### Un solo género o rotando

Por defecto rota, que es lo que conviene a un directo generalista de 24 horas.
Para una emisión temática —"guitarra toda la noche"— se fija el género y el
cambio automático se apaga:

```powershell
javaw -cp build\classes j4f.J4F --emision --genero guitarra
```

Géneros: `chill`, `jazz`, `clasica`, `caribena`, `guitarra`, `violin`. Se
aceptan también `salsa`, `classical`, `guitar` y `cuerda`.

Si el nombre está mal escrito **la aplicación no arranca** y dice cuáles hay.
Es a propósito: un directo lanzado desde un script con una errata emitiría
horas del ambiente equivocado sin que nadie se diera cuenta.

La tecla **G** sigue cambiando de género a mano aunque esté fijado, por si hay
que corregir sin reiniciar el directo. `--rotar` es el comportamiento por
defecto y solo hace falta escribirlo para anular un `--genero` anterior en la
misma línea.

## 2. Instalar OBS

No lo tienes instalado. Descárgalo de `obsproject.com` (gratis, código abierto).

## 3. Configurar la escena

**Fuente de vídeo** — Añadir → *Captura de pantalla* (o *Captura de ventana*
seleccionando la ventana de Fireworks). Captura de pantalla suele dar menos
problemas con las aplicaciones en pantalla completa.

**Fuente de audio** — Aquí está lo delicado. La app suena por los altavoces
del sistema, así que hay que capturar lo que sale:

- Ajustes → Audio → *Audio de escritorio*: elige tus altavoces.
- **Quita el micrófono** (Audio de mic/aux → Deshabilitado) o se colará el
  ruido de tu habitación en el directo.

Comprueba en el mezclador que la barra de *Audio de escritorio* se mueve y la
del micrófono no.

## 4. Ajustes de emisión

Ajustes → Emisión → Servicio **YouTube - RTMPS**, y pega ahí tu clave de
emisión (la encuentras en YouTube Studio → Emitir en directo).

Ajustes → Salida (modo Avanzado):

| Parámetro | Valor | Por qué |
|---|---|---|
| Codificador | NVENC si tienes gráfica NVIDIA; si no, x264 | NVENC descarga la CPU, que aquí importa |
| Control de tasa | CBR | Lo que YouTube espera |
| Tasa de bits | 4500 Kbps para 1080p30 | La escena es oscura y con degradados |
| Intervalo de fotogramas clave | 2 s | Obligatorio en YouTube |
| Preajuste x264 | `veryfast` | Si usas x264, no le robes CPU al audio |

Ajustes → Vídeo: resolución base y de salida **1920x1080**, **30 fps**.

**30 y no 60**: la animación va a 60, pero emitir a 30 baja a la mitad la
carga de codificación y en una escena tan lenta apenas se nota. Si tu máquina
va sobrada, sube a 60.

Ajustes → Audio: frecuencia **48 kHz**, que es exactamente la que produce el
motor. Así se evita un remuestreo.

## 5. Emitir sin OBS (alternativa)

Si prefieres ffmpeg (hay que instalarlo, tampoco lo tienes):

```powershell
ffmpeg -f gdigrab -framerate 30 -i desktop `
       -f dshow -i audio="Mezcla estereo (Realtek)" `
       -c:v libx264 -preset veryfast -b:v 4500k -maxrate 4500k -bufsize 9000k `
       -pix_fmt yuv420p -g 60 `
       -c:a aac -b:a 160k -ar 48000 `
       -f flv rtmp://a.rtmp.youtube.com/live2/TU_CLAVE
```

El nombre del dispositivo de audio cámbialo por el tuyo; lo ves con
`ffmpeg -list_devices true -f dshow -i dummy`. Si no te aparece "Mezcla
estéreo", actívala en Panel de control → Sonido → Grabación → clic derecho →
*Mostrar dispositivos deshabilitados*.

**Tu clave de emisión es una credencial.** No la pegues en un archivo que vaya
a git ni me la mandes por el chat; ponla directamente en OBS.

## 6. Antes de dejarlo solo toda la noche

- [ ] Desactiva la suspensión y el apagado de pantalla de Windows, o el
      directo se corta a las dos horas.
- [ ] Desactiva el salvapantallas.
- [ ] Deja el volumen del sistema fijo y no lo toques: OBS captura lo que
      sale, así que si bajas el volumen, baja para todo el mundo.
- [ ] Haz una emisión de prueba **no listada** de media hora antes de la
      pública.
- [ ] Mira el panel de YouTube Studio en busca de avisos de tasa de bits o
      fotogramas perdidos.
- [ ] Atribución del banco de sonidos en la descripción (licencia MIT).
