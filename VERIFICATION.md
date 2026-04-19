# Verification runbook

End-to-end smoke test of the composed ehz-backend stack. Run from the repo root
on a host with a working Docker daemon. Each step pastes the command and the
expected output so it can be used as a self-check.

This is the runtime counterpart to `./gradlew test` — unit and integration
tests (42 with Testcontainers) are green, the runbook below asserts that the
composed stack actually behaves as designed in production.

## Prerequisites

- Docker 24+ and `docker compose` v2.
- `curl` and basic `coreutils` (`sha256sum`, `base64`, `stat`).
- `socat` or `netcat` for the UDP discovery probe. On Linux distros where
  neither is installed, use `python3` — a fallback snippet is shown in step 10.
- A LAN IP on the host (check with `ip -4 -o addr show scope global`). Steps 4d
  and 11 require it.

## Stack layout (see `docker-compose.yml`)

- `db` — Postgres 16, internal only.
- `app` — Spring Boot; container ports 8080 (dashboard, mgmt child context),
  8081 (LAN ingest), 8082 (internal). Compose publishes 8080 → `127.0.0.1:19080`
  (loopback-only) and 8081 → `0.0.0.0:19081` (LAN). 8082 is deliberately **not**
  published.
- `discovery-sidecar` — JmDNS announcer + UDP `5370` responder on
  `network_mode: host`.

All persistent state lives under `./data/` (`pgdata/`, `photos/`,
`credentials.txt`).

## Automatable checks (single-host)

### 1. Unit + integration tests

```sh
./gradlew :app:test :discovery-sidecar:test --no-daemon
```

Expected: `BUILD SUCCESSFUL`, 42 total tests, 0 failures.

Aggregate over the JUnit XMLs if you want a one-shot count:

```sh
grep -hoE 'tests="[0-9]+"' app/build/test-results/test/*.xml \
  discovery-sidecar/build/test-results/test/*.xml \
  | awk -F'"' '{sum+=$2} END {print sum}'
# => 42
```

### 2. Bring the stack up

From a clean state (no `./data/` and no lingering containers):

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build -d
docker compose ps
```

Expected: three services running, `db` and `app` both `(healthy)`.

```
NAME                              SERVICE             STATUS
ehz-backend-app-1                 app                 Up X seconds (healthy)
ehz-backend-db-1                  db                  Up X seconds (healthy)
ehz-backend-discovery-sidecar-1   discovery-sidecar   Up X seconds
```

Give the `app` healthcheck up to 60 s on first boot (JVM warmup + Liquibase
migrations + Tomcat connectors).

### 3. First-boot secret generation

```sh
ls -la ./data/credentials.txt
stat -c '%a %n' ./data/credentials.txt
cat ./data/credentials.txt
```

Expected:

- File exists, mode `600`, owned by the user that ran compose.
- Contents are exactly one line: `EHZ_API_KEY=<base64url>`.

### 4. Dashboard on localhost only

```sh
curl -sS -o /tmp/dash.html -w 'HTTP %{http_code}\n' http://localhost:19080/
grep -c ehz-backend /tmp/dash.html             # expect >=1
grep -c "$(grep EHZ_API_KEY ./data/credentials.txt | cut -d= -f2)" /tmp/dash.html  # expect 1

curl -sS http://localhost:19080/actuator/health
# {"status":"UP",...}

ss -ltn | grep 19080
# LISTEN ... 127.0.0.1:19080 ...   (NOT 0.0.0.0)

LAN_IP=$(ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1 | head -1)
curl -sS --max-time 3 "http://$LAN_IP:19080/"
# curl: (7) Failed to connect ...
```

Expected: 200 from loopback, HTML body contains "ehz-backend" and the plaintext
API key; actuator/health reports `UP`; bind is `127.0.0.1:19080`; a connect
attempt on the LAN address fails.

### 5. LAN ingest port: enforcement + contract header

```sh
API_KEY=$(grep EHZ_API_KEY ./data/credentials.txt | cut -d= -f2)

# 5a. ping
curl -sS -D- http://localhost:19081/api/ping
# HTTP/1.1 200
# X-Contract-Version: 1
# Content-Type: application/json
#
# {"pong":true}

# 5b. unauthenticated POST
curl -sS -D- -X POST http://localhost:19081/api/reports \
  -H 'Content-Type: application/json' --data '[]'
# HTTP/1.1 401
# WWW-Authenticate: Bearer
# X-Contract-Version: 1
# {"error":"unauthorized"}

# 5c. authenticated POST with the sample payload
curl -sS -D- -X POST http://localhost:19081/api/reports \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $API_KEY" \
  --data @docs/sample-ingest.json
# (or inline — see the sample payload at the end of this doc)
# HTTP/1.1 201
# X-Contract-Version: 1
# {"accepted":1}

# 5d. read endpoint is blocked on the LAN port even with auth
curl -sS -D- -H "Authorization: Bearer $API_KEY" \
  http://localhost:19081/api/reports
# HTTP/1.1 404
# X-Contract-Version: 1
# {"error":"not found"}
```

### 6. Internal port 8082 is not published

```sh
curl --max-time 3 http://localhost:8082/api/reports
# curl: (7) Failed to connect ...

ss -ltn | grep ':8082' || echo "no listener"
# no listener

# From inside the Docker network (db container already has busybox wget).
docker compose exec -T db wget -qO- http://app:8082/api/reports
# {"content":[{...}], "page": {...}}  -- the previously-ingested report
```

Expected: connection refused from the host; a listener exists on the Docker
bridge side and returns the report list.

### 7. Photo round-trip on disk

The sample payload embeds an 80-byte valid WebP; after the ingest in step 5c:

```sh
REPORT_ID=11111111-1111-4111-8111-111111111111
SITE_ID=22222222-2222-4222-8222-222222222222
ls -la ./data/photos/$REPORT_ID/$SITE_ID/site_photo.webp

# Expected SHA matches the base64-decoded payload value.
PAYLOAD_B64='UklGRjIAAABXRUJQVlA4IAYAAADwAQCdASoBAAEAAMAkJaACdLoB+AAETAAA/vIv+gAA'
printf '%s' "$PAYLOAD_B64" | base64 -d | sha256sum
sha256sum ./data/photos/$REPORT_ID/$SITE_ID/site_photo.webp
# Both print the same hex digest.
```

### 8. Persistence across restart

```sh
API_KEY_BEFORE=$(grep EHZ_API_KEY ./data/credentials.txt | cut -d= -f2)
docker compose down
docker compose up -d

# Wait for healthy; then:
grep EHZ_API_KEY ./data/credentials.txt | cut -d= -f2  # same as $API_KEY_BEFORE
curl -sS http://localhost:19080/ | grep -c "$API_KEY_BEFORE"  # 1
docker compose exec -T db wget -qO- http://app:8082/api/reports  # still shows the report
ls ./data/photos/$REPORT_ID/$SITE_ID/site_photo.webp  # still there
```

Expected: API key is unchanged, report is still queryable, photo still on disk.

### 9. Contract-version header on error paths

```sh
curl -sS -D- http://localhost:19081/api/does-not-exist
# HTTP/1.1 404
# X-Contract-Version: 1
```

The `ContractVersionFilter` sits at `HIGHEST_PRECEDENCE + 10`, before the
short-circuit from `LanIngestFilter`, so the header appears on every
`/api/**` response, including 401 and 404.

### 10. UDP discovery probe

The protocol: send the US-ASCII literal `EHZ_DISCOVER_V1` as a UDP datagram to
port `5370`; expect a unicast reply with a JSON `{ip, port, apiBasePath,
version}`.

```sh
# Preferred (socat):
printf 'EHZ_DISCOVER_V1' | socat -t3 - UDP-DATAGRAM:127.0.0.1:5370

# Fallback (python3):
python3 -c 'import socket; s=socket.socket(socket.AF_INET, socket.SOCK_DGRAM); \
  s.settimeout(3); s.sendto(b"EHZ_DISCOVER_V1", ("127.0.0.1",5370)); \
  print(s.recvfrom(4096)[0].decode())'

# Expected:
# {"ip":"<host-lan-ip>","port":19081,"apiBasePath":"/api","version":1}
```

The authoritative LAN discovery test is item 11 below from a second machine —
probing via the host loopback only exercises local sanity.

## Operator-checklist items (second LAN machine)

These can't be automated on the backend host alone. Run them from a second
machine on the same LAN.

### 11. LAN discovery + reachability

```sh
# mDNS browse
avahi-browse -r _ehz-backend._tcp
# Expect one entry pointing at <backend-lan-ip>:19081 with TXT version=1,
# apiBasePath=/api, plus an A-record for ehz-backend.local.

# API through the mDNS hostname
curl http://ehz-backend.local:19081/api/ping
# {"pong":true}  with X-Contract-Version: 1

# UDP broadcast discovery
echo -n EHZ_DISCOVER_V1 | socat - UDP-DATAGRAM:255.255.255.255:5370,broadcast
# {"ip":"<lan-ip>","port":19081,"apiBasePath":"/api","version":1}
```

If the second machine runs on a different subnet or has mDNS disabled
(systemd-resolved with `MulticastDNS=no`, some enterprise Wi-Fi), fall back
to the UDP broadcast probe.

### 12. Air-gap install smoke

On a host with internet:

```sh
./gradlew release -Pehz.version=0.0.0-dry -x pushImages
# Produces build/release/ehz-backend-0.0.0-dry.zip
```

Transfer the ZIP to a fresh (ideally offline) host, then:

```sh
unzip ehz-backend-0.0.0-dry.zip -d ehz-backend
cd ehz-backend
sha256sum -c SHA256SUMS
docker load < images/ehz-backend-app-0.0.0-dry.tar.gz
docker load < images/ehz-backend-sidecar-0.0.0-dry.tar.gz
EHZ_VERSION=0.0.0-dry docker compose up -d
```

Then rerun steps 3–7 and 11 against that host to confirm the air-gap package
behaves identically to the Docker Hub path.

## Troubleshooting

- **Port already in use on 19080/19081.** Compose fails loudly at `up` time
  with a bind error — change the host-side port in `docker-compose.yml`
  (`"127.0.0.1:19080:8080"` → a different high port) and rerun.
- **Healthcheck stays `starting` past 60 s.** Inspect logs:
  `docker compose logs app --tail=200`. Common causes: Postgres not healthy
  (check `db` logs), Liquibase migration error (look for "Validation Failed"),
  or entropy-starved container causing `SecureRandom.nextBytes` slow-start
  (shouldn't happen — we use `nextBytes` not `generateSeed`, but on very old
  kernels it can still block).
- **`credentials.txt` keeps rotating.** It shouldn't — it's written once on
  first boot. If it rotates, check the file mode (must be `600`; if the
  bind-mount ends up owned by root the app may fail to read it and fall back
  to regeneration). Inspect with `docker compose exec app ls -la /data`.
- **LAN ingest 401 with a key that matches `credentials.txt`.** Most likely a
  CR/LF artefact in the shell — make sure you strip whitespace:
  `API_KEY=$(grep EHZ_API_KEY ./data/credentials.txt | cut -d= -f2 | tr -d '\r\n ')`.
- **Discovery sidecar logs "multiple LAN IPv4 candidates found".** The sidecar
  picked the first non-loopback IPv4 alphabetically. If it chose the wrong NIC
  (e.g. a Docker bridge address instead of your WLAN address), set
  `EHZ_ANNOUNCE_IP=<the-right-ip>` in `.env` and `docker compose up -d`.

## Sample ingest payload

Matches the seed UUIDs in `app/src/main/resources/seed/initial_areas.yaml`
(so the catalog upsert is a no-op).

```json
[
  {
    "id": "11111111-1111-4111-8111-111111111111",
    "inspector": "Тест Тестович",
    "route":   { "id": "5cb6f3ca-6b42-4a76-a7d9-2c12b1e8f0d3", "name": "САЦ-III", "length_km": 3600 },
    "section": { "id": "f19c3e67-04d1-4c3f-9b8d-8a2ed6f54e2f", "begin_km": 0, "end_km": 41 },
    "start_time": 1745000000,
    "end_time":   1745000600,
    "sites": [
      {
        "id": "22222222-2222-4222-8222-222222222222",
        "timestamp": 1745000100,
        "position_km": 5.5,
        "location": null,
        "validation_id": null,
        "fields": [
          {"spec_id":"site_photo","name":"Фотография точки","type":"photo","value":"UklGRjIAAABXRUJQVlA4IAYAAADwAQCdASoBAAEAAMAkJaACdLoB+AAETAAA/vIv+gAA","unit":null},
          {"spec_id":"comment","name":"Комментарий","type":"text","value":"e2e ok","unit":null}
        ]
      }
    ]
  }
]
```

The photo value is a valid 51-byte WebP header (the minimum a decoder will
accept). Step 7's SHA256 check asserts byte-level round-trip, not semantic
WebP validity.
