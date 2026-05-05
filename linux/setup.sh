#!/bin/sh
set -e
cd "$(dirname "$0")"

if ! command -v docker >/dev/null 2>&1; then
    echo "Ошибка: Docker не установлен. Следуйте инструкциям в ИНСТРУКЦИЯ.md"
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "Ошибка: Docker Compose не найден. Следуйте инструкциям в ИНСТРУКЦИЯ.md"
    exit 1
fi

APP_IMAGE=$(ls img/ehz-backend-app-*.tar.gz 2>/dev/null | head -1)
DISCOVERY_IMAGE=$(ls img/ehz-backend-discovery-*.tar.gz 2>/dev/null | head -1)

if [ -z "$APP_IMAGE" ] || [ -z "$DISCOVERY_IMAGE" ]; then
    echo "Ошибка: образы не найдены в папке img/"
    exit 1
fi

echo "Загрузка образов..."
docker load < "$APP_IMAGE"
docker load < "$DISCOVERY_IMAGE"
docker tag diomentia/ehz-backend-app:$(echo "$APP_IMAGE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+') diomentia/ehz-backend-app:latest
docker tag diomentia/ehz-backend-discovery:$(echo "$DISCOVERY_IMAGE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+') diomentia/ehz-backend-discovery:latest

echo "Запуск сервиса... (для остановки нажмите Ctrl+C)"
echo "Панель управления: http://localhost:19080"
echo ""
docker compose up
