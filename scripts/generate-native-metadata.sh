#!/usr/bin/env bash
#
# Regenerate the GraalVM native-image reachability metadata committed under
#   server/app/src/main/resources/META-INF/native-image/org.opengb/opengb-server/
#
# Why a script (and not just `-Pagent test`): the test suite uses Ktor's in-memory test engine,
# which never touches the CIO network selector, Bootable's boot()/log4j2 init, or Hoplite's
# config load. So we capture metadata from TWO sources, each into its OWN subdirectory under
# META-INF/native-image/ (native-image unions all subdirs at build time — this avoids the agent's
# lossy "merge into an existing bare entry" behaviour that drops log4j2 factory methods):
#
#   from-tests/ ← `-Pagent test` + metadataCopy: crypto, kotlinx.serialization, the OAuth flow,
#                 route handlers. (No real sockets — tracing socket teardown is non-deterministic,
#                 which would make the CI metadata-drift guard flaky.)
#   from-app/   ← a real boot() traced with the agent: the CIO network selector, log4j2 JSON config
#                 (plugin factory reflection), Hoplite config decoding, Bootable startup. This is
#                 what fixes the `NoSuchFieldException: readHandlerReference`, `No factory method
#                 ... AppenderRef`, and `Only instances of KClass are supported` failures.
#
# Requirements: a GraalVM for JDK 21 (so `native-image-agent` is present). mise provides it via
# GRAALVM_HOME (see mise.toml) — so inside this repo, with mise active, just run the script. To
# override, point GRAALVM_HOME or JAVA_HOME at a GraalVM 21 install yourself.
#
# Commit the regenerated META-INF/native-image files afterwards.
set -euo pipefail

cd "$(dirname "$0")/../server"

GVM="${GRAALVM_HOME:-${JAVA_HOME:-}}"
if [[ -z "$GVM" || ! -x "$GVM/bin/java" ]]; then
  echo "ERROR: no GraalVM found. GRAALVM_HOME should be set by mise (run from the repo with mise" >&2
  echo "  active, after 'mise install'), or point GRAALVM_HOME/JAVA_HOME at a GraalVM for JDK 21." >&2
  exit 1
fi
if [[ ! -f "$GVM/lib/libnative-image-agent.so" ]]; then
  echo "ERROR: $GVM is not a GraalVM (no native-image-agent). Use a GraalVM for JDK 21." >&2
  exit 1
fi

META_ROOT="$PWD/app/src/main/resources/META-INF/native-image/org.opengb"
META_APP="$META_ROOT/from-app"
PORT="${OPENGB_METADATA_PORT:-18099}"

echo "==> Using GraalVM:  $GVM"
echo "==> Metadata root:  $META_ROOT"

# A clean PATH/HOME so Gradle and the JVM still work, but stripped of the environment-module
# (Lmod) BASH_FUNC_* exports whose values contain '${...}' — Hoplite's config load chokes on
# those. (They won't exist on the Fly machine; this only matters when generating metadata in a
# polluted dev shell.)
run_clean() {
  env -i HOME="$HOME" LANG="${LANG:-C.UTF-8}" \
    JAVA_HOME="$GVM" GRAALVM_HOME="$GVM" PATH="$GVM/bin:/usr/bin:/bin" \
    "$@"
}

echo "==> [1/3] Tracing the test suite under the native-image agent"
run_clean ./gradlew --no-daemon -Pagent --rerun-tasks :app:test
run_clean ./gradlew --no-daemon :app:metadataCopy

echo "==> [2/3] Building the install distribution (runtime classpath)"
run_clean ./gradlew --no-daemon :app:installDist
LIBDIR="$(echo "$PWD"/app/build/install/*/lib)"

echo "==> [3/3] Tracing the real application boot under the agent (-> from-app/)"
# Fresh output dir (not a merge) so log4j2's factory-method metadata is written in full.
rm -rf "$META_APP"
mkdir -p "$META_APP"
# The agent writes its complete config only on a CLEAN JVM exit. Bootable's stop-signal handler
# turns SIGTERM into a normal exit (code 0), which runs the agent's shutdown flush. We launch java
# DIRECTLY (not via the run_clean function) so $APP_PID is the JVM and our SIGTERM reaches it —
# backgrounding a function would leave $APP_PID pointing at a subshell while java runs on as an
# orphan holding the port. Don't use config-write-period-secs: it dumps into temp dirs that are
# only consolidated on clean exit anyway. (The signal handler's own reflection isn't captured here
# — it only runs when a signal is handled — which is why StopSignalHandler is registered by hand
# in manual/reflect-config.json.)
if curl -fsS -o /dev/null -m1 "http://127.0.0.1:$PORT/health" 2>/dev/null; then
  echo "ERROR: port $PORT is already in use — free it (or set OPENGB_METADATA_PORT) and retry." >&2
  exit 1
fi
env -i HOME="$HOME" LANG="${LANG:-C.UTF-8}" \
  JAVA_HOME="$GVM" GRAALVM_HOME="$GVM" PATH="$GVM/bin:/usr/bin:/bin" \
  OPENGB_HOST_PORT="127.0.0.1:$PORT" OPENGB_PUBLIC_BASE_URL="http://127.0.0.1:$PORT" \
  "$GVM/bin/java" "-agentlib:native-image-agent=config-output-dir=$META_APP" \
  -cp "$LIBDIR/*" org.opengb.AppKt >/tmp/opengb-metadata-run.log 2>&1 &
APP_PID=$!

# Wait for the listener, then exercise the routes so their handlers + logging are traced.
for _ in $(seq 1 400); do
  curl -fsS -o /dev/null "http://127.0.0.1:$PORT/health" 2>/dev/null && break
  sleep 0.05
done
for path in /health / /utilities /connect/unknown/callback; do
  curl -fsS -o /dev/null "http://127.0.0.1:$PORT$path" 2>/dev/null || true
done
sleep 1  # let the agent observe everything before shutdown

kill -TERM "$APP_PID" 2>/dev/null || true
# Give the JVM up to ~15s to shut down cleanly — that clean exit is what flushes the agent config.
for _ in $(seq 1 150); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.1; done
# Backstop: never leave a process holding the port for the next run.
kill -9 "$APP_PID" 2>/dev/null || true
if [[ ! -s "$META_APP/reflect-config.json" ]] || ! grep -q '"name"' "$META_APP/reflect-config.json"; then
  echo "ERROR: agent produced no metadata in $META_APP — did the app boot? See /tmp/opengb-metadata-run.log" >&2
  exit 1
fi

echo "==> Done. Review & commit the changes under:"
echo "    server/app/src/main/resources/META-INF/native-image/"
