#!/bin/sh
set -e
cd "$(dirname "$0")"

APP_IMAGE=$(ls img/ehz-backend-app-*.tar.gz 2>/dev/null | head -1)
DISCOVERY_IMAGE=$(ls img/ehz-backend-discovery-*.tar.gz 2>/dev/null | head -1)

if [ -z "$APP_IMAGE" ] || [ -z "$DISCOVERY_IMAGE" ]; then
    echo "Ошибка: образы не найдены в папке img/"
    exit 1
fi

echo "Загрузка образов..."
docker load < "$APP_IMAGE"
docker load < "$DISCOVERY_IMAGE"

echo "Запуск сервиса... (для остановки нажмите Ctrl+C)"
echo "Панель управления: http://localhost:19080"
echo ""
docker compose up
