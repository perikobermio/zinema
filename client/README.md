# Zinema Android client

Cliente Android nativo conectado a la API pública de Zinema. La interfaz usa un tema oscuro con portada destacada y filas horizontales de carátulas.

## Funciones

- Explorar películas, series, colecciones, temporadas y episodios.
- Buscar en el catálogo del servidor.
- Ver fichas con sinopsis, año, géneros e imágenes.
- Guardar títulos en «Mi lista» y conservar el progreso local para continuar viendo.
- Reproducir el contenido del NAS con los controles nativos de Android.
- Configurar el dominio público o una dirección local del servidor desde el icono de ajustes.

El cliente no contiene credenciales de TMDb ni del administrador. El servidor sirve las imágenes y los vídeos.

## Compilar el APK

Necesitas un JDK, Android SDK Platform 35 y Android Build Tools 36.0.0. Ejecuta:

```sh
./build-apk.sh
```

El APK firmado para depuración queda en `build/outputs/apk/debug/zinema-client-debug.apk`. El script compila y firma el paquete, pero no instala nada. Puedes indicar otra ubicación del SDK con `ANDROID_SDK_ROOT`.

La primera apertura conecta al dominio configurado en `MainActivity`; puedes cambiarlo desde «Servidor». La compatibilidad de reproducción depende de los formatos y códecs que soporte el dispositivo Android.
