# Zinema Android client

Cliente Android nativo conectado a la API pública de Zinema. La interfaz usa un tema oscuro con portada destacada y filas horizontales de carátulas.

## Funciones

- Explorar películas, series, colecciones, temporadas y episodios.
- Mostrar el número de títulos en cada colección y un mosaico con sus pósteres si no tiene uno propio.
- Navegar cinco sugerencias desde la portada y retomar vídeos empezados desde una fila prioritaria con sus pósteres.
- Ver géneros de películas y colecciones, y el número de temporadas de cada serie.
- Navegar atrás con el botón visible en pantalla o con la tecla atrás del dispositivo.
- Buscar en el catálogo del servidor.
- Ver fichas con sinopsis, año, géneros e imágenes.
- Editar metadata manualmente y escoger una miniatura entre hasta diez imágenes encontradas en TMDb.
- Validar las coincidencias encontradas por TMDb antes de guardarlas.
- Mostrar el título de cada episodio cuando TMDb lo proporciona; si no, mostrar el nombre del archivo.
- Guardar títulos en «Mi lista» y conservar el progreso local para continuar viendo.
- Reintentar la metadata desde el menú de tres puntos de cada tarjeta o ficha.
- Reproducir el contenido del NAS con los controles nativos de Android.
- Configurar el dominio público o una dirección local del servidor desde el icono de ajustes.

El cliente no contiene credenciales de TMDb ni del administrador. El servidor sirve las imágenes y los vídeos.

## Compilar el APK

Necesitas un JDK, Android SDK Platform 35 y Android Build Tools 36.0.0. Ejecuta:

```sh
./build-apk.sh
```

El APK firmado para depuración queda en `build/outputs/apk/debug/zinema-client-debug.apk`. El script compila y firma el paquete, pero no instala nada. La clave local de depuración se conserva en `.debug.keystore` (ignorada por Git) para que las compilaciones siguientes puedan instalarse como actualización. Puedes indicar otra ubicación del SDK con `ANDROID_SDK_ROOT`.

La primera apertura conecta al dominio configurado en `MainActivity`; puedes cambiarlo desde «Servidor». La compatibilidad de reproducción depende de los formatos y códecs que soporte el dispositivo Android.
