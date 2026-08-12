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

# Explicit memory budget, because the default is derived from the host and the host is small.
# With no -Xmx the JVM takes ~25% of visible RAM as max heap; on the 512MB production Lightsail
# instance that is ~128MB, chosen without knowing that Redis wants a share of the same 512MB and
# that Netty's off-heap direct buffers are not counted in it at all. Under load the two grew into
# each other with no stated ceiling for either.
#
# The numbers: 160m heap covers steady-state request handling with room for the pricing parse on
# the providers that still need it. MaxDirectMemorySize is what actually bounds the response
# aggregation — HttpObjectAggregator buffers each response whole, so this is the real cap on
# concurrent large relays, and leaving it unset lets it default to roughly the heap size again,
# silently doubling the process footprint. 160+64 plus JVM overhead leaves Redis its room.
#
# ExitOnOutOfMemoryError so exhaustion terminates the process instead of entering the far worse
# failure it produced before: a JVM alive but spending all its time in GC, which from the outside
# looks exactly like the slow-then-timeout behaviour this whole change set is about. Paired with
# `restart: unless-stopped` in docker-compose.yml, that turns a memory blowout into a few seconds
# of downtime rather than an indefinite brownout.
ENV JAVA_OPTS="-Xmx160m -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

ENTRYPOINT ["/opt/aicoin-proxy/bin/aicoin-proxy"]
