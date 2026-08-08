## Multi-stage build for aicoin-proxy, per CONTRACT.md's "Docker /
## docker-compose" section: a GraalVM build stage running the Gradle build,
## then just the application plugin's install output copied onto a GraalVM
## runtime base (GraalVM chosen for a smaller heap footprint on the cheap
## single-vCPU production Lightsail host — see CONTRACT.md's "Production
## hosting decision"). Nothing here bakes in config values — every
## ProxyConfig field remains overridable via env vars at `docker run` time
## (see README.md's "Configuration" section for the full list).
##
## NOTE on the version pin: CONTRACT.md calls for "GraalVM JDK 26", but as of
## this writing (Aug 2026) GraalVM has not published a JDK 26 build at all —
## verified live against ghcr.io/graalvm/graalvm-community's tag list (no
## "26" tag; latest is the 25.x line, e.g. 25.0.2/25.0.3) and against
## graalvm.org's release calendar, which shows no JDK 26 entry even in
## planned future releases. GraalVM Community images also don't ship a
## separate JDK/JRE split the way eclipse-temurin does, so both stages below
## use the one full "graalvm-community" image. This pins to the latest real,
## published GraalVM major line (25) instead of guessing a tag that doesn't
## exist; it's in lockstep with build.gradle's toolchain and both
## .java-version files (all three must move together — running JDK-26
## bytecode on a JDK 25 JVM fails with UnsupportedClassVersionError at
## startup). TODO: bump the tag below to "26" (and build.gradle's toolchain,
## and both .java-version files) the moment GraalVM publishes a JDK 26 build.

FROM ghcr.io/graalvm/graalvm-community:25 AS build
WORKDIR /workspace

# Cache dependency downloads separately from source changes.
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --version

COPY src ./src
RUN ./gradlew installDist --no-daemon

FROM ghcr.io/graalvm/graalvm-community:25 AS runtime
WORKDIR /opt/aicoin-proxy

COPY --from=build /workspace/build/install/aicoin-proxy/ ./

EXPOSE 8080

ENTRYPOINT ["/opt/aicoin-proxy/bin/aicoin-proxy"]
