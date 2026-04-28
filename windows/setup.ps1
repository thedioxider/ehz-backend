$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$appImage = Get-Item img\ehz-backend-app-*.tar.gz -ErrorAction SilentlyContinue | Select-Object -First 1
$discoveryImage = Get-Item img\ehz-backend-discovery-*.tar.gz -ErrorAction SilentlyContinue | Select-Object -First 1

if (-not $appImage -or -not $discoveryImage) {
    Write-Error "Ошибка: образы не найдены в папке img\"
    exit 1
}

Write-Host "Загрузка образов..."
Get-Content $appImage.FullName -Raw | docker load
Get-Content $discoveryImage.FullName -Raw | docker load

Write-Host "Запуск сервиса... (для остановки нажмите Ctrl+C)"
Write-Host "Панель управления: http://localhost:19080"
Write-Host ""
docker compose up
