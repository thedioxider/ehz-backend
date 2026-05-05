#!/bin/sh
set -e
cd "$(dirname "$0")/.."

echo "ВНИМАНИЕ: база данных и данные приложения (фотографии, учётные данные) будут удалены без возможности восстановления."
echo "Перед сбросом будет автоматически создана резервная копия."
echo ""

printf 'Введите "да" для подтверждения: '
read -r CONFIRM
if [ "$CONFIRM" != "да" ]; then
    echo "Отменено."
    exit 0
fi
echo ""

if [ -n "$(docker compose ps -q 2>/dev/null)" ]; then
    echo "Остановка сервиса..."
    docker compose down
fi

sh "$(dirname "$0")/backup.sh"

echo "Удаление данных..."
docker volume rm -f ehz_pgdata ehz_appdata

echo "Готово."
