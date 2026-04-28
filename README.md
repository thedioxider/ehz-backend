# ehz-backend

Field-deployed diagnostic report ingestion service. Runs as three containers
behind a single `docker compose up -d`: Postgres, the Spring Boot app, and a
discovery service that announces the backend on the LAN.

## Install from Docker Hub

Requires Docker + Docker Compose on the host.

```sh
mkdir ehz && cd ehz
curl -fLO https://raw.githubusercontent.com/diomentia/ehz-backend/v<VER>/docker-compose.yml
curl -fLO https://raw.githubusercontent.com/diomentia/ehz-backend/v<VER>/.env.example
mv .env.example .env
docker compose up -d
```

After first boot, the auto-generated API key lives at `./data/credentials.txt`.
Open `http://localhost:19080` for the operator dashboard.

## Install from ZIP (air-gap)

For hosts without internet. Download `ehz-backend-<VER>.zip` on a connected
machine, transfer it to the target, then:

```sh
unzip ehz-backend-<VER>.zip
cd ehz-backend-<VER>
sha256sum -c SHA256SUMS
docker load < images/ehz-backend-app-<VER>.tar.gz
docker load < images/ehz-backend-discovery-<VER>.tar.gz
docker compose up -d
```

The ZIP ships the same `docker-compose.yml` that references images by tag;
because the images are now pre-loaded locally, compose finds them without
reaching out to Docker Hub.

## Contributor build

Builds both images from source via the dev overlay:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build -d
```

Builds use BuildKit cache mounts so repeated builds reuse the Gradle cache.

## Ports

- `127.0.0.1:19080` — operator dashboard (localhost only, never exposed to LAN)
- `<lan-ip>:19081` — app ingest, API-key protected (this is what the Android app talks to)
- `8082` inside Docker only — internal API for sibling services (future frontend)
- UDP 5353 (mDNS) + UDP 5370 (broadcast discovery) — on the host network via the discovery service

## Data

Everything persistent lives under `./data`:

- `./data/pgdata/` — Postgres storage
- `./data/photos/` — uploaded report photos
- `./data/credentials.txt` — auto-generated API key (mode 600)
- `./data/config/` — operator-editable config overrides

Back up the whole `./data` directory to back up the service.
