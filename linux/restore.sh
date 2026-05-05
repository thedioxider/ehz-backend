#!/bin/sh
set -e
cd "$(dirname "$0")/.."

ARCHIVE="$1"
if [ -z "$ARCHIVE" ]; then
    echo "Использование: sh data/restore.sh <имя-архива>"
    echo "Доступные архивы:"
    ls data/ehz-backup-*.tar.gz 2>/dev/null || echo "  (нет)"
    exit 1
fi

if [ -n "$(docker compose ps -q 2>/dev/null)" ]; then
    echo "Остановка сервиса..."
    docker compose stop
fi

echo "Восстановление из $ARCHIVE..."
docker volume rm ehz_pgdata ehz_appdata 2>/dev/null || true
docker volume create ehz_pgdata
docker volume create ehz_appdata
docker run --rm \
    -v ehz_pgdata:/dst/pgdata \
    -v ehz_appdata:/dst/appdata \
    -v "$(pwd)/data:/src:ro" \
    alpine tar xzf "/src/$ARCHIVE" -C /dst

echo "Запуск сервиса..."
docker compose start
echo "Готово."
