$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot)

New-Item -ItemType Directory -Force data | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$archive = "ehz-backup-$timestamp.tar.gz"
$dataPath = (Resolve-Path "data").Path

$wasRunning = docker compose ps -q 2>$null
if ($wasRunning) {
    Write-Host "Остановка сервиса..."
    docker compose stop
}

Write-Host "Создание резервной копии..."
docker run --rm `
    -v ehz_pgdata:/src/pgdata:ro `
    -v ehz_appdata:/src/appdata:ro `
    -v "${dataPath}:/out" `
    alpine tar czf "/out/$archive" -C /src .

Write-Host "Готово: data\$archive"
