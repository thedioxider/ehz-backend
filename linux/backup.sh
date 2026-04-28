#!/bin/sh
set -e
cd "$(dirname "$0")/.."

mkdir -p data
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
ARCHIVE="ehz-backup-$TIMESTAMP.tar.gz"

echo "Остановка сервиса..."
docker compose stop

echo "Создание резервной копии..."
docker run --rm \
    -v ehz_pgdata:/src/pgdata:ro \
    -v ehz_appdata:/src/appdata:ro \
    -v "$(pwd)/data:/out" \
    alpine tar czf "/out/$ARCHIVE" -C /src .

echo "Запуск сервиса..."
docker compose start

echo "Готово: data/$ARCHIVE"
