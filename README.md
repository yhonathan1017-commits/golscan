# GolScan — diagnóstico KWP1281 para Android

App Android para diagnosticar vía **línea K (KWP1281)** autos VAG anteriores al
OBD-II real, como el **VW Gol 2002 con inyección Magneti Marelli**, usando el
mismo cable **KKL 409.1** que ya funciona en PC, conectado al celular por **USB-OTG**.

Está construida desde cero sobre la documentación pública del protocolo KWP1281.
No contiene ni deriva de ningún software comercial.

## Qué hace (versión 0.1)

- Conecta con la ECU de motor (dirección `0x01`) con init lento a 5 baudios.
- Lee la identificación que envía la ECU.
- Lee y borra códigos de falla (DTC).
- Lee grupos de medición (RPM, temperaturas, etc.).
- Muestra un log en pantalla de todo el intercambio.

## Hardware necesario

- El cable **KKL 409.1** que ya te funcionó en el PC (chip CH340, FTDI, PL2303 o CP210x).
- Un adaptador/cable **USB-OTG** para tu celular Android.
- Android 5.0 (API 21) o superior con soporte USB Host (casi todos).

## Cómo obtener el APK sin instalar nada pesado

1. Crea un repositorio en GitHub y sube el contenido de esta carpeta.
2. GitHub Actions compila el APK automáticamente (ver pestaña **Actions**).
   También puedes lanzarlo a mano con **Run workflow**.
3. Descarga el artefacto **GolScan-debug-apk**, pásalo al celular e instálalo
   (hay que permitir "instalar apps de orígenes desconocidos").

> Alternativa: abrir la carpeta en **Android Studio** y pulsar Run; él genera
> su propio Gradle wrapper y el `local.properties` con la ruta del SDK.

## Cómo se usa

1. Motor en contacto (o encendido), cable KKL en el conector de diagnóstico.
2. Conecta el KKL al celular por OTG. Android pedirá permiso para el dispositivo USB.
3. Abre GolScan y pulsa **Conectar**.
4. Usa **Leer fallas**, **Borrar fallas** o escribe un número de grupo y **Leer grupo**.

## Ajuste fino (importante)

El protocolo está implementado según los valores típicos, pero el **init lento**
y los **tiempos** (W1–W4) a veces hay que afinarlos contra el auto real. Si al
conectar no llega el byte `0x55`:

- Prueba cambiar `COMM_BAUD` a `9600` en `Kwp1281.kt`.
- Ajusta `W4_MS` y `W_INTERBYTE_MS`.
- Revisa el método `slowInit()` (formato de bits del init a 5 baudios).

El log en pantalla muestra exactamente en qué paso falla, lo que facilita el ajuste.

Las fórmulas de los grupos de medición (`Formulas.kt`) y la tabla de códigos
(`FaultText.kt`) son parciales: se amplían con lo que vaya apareciendo en tu Gol.

## Estructura

```
app/src/main/java/com/galzza/golscan/
├─ MainActivity.kt          UI y manejo del permiso USB
├─ usb/UsbKLine.kt          capa física: cable KKL por USB-serial
└─ kwp/
   ├─ Kwp1281.kt            el protocolo (init, handshake, bloques)
   ├─ Models.kt             tipos de datos
   ├─ Formulas.kt           conversión de grupos de medición
   └─ FaultText.kt          descripciones de códigos de falla
```

## Legalidad

Pensada para tu propio vehículo. KWP1281 es un protocolo documentado
públicamente; esta app no usa propiedad intelectual de terceros.
