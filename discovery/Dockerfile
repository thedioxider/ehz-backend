# syntax=docker/dockerfile:1.7
#
# Build context is the REPO ROOT (not discovery/) so the builder has
# access to the Gradle wrapper and both subprojects.
#
#   docker build -f discovery/Dockerfile -t ehz-backend-discovery .
#

# ---------- build stage ----------
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /src

COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts app/build.gradle.kts
COPY discovery/build.gradle.kts discovery/build.gradle.kts

RUN chmod +x ./gradlew

COPY app/src app/src
COPY discovery/src discovery/src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :discovery:shadowJar --no-daemon -x test

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine

RUN adduser -D -H -u 1000 ehz && \
    mkdir -p /app

COPY --from=builder /src/discovery/build/libs/*.jar /app/discovery.jar

USER ehz
WORKDIR /app

# Documentary only: this service runs with network_mode: host in compose, which
# ignores EXPOSE. Listed here so `docker image inspect` still tells the story.
EXPOSE 5353/udp 5370/udp

# No HEALTHCHECK — the discovery service doesn't expose an HTTP endpoint.
# Operator observes liveness via `docker ps` / logs.

ENTRYPOINT ["java","-jar","/app/discovery.jar"]
