# abismoCam · MVP Android

Cámara para **El abismo de la visibilidad**, Lisandro Peralta. Versión 0.5.0. Samsung A51 como dispositivo objetivo; Android 8 o posterior, ARM64. Interfaz en español, orientación vertical, cámara frontal por defecto, sin micrófono. Guarda fotografías, no graba video.

## Estado

El APK de `entregables` incorpora las bibliotecas oficiales `libndi.so` del SDK NDI para Android, en ARM64 para el teléfono y x86_64 para el emulador. La cámara, NDI High Bandwidth y OSC están implementados. Falta validar la recepción y el rendimiento en el Samsung A51 y la red de la instalación. Las licencias del SDK se conservan en `licenses/ndi/`.

El SDK utilizado se instaló en `C:/Users/lisan/AppData/Local/NDI-SDK-Android`. Las bibliotecas `.so` están excluidas de Git; si se copia el proyecto sin ellas, hay que incorporarlas nuevamente con las instrucciones de abajo.

## Uso

1. Instalar `entregables/AbismoCam-debug.apk` en el teléfono y permitir el acceso a la cámara.
2. Conectar teléfono y computadoras a la misma red local, preferentemente Wi-Fi de 5 GHz y computadoras por Ethernet.
3. Mantener el engranaje presionado **3 segundos** para abrir ajustes, elegir un modo OSC y guardar:
   - **Computadoras individuales**: nombre, IPv4 y puerto por receptor; se puede habilitar, editar o eliminar cada destino.
   - **Broadcast**: dirección de broadcast y puerto común. Detectar broadcast calcula la dirección de una interfaz local Wi-Fi/Ethernet. Revisarla si hay varias interfaces. No funciona a través de subredes o redes que lo bloqueen.
4. Configurar los receptores para escuchar OSC UDP en el puerto elegido, en su interfaz de red o `0.0.0.0`, no solamente en `127.0.0.1`.
5. Soltar el disparador circular inicia la cuenta **5, 4, 3, 2, 1**. Envía `/camara/disparo` con tipo OSC `,i` y entero `1` al comenzar y `0` al terminar. La ruta es configurable. Mantener el dedo apoyado no inicia nada; los toques adicionales se bloquean hasta volver a la cámara en vivo.
6. Al terminar: flash blanco breve, foto congelada durante un segundo y regreso automático al video. La foto se guarda como JPEG en **Pictures/AbismoCam** y aparece una miniatura para abrirla. La cámara frontal y su foto se muestran espejadas.
7. NDI comienza automáticamente al abrir la app con permiso de cámara. Seleccionar la fuente **abismoCam** en los receptores. El indicador de la pantalla principal no responde a toques; activar o detener NDI solo está disponible dentro de ajustes. Cada receptor necesita soporte NDI High Bandwidth.

**NDI continúa enviando la cámara original en vivo durante el conteo, flash, congelado y guardado.** Esos efectos solo aparecen en el teléfono; NDI no lleva espejo ni superposiciones. La foto usa el siguiente cuadro disponible al terminar el conteo, a la resolución del video configurado. La pantalla recorta para llenarse; el archivo conserva el cuadro completo.

El botón y la interfaz no se incluyen en el video. La vista frontal puede mostrarse espejada en la previsualización; el video enviado conserva la imagen de cámara sin espejo. Los cuadros se rotan para su orientación vertical. Resoluciones seleccionables: nHD 640×360, qHD 960×540 y HD 1280×720; en vertical, 360×640, 540×960 y 720×1280. Los FPS se eligen por separado: 15 o 30. La primera actualización a esta versión usa qHD a 30 fps y conserva los destinos OSC. Luego se recuerda la selección. Si la cámara entrega otro tamaño, el cuadro se recorta al centro y escala a la resolución elegida, sin espejo para NDI. Los FPS efectivos dependen del teléfono y la red.

## Transporte NDI

En ajustes (engranaje durante tres segundos), elegir **Unicast** o **Multicast** y tocar **Guardar y volver a cámara**. Unicast es el valor inicial; la selección queda guardada. Cambiar el selector sin guardar no modifica la transmisión. Al guardar un cambio se reinicia el emisor NDI; los receptores pueden mostrar negro o el último cuadro durante la reconexión. El nombre sigue siendo `abismoCam`. Si NDI estaba detenido manualmente, permanece detenido hasta iniciarlo desde ajustes.

Multicast habilita la negociación con cada receptor: no obliga a todos a usarlo. La red debe permitir multicast y administrar correctamente IGMP; un receptor con multicast deshabilitado puede usar unicast. Probar con el teléfono por Wi-Fi de 5 GHz y las computadoras por cable antes de la instalación. OSC es independiente y conserva sus modos individual/broadcast; ambos utilizan las repeticiones configuradas.

El SDK estándar recibe la configuración oficial mediante `NDI_CONFIG_DIR` y el archivo privado `files/ndi/ndi-config.v1.json`. Se escribe de forma atómica, con `ndi.multicast.send.enable`, TTL 1 y rango 239.255.0.0/16. El cambio cierra el emisor, termina la instancia del SDK y vuelve a inicializarlo antes de crear la fuente. No se modifican ajustes del router ni de las computadoras receptoras.

Referencias: [configuración SDK](https://docs.ndi.video/all/developing-with-ndi/sdk/configuration-files), [NDI_CONFIG_DIR y Android](https://docs.ndi.video/all/developing-with-ndi/sdk/platform-considerations).

## Verificar OSC desde Windows

Desde 0.5.0, cada valor (`1` y `0`) se envía **cinco veces**, con el primero inmediato y **50 ms** entre mensajes por defecto: instantes 0, 50, 100, 150 y 200 ms. En ajustes se elige **10, 25, 50 o 100 ms**, que abarcan 40, 100, 200 o 400 ms respectivamente. La selección se aplica al guardar y persiste sin reiniciar NDI; instalaciones anteriores adoptan 50 ms manteniendo sus otros ajustes.

Los receptores deben actuar solo cuando cambia el valor para evitar cinco acciones por transición. UDP sigue sin confirmar entrega ni garantizar orden. Los intervalos son nominales, sujetos a la planificación de Android y la red. Al cancelar la cuenta se cancelan los `1` pendientes y se programan cinco `0`; un cierre normal de la actividad deja completar esa última ráfaga mientras el proceso siga vivo. Un cierre forzado o una pérdida de red puede impedirlo.

En cada computadora, abrir PowerShell en esta carpeta:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\osc-monitor.ps1 -Port 9000
```

Usar `ipconfig` para encontrar la IPv4 de la computadora y agregarla a la app. Permitir recepción UDP para el puerto elegido en el firewall de la red privada si fuera necesario. El monitor imprime la hora, origen, dirección OSC y valor recibido; no cambia el firewall. Cerrar el monitor antes de probar un software que necesite el mismo puerto.

## Incorporar NDI

1. Obtener el **SDK oficial para Android**, con biblioteca **ARM64 / arm64-v8a**, desde https://ndi.video/for-developers/ndi-sdk/download/ y revisar sus términos. Una DLL de Windows o un `.so` de Linux no sirven.
2. Copiar `libndi.so` a `app/src/main/jniLibs/arm64-v8a/libndi.so`. Alternativamente:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\integrate-ndi.ps1 -LibraryPath 'C:\ruta\SDK Android\arm64-v8a\libndi.so'
```

3. Incorporar las dependencias nativas y avisos de licencia que indique el SDK. El script comprueba ELF ARM64, pero no reemplaza la comprobación de que el archivo sea para Android.
4. Recompilar e instalar el APK actualizado. La carga nativa ocurre al iniciar la transmisión. Revisar con un receptor que la imagen tenga orientación y colores correctos, y medir rendimiento con varios receptores.

Se usa `NsdManager` antes de inicializar NDI y un bloqueo multicast durante la transmisión. El adaptador usa las estructuras C `NDIlib_send_create_t` y `NDIlib_video_frame_v2_t`, cuadros RGBA y envío síncrono: el buffer de CameraX se libera después de terminar el envío. Las llamadas NDI están serializadas en el executor de cámara; OSC usa otro executor para no esperar a la compresión de video. Android ARM64 y x86_64 son las arquitecturas configuradas; el emulador requiere su propia biblioteca x86_64 si se desea probar NDI.

## Compilar

Abrir la carpeta en Android Studio o ejecutar:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1 -Test
```

Dependencias fijadas: AGP 8.13.2, Gradle 8.13 (con checksum), Java 17 o posterior, Android SDK 36.1 / Build Tools 36.1.0, CameraX 1.4.2, JNA 5.17.0. La primera compilación requiere acceso a Google Maven, Maven Central y Gradle. El script detecta el Java de Android Studio y el SDK estándar del usuario; también respeta `JAVA_HOME` y `ANDROID_HOME` existentes. No hace falta NDK para este adaptador JNA.

Resultado: `entregables/AbismoCam-debug.apk`. Es un APK de desarrollo para instalar manualmente; no es una publicación en Google Play. Los reportes de pruebas y análisis estático se exportan a `entregables/validacion/`.

Las pruebas unitarias cubren OSC, sus repeticiones y el reloj de captura (13 casos). Con un dispositivo o emulador conectado, `gradlew.bat connectedDebugAndroidTest` ejecuta además las pruebas Android: toques, OSC UDP real, captura JPEG, cancelación, selección de intervalos, transporte NDI y avance de cuadros enviados al SDK durante el congelado. Requieren la biblioteca NDI de la arquitectura correspondiente; no prueban un receptor NDI externo.

Los archivos temporales de compilación se guardan en `%USERPROFILE%/.abismocam/build/`, en una subcarpeta por proyecto, para evitar bloqueos de OneDrive. El código y los entregables permanecen en esta carpeta. En Android Studio puede ejecutarse la tarea raíz `exportDebugApk` para copiar el APK a `entregables`.

## Comportamiento y límites

- OSC individual y broadcast son modos alternativos; la lista de computadoras se conserva al cambiar de modo.
- Se guardan configuración y fotos localmente. No hay audio, servicios de nube ni analítica. Android 8 y 9 requieren permiso de almacenamiento para las fotos; Android 10 o posterior usa MediaStore sin ese permiso.
- Si se abandona la app durante el conteo, se cancela la captura y se intenta enviar `0`. Cancelar el toque antes de soltar no inicia un conteo. Los ajustes se bloquean durante la secuencia. No puede garantizarse el envío si Android mata el proceso, se corta la red o se pierde el paquete UDP.
- UDP no confirma entrega, orden ni sincronía entre computadoras. El estado “enviado” cuenta entregas al sistema operativo, no confirmaciones remotas.
- Video y OSC viajan por separado. Un mensaje OSC no identifica un cuadro NDI ni garantiza sincronización con él.
- La transmisión NDI continúa al entrar y salir de ajustes. Guardar cambios de cámara, resolución o FPS la reinicia brevemente. Al salir de la app se detiene; al volver se reanuda si no se había detenido manualmente en ajustes. Cada apertura nueva inicia NDI automáticamente. La pantalla permanece encendida mientras la app está visible.
- Sin pruebas físicas todavía no se garantiza 720p/30 fps ni una cantidad concreta de receptores para el A51.

## Referencias y licencias

- SDK y condiciones: https://docs.ndi.video/all/developing-with-ndi/sdk/licensing
- Android / descubrimiento: https://docs.ndi.video/all/developing-with-ndi/sdk/platform-considerations
- OSC 1.0: https://opensoundcontrol.stanford.edu/spec-1_0.html
- CameraX: https://developer.android.com/media/camera/camerax/analyze
- JNA: https://github.com/java-native-access/jna (Apache-2.0 / LGPL-2.1-or-later, según sus términos).
- AndroidX y Gradle: Apache-2.0 y avisos de sus respectivas distribuciones.

NDI® is a registered trademark of Vizrt NDI AB. Este proyecto personal no está afiliado a Vizrt. Los componentes externos mantienen sus propias licencias.


