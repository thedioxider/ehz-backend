#!/bin/sh
# Ensure the JVM can write to /state, which is a bind-mount owned by whoever
# created ./data/discovery on the host (typically root under regular Docker).
# Mirrors app/docker-entrypoint.sh — see that file for the uid_map rationale.
set -eu

if [ "$(id -u)" = "0" ]; then
    if su-exec ehz:ehz test -w /state 2>/dev/null; then
        exec su-exec ehz:ehz "$@"
    fi

    if [ -r /proc/self/uid_map ] && \
       [ "$(awk '{print $1, $2}' /proc/self/uid_map | head -1)" != "0 0" ]; then
        exec "$@"
    fi

    chown ehz:ehz /state 2>/dev/null || true
    exec su-exec ehz:ehz "$@"
fi

exec "$@"
