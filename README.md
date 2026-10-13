# Zinema Server

Servidor propio para catalogar y reproducir archivos de vídeo guardados en el NAS. En esta versión no necesita Jellyfin: escanea las dos carpetas configuradas, mantiene el catálogo en SQLite y entrega los vídeos por HTTP con soporte de rangos, necesario para buscar dentro del vídeo y reanudar reproducción.

## Instalar en el equipo que tiene acceso al NAS

Requisitos: Docker Engine con Docker Compose v2 y acceso de lectura a estas rutas del host:

- Películas: `/d/NAS/Pelikulek`
- Series: `/d/NAS/Serijek`

Desde `/home/projects/zinema` ya está preparado un `.env` local con las dos rutas y un token aleatorio. Si partes de un clone limpio, créalo así y cambia `ADMIN_TOKEN` por un secreto largo:

```sh
cp .env.example .env
```

Las rutas están configuradas con las que has proporcionado. Comprueba que Docker puede leer ambas carpetas y luego ejecuta:

```sh
docker compose up --build -d
docker compose ps
docker compose logs -f zinema
```

El catálogo se conserva en `./data`; el NAS se monta en modo solo lectura. La API queda disponible en el puerto `8180` del equipo anfitrión.

## Comprobar catálogo y reproducción

En otra terminal, desde la carpeta del proyecto, carga el token y lanza el escaneo:

```sh
set -a; . ./.env; set +a
curl -X POST http://localhost:8180/api/v1/admin/scan \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

La respuesta muestra cuántos archivos encontró. Comprueba que el servidor está activo y consulta los primeros elementos:

```sh
curl http://localhost:8180/health
curl 'http://localhost:8180/api/v1/items?limit=10'
curl 'http://localhost:8180/api/v1/items?q=nombre%20de%20pelicula'
```

En la lista, copia el `id` de un vídeo real y prueba una descarga parcial de los primeros 1 KiB:

```sh
curl -v -H 'Range: bytes=0-1023' \
  http://localhost:8180/api/v1/items/ID_DEL_VIDEO/stream \
  -o /tmp/zinema-fragmento.bin
```

Debe devolver `206 Partial Content`, `Accept-Ranges: bytes` y `Content-Range: bytes 0-1023/TAMANO`. Comprueba el fragmento con `file /tmp/zinema-fragmento.bin`. Para probar reproducción completa, abre en VLC `http://IP_DEL_SERVIDOR:8180/api/v1/items/ID_DEL_VIDEO/stream` desde un dispositivo de la misma red.

Desde otro equipo, sustituye `localhost` por la IP del equipo que ejecuta Docker: `http://IP_DEL_SERVIDOR:8180/api/v1/items/ID_DEL_VIDEO/stream`. Si funciona localmente pero no desde el móvil/TV, revisa el firewall y que ambos estén en la misma red.

## Límites de esta primera versión

- Sirve los formatos tal como están en el NAS (Direct Play); todavía no convierte formatos incompatibles.
- El escaneo es manual y se inicia con el token de administrador.
- El catálogo guarda título provisional derivado del nombre del archivo. Todavía no importa metadatos ni crea temporadas y episodios a partir de la estructura.
- La API de catálogo y streaming está pensada para una red privada de confianza. No la publiques directamente en internet; autenticación por usuario y HTTPS quedan para antes del acceso remoto.
- Si una ruta no está montada al escanear, el servidor lo informa y no modifica el catálogo. Los archivos que desaparecen de un escaneo se marcan como no disponibles sin borrar sus registros.
