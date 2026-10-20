# Zinema Server

Servidor para catalogar y reproducir vídeo del NAS. Escanea las carpetas de películas y series, organiza el catálogo según su estructura y guarda metadata y carátulas en SQLite y `./data/artwork`. El cliente consulta Zinema; la clave de TMDb solo vive en el servidor.

## Estructura de carpetas

La exploración admite carpetas de colección a cualquier profundidad:

```text
Peliculas/
  Coleccion/
    Titulo de pelicula (2024)/
      pelicula.mkv
Series/
  Coleccion/
    Nombre de serie/
      Season 00/
        episodio S00E01.mkv
```

Los directorios anteriores a la carpeta de película o serie aparecen como colecciones. La API devuelve el número de títulos descendientes en `item_count` y hasta cuatro carátulas de películas o series en `mosaic_images` para que el cliente dibuje el mosaico cuando la colección no tenga póster propio. Las temporadas tienen una imagen de póster generada con su número. Los episodios se enlazan a la serie y temporada cuando están en una carpeta `Season NN`; los patrones `SxxEyy` y `xxXyy` se usan para identificar el número del episodio.

## Configurar y arrancar

Requisitos: Docker Engine con Docker Compose v2, `curl` y acceso de lectura y escritura del host a las bibliotecas y a Incoming.

1. En el NAS, clona el proyecto y crea su configuración:

   ```sh
   git clone https://github.com/perikobermio/zinema.git ~/apps/zinema
   cd ~/apps/zinema
   cp .env.example .env
   nano .env
   ```

2. Configura `MOVIES_PATH`, `SERIES_PATH`, `INCOMING_PATH` (por defecto `/d/NAS/Incoming`) y `NAS_SCRIPTS_PATH` (por defecto `/d/NAS/scripts`). Incoming es la carpeta donde llegan las descargas. El script `telegram_download.py` debe existir dentro de `NAS_SCRIPTS_PATH`; el contenedor monta esa carpeta y Incoming en sus rutas del NAS para lanzar la descarga con la sesión existente. Para TMDb puedes guardar la **API Key v3** en `TMDB_KEY` o el **API Read Access Token** en `TMDB_ACCESS_TOKEN`; basta con configurar uno. No subas estas credenciales a Git. `TMDB_LANGUAGE` controla el idioma preferido de los resultados; por defecto es `es-ES`.

3. Valida las rutas y arranca el servidor:

   ```sh
   ./check-nas.sh
   docker compose up --build -d
   docker compose ps
   docker compose logs -f zinema
   ```

El NAS y SQLite se montan de forma persistente en `./data`. Las bibliotecas, Incoming y la carpeta de sesión/script de Telegram necesitan permisos de escritura. En Administración, la tarjeta de descarga inicia `python /d/NAS/scripts/telegram_download.py --force`, muestra su salida y el servidor vigila Incoming para detectar ficheros cuyo tamaño o fecha de modificación cambian, incluso si la descarga se inició fuera de Zinema. Mientras detecta actividad bloquea otro inicio y oculta ese fichero de la lista para evitar moverlo a medio descargar; cuando se estabiliza, actualiza la lista. La tarjeta muestra el nombre y tamaño actual si puede identificar el fichero. «Gestión de Incoming» permite revisar la ruta propuesta y mover cada fichero. La API está en el puerto `8180` del host.

### Crear la cuenta administradora inicial

Tras reconstruir y arrancar el servidor en el NAS, ejecuta:

```sh
./create-admin.sh
```

El script solicita email y contraseña sin mostrar esta última. Crea o promueve la cuenta administradora y vincula a ella los perfiles existentes que aún no tenían propietario; conserva sus IDs y el estado sincronizado de cada perfil. No guarda la contraseña en `.env` ni en el repositorio. Ejecuta este paso una vez después de actualizar el servidor para migrar la base de datos persistente.

## Lanzar la sincronización inicial

La sincronización vuelve a escanear las carpetas y busca metadata de películas y series en TMDb. Se ejecuta como trabajo en segundo plano:

```sh
./start-import.sh
```

El script usa `DEVICE_TOKEN` del entorno (debe pertenecer a una sesión administradora), inicia el trabajo y muestra su ID. Para usar una URL distinta, ejecuta `API_BASE_URL=https://zinema.tudominio.com ./start-import.sh`. Consulta el progreso con la URL y el comando que imprime el script, o con:

```sh
curl http://localhost:8180/api/v1/admin/sync/ID_DEL_TRABAJO \
  -H "Authorization: Bearer $DEVICE_TOKEN"
unset DEVICE_TOKEN
```

También puedes consultar el último trabajo o uno concreto con el script de estado:

```sh
./sync-status.sh
./sync-status.sh ID_DEL_TRABAJO
```

Acepta la misma variable `API_BASE_URL` para consultar el servidor mediante el dominio del reverse proxy.

El estado pasa por `scan` y `metadata:N`; al terminar devuelve cuántos archivos se escanearon y cuántos títulos se asociaron, quedaron para revisar o no tuvieron coincidencia. La importación automática solo acepta coincidencias claras; los casos ambiguos quedan en la cola de revisión:

```sh
curl http://localhost:8180/api/v1/admin/metadata/review \
  -H "Authorization: Bearer $DEVICE_TOKEN"
```

Para aceptar una coincidencia, envía el `tmdb_id` elegido:

```sh
curl -X POST 'http://localhost:8180/api/v1/admin/catalog/ID_DEL_NODO/match?tmdb_id=ID_TMDB' \
  -H "Authorization: Bearer $DEVICE_TOKEN"
```

Tras una sincronización inicial completada, el servidor vuelve a escanear diariamente. Solo consulta TMDb para entradas nuevas o cuya metadata lleve 30 días sin actualizar. Puedes cambiar el intervalo y el tamaño de lote con `TMDB_SYNC_INTERVAL_HOURS` y `TMDB_SYNC_BATCH_SIZE`.

## API para el cliente

Las cuentas cliente se crean con usuario y contraseña. Tras el registro quedan pendientes y no pueden iniciar sesión ni consultar la biblioteca hasta que un administrador las apruebe. Las cuentas administradoras no requieren aprobación. Al desplegar esta protección, las cuentas estándar ya existentes también quedan pendientes de una revisión inicial; las cuentas administradoras conservan el acceso. Desde **Gestión de cuentas**, accesible en el menú del avatar del cliente, el administrador puede permitir el acceso, denegarlo temporalmente o borrar la cuenta y sus perfiles. `create-admin.sh` crea o promueve una cuenta administradora a partir de un email.

El inicio de sesión emite un token aleatorio revocable para esa instalación. El registro responde con estado pendiente y no emite token. Android cifra el token con Android Keystore; el servidor guarda solamente su hash. Las contraseñas se guardan con Argon2id. Las rutas de datos requieren `Authorization: Bearer <token>`; `/health` queda pública para comprobar conectividad, y registro/login son los únicos endpoints de cuenta sin sesión. VLC recibe un ticket de reproducción temporal después de que la app se autentique.

Ejemplo de login desde terminal (no guardes la contraseña en el historial del shell):

```sh
curl -X POST 'http://localhost:8180/api/v1/auth/login' \
  -H 'Content-Type: application/json' \
  -d '{"email":"tu@email","password":"...","device_name":"Terminal"}'
```

`POST /api/v1/auth/register` acepta `username` y `password` (también admite `email` por compatibilidad) y responde `202` con `status: pending_approval`, sin emitir token. `GET /api/v1/admin/accounts` lista las cuentas; `POST /api/v1/admin/accounts/{id}/approve` permite el acceso, `/disable` lo deniega y `DELETE /api/v1/admin/accounts/{id}` borra la cuenta y sus perfiles. `POST /api/v1/admin/accounts/{id}/reject` también elimina una solicitud pendiente. `POST /api/v1/auth/logout` revoca el dispositivo actual. `GET /api/v1/auth/me` devuelve la cuenta y su número de perfiles. Los perfiles y su estado de reproducción pertenecen a la cuenta, de modo que cualquier instalación que inicie sesión con ella recibe los mismos perfiles.

El catálogo se navega por nivel, usando `parent_id` para abrir una colección, serie o temporada:

```sh
set -a; . ./.env; set +a
curl 'http://localhost:8180/api/v1/catalog?library=movies' -H "Authorization: Bearer $DEVICE_TOKEN"
curl 'http://localhost:8180/api/v1/catalog?library=series' -H "Authorization: Bearer $DEVICE_TOKEN"
curl 'http://localhost:8180/api/v1/catalog?library=series&parent_id=ID_DEL_NODO' -H "Authorization: Bearer $DEVICE_TOKEN"
curl 'http://localhost:8180/api/v1/catalog/ID_DEL_NODO' -H "Authorization: Bearer $DEVICE_TOKEN"
```

`DEVICE_TOKEN` es el token emitido al iniciar sesión en una instalación.

Al reintentar metadata desde el cliente, `POST /api/v1/catalog/ID_DEL_NODO/metadata/candidates` busca coincidencias TMDb para validarlas antes de guardarlas. `GET /api/v1/catalog/ID_DEL_NODO/metadata/candidate/ID_TMDB` carga sus datos completos. La edición manual se guarda con `POST /api/v1/catalog/ID_DEL_NODO/metadata/manual`. Para buscar imágenes se usa `GET /api/v1/catalog/ID_DEL_NODO/metadata/images?q=TITULO`; devuelve hasta diez imágenes TMDb.

Las rutas de reintento, búsqueda y edición de metadata también requieren `Authorization: Bearer $DEVICE_TOKEN`. Las ediciones manuales permanecen guardadas y no las reemplaza la sincronización diaria. Se pueden volver a solicitar coincidencias después de un minuto por elemento.

Las solicitudes de contenido usan `GET /api/v1/content-requests/search?q=TITULO` para buscar películas y series en TMDb, `GET /api/v1/content-requests/tmdb/{tipo}/{tmdb_id}` para cargar sus temporadas (`tipo` es `movie` o `tv`) y `POST /api/v1/content-requests` para enviarlas. Se pueden enviar varios títulos en una sola petición con `items`, cada uno con `content_type`, `tmdb_id` y `season_number` opcional. Cualquier cuenta autenticada puede pedir contenido nuevo o solicitar las temporadas y capítulos que faltan de una serie incompleta. `GET /api/v1/admin/content-requests`, `POST /api/v1/admin/content-requests/{id}/approve`, `POST /api/v1/admin/content-requests/{id}/resend` y `DELETE /api/v1/admin/content-requests/{id}` requieren una cuenta administradora. La API incluye los datos de póster para mostrar miniaturas en los resultados y en Administración. Al validar una solicitud, el servidor envía un mensaje con miniatura y datos del título a Telegram; solo la marca como validada si el envío se completa. Una solicitud validada se puede reenviar desde Administración. Los títulos validados aparecen como tarjetas provisionales en el catálogo, con la indicación de que todavía no se han añadido y sin acceso a detalle. Tras la sincronización, cuando el catálogo encuentra la película, serie o temporada correspondiente, muestra el elemento real en su lugar.

Telegram usa una sesión de usuario aislada, guardada en `./data/telegram/zinema.session` en el host mediante el volumen persistente de Docker. Define `TELEGRAM_API_ID` y `TELEGRAM_API_HASH` en el `.env` del servidor (se obtienen en `my.telegram.org`), despliega el contenedor y crea la sesión interactiva una sola vez:

```sh
docker compose exec -it zinema python -m app.telegram
```

Telegram enviará un código a la cuenta para autorizar la sesión; se solicitará también la contraseña de verificación en dos pasos si está activada. Por defecto los mensajes van al chat de Telegram `-1001764210415` (`TELEGRAM_TARGET=-1001764210415`). La cuenta del servidor debe haber abierto ese chat para que Telethon pueda resolver su ID. Se puede elegir otro destino con `TELEGRAM_TARGET`; usa `me` para **Mensajes guardados**. El archivo de sesión contiene acceso a la cuenta, se mantiene fuera de Git bajo `data/` y no debe compartirse.

Al asociar una serie con TMDb, el servidor consulta también los episodios para guardar sus títulos. Si TMDb no tiene un título para el episodio, el cliente muestra el nombre del archivo. En temporadas intenta usar el póster de temporada, una imagen del primer episodio y, como último recurso, el póster de la serie.

Los nodos y archivos heredan los géneros y el póster de la colección ancestro más cercana cuando no tienen esos datos propios. Un póster o género asignado al nodo prevalece sobre el heredado. `GET /api/v1/genres` devuelve la lista sin duplicados de los géneros que ya aparecen en el catálogo; al importar metadata nueva, sus géneros pasan a estar disponibles automáticamente.

Cada nodo incluye `kind`, `display_title`, `overview`, `year`, `genres`, `poster_url`, `backdrop_url`, `children`, `files` y `mosaic_images`. Para reproducir, el cliente solicita un permiso temporal por vídeo; VLC usa la URL resultante durante ocho horas. La ruta directa requiere `DEVICE_TOKEN`:

```text
/api/v1/items/ID_DEL_ARCHIVO/stream
```

`/api/v1/items` sigue disponible como lista plana y también requiere `DEVICE_TOKEN`. Evita guardar rutas `/api/v1/playback/...` en logs de acceso del reverse proxy, porque contienen permisos temporales de reproducción.

Para consultar o revocar dispositivos vinculados, usa el token de un dispositivo conectado con la cuenta administradora:

```sh
curl http://localhost:8180/api/v1/admin/devices -H "Authorization: Bearer $DEVICE_TOKEN"
curl -X DELETE http://localhost:8180/api/v1/admin/devices/ID_DEL_DISPOSITIVO \
  -H "Authorization: Bearer $DEVICE_TOKEN"
```

## Cliente Android

El código del cliente está en [`client/`](client/). Para compilar el APK de depuración con Android SDK Platform 35 y Build Tools 36.0.0:

```sh
./client/build-apk.sh
```

El archivo queda en `client/build/outputs/apk/debug/zinema-client-debug.apk`. El cliente se conecta al dominio público configurado en la app y permite cambiarlo desde ajustes.

## Fuentes y acceso

TMDb proporciona metadata e imágenes para este proyecto. La aplicación debe mostrar la atribución requerida por TMDb: **“This product uses the TMDB API but is not endorsed or certified by TMDB.”** Si el uso pasa a ser comercial, revisa las condiciones y licencia con TMDb. Zinema conserva los IDs de IMDb que TMDb devuelva, pero no consulta ni extrae datos directamente de IMDb.

Todas las rutas `/api/v1` que consultan datos requieren un token válido salvo `/health`; registro y login son públicos para iniciar sesión. Cada cliente usa un token individual revocable y las operaciones administrativas verifican el rol de la cuenta asociada. Mantén HTTPS y, si es posible, limita además el acceso a través de una VPN. Las URLs temporales de reproducción deben excluirse de los logs del proxy.

## Comprobaciones

```sh
./check-nas.sh
./smoke-test.sh
```

El primer escaneo completo puede tardar según el tamaño de las bibliotecas y la respuesta de TMDb. Los datos anteriores se conservan en SQLite y `./data/artwork` entre reconstrucciones del contenedor.
