# ehz-backend

Field-deployed diagnostic report ingestion service. Runs as three containers
behind a single `docker compose up`: Postgres, the Spring Boot app, and a
discovery service that announces the backend on the LAN.

## Install from Docker Hub

Requires Docker + Docker Compose on the host.

```sh
mkdir ehz && cd ehz
curl -fLO https://raw.githubusercontent.com/diomentia/ehz-backend/v<VER>/docker-compose.yml
curl -fLO https://raw.githubusercontent.com/diomentia/ehz-backend/v<VER>/.env.example
mv .env.example .env
docker compose up
```

Open `http://localhost:19080` for the operator dashboard. The API key is shown there.

## Install from ZIP (air-gap)

Two platform ZIPs are published with each release:
`ehz-backend-<VER>-linux.zip` and `ehz-backend-<VER>-windows.zip`.

Download the appropriate ZIP on a connected machine, transfer it to the target,
verify, and run the setup script. See `ИНСТРУКЦИЯ.md` inside the ZIP.

## Contributor build

Builds both images from source via the dev overlay:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
```

Builds use BuildKit cache mounts so repeated builds reuse the Gradle cache.

## Ports

- `127.0.0.1:19080` — operator dashboard (localhost only, never exposed to LAN)
- `<lan-ip>:19081` — LAN ingest, API-key protected (what the Android app talks to)
- `8082` inside Docker only — internal API for sibling services (future frontend)
- UDP 5353 (mDNS) + UDP 5370 (broadcast discovery) — host network via the discovery service

## Data

All persistent data lives in Docker named volumes (`ehz_pgdata`, `ehz_appdata`).
Backup and restore via the scripts in `tools/` inside the release ZIP, or
`linux/backup.sh` / `windows/backup.ps1` in the repo.
