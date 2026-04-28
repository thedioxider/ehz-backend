$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot)

$archive = $args[0]
if (-not $archive) {
    Write-Host "Использование: .\data\restore.ps1 <имя-архива>"
    Write-Host "Доступные архивы:"
    $found = Get-Item data\ehz-backup-*.tar.gz -ErrorAction SilentlyContinue
    if ($found) { $found | ForEach-Object { Write-Host "  $($_.Name)" } } else { Write-Host "  (нет)" }
    exit 1
}

$dataPath = (Resolve-Path "data").Path

Write-Host "Остановка сервиса..."
docker compose stop

Write-Host "Восстановление из $archive..."
docker volume rm ehz_pgdata ehz_appdata 2>&1 | Out-Null
docker volume create ehz_pgdata
docker volume create ehz_appdata
docker run --rm `
    -v ehz_pgdata:/dst/pgdata `
    -v ehz_appdata:/dst/appdata `
    -v "${dataPath}:/src:ro" `
    alpine tar xzf "/src/$archive" -C /dst

Write-Host "Запуск сервиса..."
docker compose start
Write-Host "Готово."
