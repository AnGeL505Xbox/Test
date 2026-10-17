# Ultra HDR Wallpaper — prototipo experimental

Aplicación Android 14+ para seleccionar JPEG Ultra HDR y aplicarlos como fondo animado **estático**.

## Compilar
1. Abrir la carpeta `UltraHDRWallpaper` en Android Studio (JDK 17).
2. Sincronizar Gradle (requiere acceso a internet para dependencias).
3. Ejecutar en el teléfono o generar APK mediante **Build > Build APK(s)**.
4. Seleccionar un JPEG, comprobar la detección de gain map y pulsar **Elegir como fondo de inicio**.

## Limitaciones importantes
- Este proyecto **NO demuestra** que el compositor de One UI muestre el fondo en HDR real. Usa `ImageDecoder` y `Canvas` acelerado por hardware; la composición puede reducir el rango dinámico a SDR.
- La selección y detección del gain map sí están implementadas. El renderizado HDR del wallpaper debe comprobarse en hardware real y quizá requiera un pipeline EGL/Vulkan HDR y soporte del compositor.
- El encuadre es centrado y tipo *center crop*. No se implementan encuadres independientes para pantallas interior/exterior.
- No se incluye APK compilado; este entorno no tiene Android SDK/Gradle.

## Obtener el APK sin instalar Android Studio
Este repositorio incluye `.github/workflows/build-apk.yml` para compilar el proyecto en GitHub Actions.

1. Crea un repositorio en GitHub y sube el contenido de **esta carpeta** a la raíz (incluyendo `.github`).
2. En la pestaña **Actions**, selecciona **Compilar APK de prueba** y pulsa **Run workflow**.
3. Cuando finalice, descarga el artefacto `UltraHDRWallpaper-debug-apk`. Dentro está `app-debug.apk`.
4. Pasa el APK al teléfono, acepta la instalación desde esa fuente y abre la aplicación.

**Aviso:** no se ha compilado ni probado en un dispositivo en este entorno. El APK será un prototipo experimental; que detecte Ultra HDR no garantiza que el fondo se muestre en HDR. GitHub Actions deberá poder descargar Gradle y las dependencias Android. El APK debug estará firmado con una clave de prueba generada por el servicio de compilación.
