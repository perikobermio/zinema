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

Requisitos: Docker Engine con Docker Compose v2, `curl` y acceso de lectura del host a las dos rutas del NAS.

1. En el NAS, clona el proyecto y crea su configuración:

   ```sh
   git clone https://github.com/perikobermio/zinema.git ~/apps/zinema
   cd ~/apps/zinema
   cp .env.example .env
   nano .env
   ```

2. Configura `MOVIES_PATH`, `SERIES_PATH`, un `ADMIN_TOKEN` y un `PROFILE_SYNC_TOKEN` propios. El token de perfiles debe ser el mismo en todos los clientes y se introduce en Administración → sincronización de perfiles. Para TMDb puedes guardar la **API Key v3** en `TMDB_KEY` o el **API Read Access Token** en `TMDB_ACCESS_TOKEN`; basta con configurar uno. No pongas estas credenciales en el cliente ni las subas a Git. `TMDB_LANGUAGE` controla el idioma preferido de los resultados; por defecto es `es-ES`.

3. Valida las rutas y arranca el servidor:

   ```sh
   ./check-nas.sh
   docker compose up --build -d
   docker compose ps
   docker compose logs -f zinema
   ```

El NAS y SQLite se montan de forma persistente en `./data`; las carpetas de medios se montan en solo lectura. La API está en el puerto `8180` del host.

## Lanzar la sincronización inicial

La sincronización vuelve a escanear las carpetas y busca metadata de películas y series en TMDb. Se ejecuta como trabajo en segundo plano:

```sh
./start-import.sh
```

El script lee `ADMIN_TOKEN` de `.env`, inicia el trabajo y muestra su ID. Para usar una URL distinta, ejecuta `API_BASE_URL=https://zinema.tudominio.com ./start-import.sh`. Consulta el progreso con la URL y el comando que imprime el script, o con:

```sh
set -a
. ./.env
set +a
curl http://localhost:8180/api/v1/admin/sync/ID_DEL_TRABAJO \
  -H "Authorization: Bearer $ADMIN_TOKEN"
unset ADMIN_TOKEN
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
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Para aceptar una coincidencia, envía el `tmdb_id` elegido:

```sh
curl -X POST 'http://localhost:8180/api/v1/admin/catalog/ID_DEL_NODO/match?tmdb_id=ID_TMDB' \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Tras una sincronización inicial completada, el servidor vuelve a escanear diariamente. Solo consulta TMDb para entradas nuevas o cuya metadata lleve 30 días sin actualizar. Puedes cambiar el intervalo y el tamaño de lote con `TMDB_SYNC_INTERVAL_HOURS` y `TMDB_SYNC_BATCH_SIZE`.

## API para el cliente

El catálogo se navega por nivel, usando `parent_id` para abrir una colección, serie o temporada:

```sh
curl 'http://localhost:8180/api/v1/catalog?library=movies'
curl 'http://localhost:8180/api/v1/catalog?library=series'
curl 'http://localhost:8180/api/v1/catalog?library=series&parent_id=ID_DEL_NODO'
curl 'http://localhost:8180/api/v1/catalog/ID_DEL_NODO'
```

Al reintentar metadata desde el cliente, `POST /api/v1/catalog/ID_DEL_NODO/metadata/candidates` busca coincidencias TMDb para validarlas antes de guardarlas. `GET /api/v1/catalog/ID_DEL_NODO/metadata/candidate/ID_TMDB` carga sus datos completos. La edición manual se guarda con `POST /api/v1/catalog/ID_DEL_NODO/metadata/manual`. Para buscar imágenes se usa `GET /api/v1/catalog/ID_DEL_NODO/metadata/images?q=TITULO`; devuelve hasta diez imágenes TMDb.

Las ediciones manuales permanecen guardadas y no las reemplaza la sincronización diaria. Se pueden volver a solicitar coincidencias después de un minuto por elemento.

Al asociar una serie con TMDb, el servidor consulta también los episodios para guardar sus títulos. Si TMDb no tiene un título para el episodio, el cliente muestra el nombre del archivo. En temporadas intenta usar el póster de temporada, una imagen del primer episodio y, como último recurso, el póster de la serie.

Los nodos y archivos heredan los géneros y el póster de la colección ancestro más cercana cuando no tienen esos datos propios. Un póster o género asignado al nodo prevalece sobre el heredado. `GET /api/v1/genres` devuelve la lista sin duplicados de los géneros que ya aparecen en el catálogo; al importar metadata nueva, sus géneros pasan a estar disponibles automáticamente.

Cada nodo incluye `kind`, `display_title`, `overview`, `year`, `genres`, `poster_url`, `backdrop_url`, `children`, `files` y `mosaic_images`. Los archivos reproducibles conservan sus IDs y URL de streaming:

```text
/api/v1/items/ID_DEL_ARCHIVO/stream
```

`/api/v1/items` sigue disponible como lista plana. `/health` permite comprobar que el servidor responde.

## Cliente Android

El código del cliente está en [`client/`](client/). Para compilar el APK de depuración con Android SDK Platform 35 y Build Tools 36.0.0:

```sh
./client/build-apk.sh
```

El archivo queda en `client/build/outputs/apk/debug/zinema-client-debug.apk`. El cliente se conecta al dominio público configurado en la app y permite cambiarlo desde ajustes.

## Fuentes y acceso

TMDb proporciona metadata e imágenes para este proyecto. La aplicación debe mostrar la atribución requerida por TMDb: **“This product uses the TMDB API but is not endorsed or certified by TMDB.”** Si el uso pasa a ser comercial, revisa las condiciones y licencia con TMDb. Zinema conserva los IDs de IMDb que TMDb devuelva, pero no consulta ni extrae datos directamente de IMDb.

Las rutas de catálogo, metadata e imágenes no requieren autenticación. Las rutas bajo `/api/v1/admin` requieren `ADMIN_TOKEN`; las rutas `/api/v1/profiles` requieren `PROFILE_SYNC_TOKEN` y sincronizan perfiles, avatares y su estado local entre dispositivos. Mantén el servicio dentro de una red privada o protégelo detrás de una VPN/autenticación antes de hacerlo accesible desde Internet.

## Comprobaciones

```sh
./check-nas.sh
./smoke-test.sh
```

El primer escaneo completo puede tardar según el tamaño de las bibliotecas y la respuesta de TMDb. Los datos anteriores se conservan en SQLite y `./data/artwork` entre reconstrucciones del contenedor.
