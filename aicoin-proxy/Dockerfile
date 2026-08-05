## Multi-stage build for aicoin-proxy, per CONTRACT.md's "Docker /
## docker-compose" section: a JDK-26 build stage running the Gradle build,
## then just the application plugin's install output copied onto a JRE-26
## runtime base. Nothing here bakes in config values — every ProxyConfig
## field remains overridable via env vars at `docker run` time (see
## README.md's "Configuration" section for the full list).

FROM eclipse-temurin:26-jdk AS build
WORKDIR /workspace

# Cache dependency downloads separately from source changes.
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --version

COPY src ./src
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:26-jre AS runtime
WORKDIR /opt/aicoin-proxy

COPY --from=build /workspace/build/install/aicoin-proxy/ ./

EXPOSE 8080

ENTRYPOINT ["/opt/aicoin-proxy/bin/aicoin-proxy"]
