# syntax=docker/dockerfile:1

# Multi-stage build: compile the GraalVM native image of the Open Green Button server, then
# package the binary onto a minimal glibc base for the Fly.io scale-to-zero deployment.
#
# The build context MUST be the repository root (not server/), because server/app's
# processResources pulls brand assets from ../branding. Deploy from the repo root with:
#
#   fly deploy --config server/fly.toml --dockerfile Dockerfile
#
# native-image needs a few GB of RAM to compile — use Fly's remote builder (the default) or a
# local Docker daemon with enough memory.

# ---- Build stage --------------------------------------------------------------------------
# GraalVM for JDK 21: matches the project's jvmToolchain(21) and sidesteps the JDK 25
# tracing-agent regression with Ktor CIO (oracle/graal#12650). native-image ships in this image,
# and JAVA_HOME already points at GraalVM, so the Gradle plugin picks it up with no toolchain
# download.
FROM ghcr.io/graalvm/native-image-community:21 AS build
# findutils: the native-image driver shells out to `xargs` to assemble its (very long) argument
#   list; the minimal Oracle Linux base doesn't ship it (build fails late with "xargs is not
#   available").
# zlib-static: lets -Popengb.native.static link zlib into the binary, so the result needs only
#   glibc and runs on the minimal distroless base below (no libz.so.1 in the runtime image).
RUN microdnf install -y findutils zlib-static && microdnf clean all
WORKDIR /src

# Warm the Gradle dependency cache in its own layer so source-only edits don't force a redownload.
COPY server/gradlew server/settings.gradle.kts server/build.gradle.kts ./server/
COPY server/gradle ./server/gradle
COPY server/app/build.gradle.kts ./server/app/
RUN cd server && ./gradlew --no-daemon -q :app:dependencies >/dev/null 2>&1 || true

# Full source tree (+ branding/ assets referenced by processResources), then compile the image.
COPY . .
ARG OPENGB_VERSION=0.0.0-SNAPSHOT
RUN cd server && ./gradlew --no-daemon :app:nativeCompile \
      -Popengb.version="${OPENGB_VERSION}" -Popengb.native.static

# ---- Runtime stage ------------------------------------------------------------------------
# distroless "cc" base for dynamically-linked compiled binaries: ships glibc, zlib (libz.so.1),
# libstdc++, ca-certificates, and tzdata, and runs as a non-root user. The binary links libc.so.6
# + libz.so.1 (verify with `ldd`); cc-debian12 guarantees both.
FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=build /src/server/app/build/native/nativeCompile/opengb-server /opengb-server

# Listen on all interfaces inside the Fly machine (mirrors fly.toml's [env]).
ENV OPENGB_HOST_PORT=0.0.0.0:8080
EXPOSE 8080
ENTRYPOINT ["/opengb-server"]
