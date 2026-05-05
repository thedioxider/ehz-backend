$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot)

Write-Host "ВНИМАНИЕ: база данных и данные приложения (фотографии, учётные данные) будут удалены без возможности восстановления."
Write-Host "Перед сбросом будет автоматически создана резервная копия."
Write-Host ""

$confirm = Read-Host 'Введите "да" для подтверждения'
if ($confirm -ne "да") {
    Write-Host "Отменено."
    exit 0
}
Write-Host ""

if (docker compose ps -q 2>$null) {
    Write-Host "Остановка сервиса..."
    docker compose down
}

& "$PSScriptRoot\backup.ps1"

Write-Host "Удаление данных..."
docker volume rm -f ehz_pgdata ehz_appdata

Write-Host "Готово."
