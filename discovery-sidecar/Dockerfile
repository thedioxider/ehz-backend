# syntax=docker/dockerfile:1.7
#
# Build context is the REPO ROOT (not discovery-sidecar/) so the builder has
# access to the Gradle wrapper and both subprojects.
#
#   docker build -f discovery-sidecar/Dockerfile -t ehz-backend-sidecar .
#

# ---------- build stage ----------
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /src

COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts app/build.gradle.kts
COPY discovery-sidecar/build.gradle.kts discovery-sidecar/build.gradle.kts

RUN chmod +x ./gradlew

COPY app/src app/src
COPY discovery-sidecar/src discovery-sidecar/src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :discovery-sidecar:shadowJar --no-daemon -x test

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine

RUN adduser -D -H -u 1000 ehz && \
    mkdir -p /app

COPY --from=builder /src/discovery-sidecar/build/libs/*.jar /app/sidecar.jar

USER ehz
WORKDIR /app

# Documentary only: the sidecar runs with network_mode: host in compose, which
# ignores EXPOSE. Listed here so `docker image inspect` still tells the story.
EXPOSE 5353/udp 5370/udp

# No HEALTHCHECK — the sidecar doesn't expose an HTTP endpoint. Operator
# observes liveness via `docker ps` / logs.

ENTRYPOINT ["java","-jar","/app/sidecar.jar"]
