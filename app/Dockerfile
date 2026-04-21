# syntax=docker/dockerfile:1.7
#
# Build context is the REPO ROOT (not app/) so the builder has access to the
# Gradle wrapper, settings, and both subprojects.
#
#   docker build -f app/Dockerfile -t ehz-backend-app .
#

# ---------- build stage ----------
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /src

# Wrapper + settings first so we can resolve the Gradle distribution and most
# plugins before the source changes. These layers survive most code edits.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts app/build.gradle.kts
COPY discovery-sidecar/build.gradle.kts discovery-sidecar/build.gradle.kts

RUN chmod +x ./gradlew

# Source. Copying both modules keeps the settings include(...) happy even
# though we only build :app:bootJar here.
COPY app/src app/src
COPY discovery-sidecar/src discovery-sidecar/src

# BuildKit cache mount on ~/.gradle speeds up incremental builds substantially.
# Tests are run in CI, not here.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :app:bootJar --no-daemon -x test

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine

# su-exec lets the entrypoint drop from root to the `ehz` user after fixing
# bind-mount ownership. BusyBox `wget` is already on PATH in the base image.
RUN apk add --no-cache su-exec && \
    adduser -D -H -u 1000 ehz && \
    mkdir -p /app /data

COPY --from=builder /src/app/build/libs/*.jar /app/app.jar
COPY app/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

WORKDIR /app

# 8080 = management/dashboard, 8081 = LAN ingest, 8082 = internal API.
# Host-side exposure is decided by compose (`ports:` publishes 8080+8081 only;
# 8082 stays Docker-network-only). The management listener binds 0.0.0.0 inside
# the container so Docker's loopback-only publish `127.0.0.1:19080:8080` can
# reach it; that host-side binding is what keeps the dashboard off the LAN.
EXPOSE 8080 8081 8082

# Healthcheck hits the management port on loopback inside the container — that
# works even though 8080 is 127.0.0.1-bound (it's loopback within the netns).
HEALTHCHECK --interval=30s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["java","-jar","/app/app.jar"]
