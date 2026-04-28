#!/bin/sh
# Ensure the JVM can write to /data, which in the common deployment is a
# bind-mount owned by whoever created ./data on the host.
#
# Two runtime modes are both safe:
#
#   * Non-root Docker: the container starts as root, but root-in-container is
#     mapped to an unprivileged host user (rootless Docker / Podman), so
#     chowning /data to `ehz` would change host-side ownership in a way the
#     operator doesn't expect. We instead test whether `ehz` can already write,
#     and if not, stay as root (which, under rootless, is still the host user).
#
#   * Regular Docker: the container starts as root for real. Here we want to
#     drop privileges. If /data is writable by `ehz` we su-exec. Otherwise we
#     chown /data (top level only, so existing subdirs like pgdata keep their
#     postgres-owned permissions) and then drop.
set -eu

if [ "$(id -u)" = "0" ]; then
    if su-exec ehz:ehz test -w /data; then
        exec su-exec ehz:ehz "$@"
    fi

    # Detect rootless / remapped-root by checking whether /proc/self/uid_map
    # has anything other than the identity line "0 0 ...". A non-identity map
    # means our root is a userns-mapped non-root host user — stay as root.
    if [ -r /proc/self/uid_map ] && \
       [ "$(awk '{print $1, $2}' /proc/self/uid_map | head -1)" != "0 0" ]; then
        exec "$@"
    fi

    # Real root in a regular Docker daemon. Fix ownership of /data itself
    # (not recursive; subdirs like pgdata belong to other services) and drop.
    chown ehz:ehz /data 2>/dev/null || true
    exec su-exec ehz:ehz "$@"
fi

exec "$@"
