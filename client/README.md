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
- Mostrar una pantalla propia de inicio de sesión y registro como primera vista cuando no hay sesión.
- Leer el servidor desde `ZINEMA_SERVER_URL` en el `.env` del proyecto durante la compilación; el usuario no tiene que introducir su dirección ni el nombre del servidor.
- Registrar cuentas que requieren aprobación administrativa antes de acceder a la biblioteca.
- Aprobar o rechazar solicitudes de cuenta desde Administración y cerrar sesión desde el menú de perfil.

El cliente no contiene credenciales de TMDb ni del administrador. El servidor sirve las imágenes y los vídeos.

## Compilar el APK

Necesitas un JDK, Android SDK Platform 35 y Android Build Tools 36.0.0. Ejecuta:

```sh
./build-apk.sh
```

Define `ZINEMA_SERVER_URL` en el `.env` antes de compilar. El valor queda integrado en el APK; después de cambiarlo, vuelve a compilar.

El APK firmado para depuración queda en `build/outputs/apk/debug/zinema-client-debug.apk`. El script compila y firma el paquete, pero no instala nada. La clave local de depuración se conserva en `.debug.keystore` (ignorada por Git) para que las compilaciones siguientes puedan instalarse como actualización. Puedes indicar otra ubicación del SDK con `ANDROID_SDK_ROOT`.

## Publicar una actualización de producción

Cada compilación de producción aumenta automáticamente el `versionCode` y genera `zinema-update.json` junto al APK. Incluye ambos artefactos en el commit que subas a GitHub (el APK está ignorado por Git, por eso hay que forzar su inclusión):

```sh
git add client app publish-client-update.sh
git add -f zinema-prod.apk zinema-update.json
git commit -m "Publish Zinema client update"
git push
```

Después, desde la carpeta del proyecto en el servidor, ejecuta:

```sh
git pull
./publish-client-update.sh
```

El script reconstruye y reinicia `zinema`, publica los dos artefactos en `data/client-update/` y verifica la versión que anuncia la API. Por defecto consulta `http://127.0.0.1:8180`; si el puerto local es distinto, define `API_BASE_URL` al ejecutarlo. Los clientes comparan la versión al abrir o volver a la app, descargan el APK al pulsar «Actualizar» y muestran el instalador de Android. Android pide confirmación para instalar y puede pedir permiso a Zinema para instalar paquetes; la app no puede instalarse silenciosamente. La actualización conserva datos si el APK usa la misma clave de firma que la instalación existente.

La primera apertura muestra el inicio de sesión; después de autenticar una cuenta aprobada, el cliente recupera sus perfiles y el catálogo. La compatibilidad de reproducción depende de los formatos y códecs que soporte el dispositivo Android.
