#!/bin/sh
set -eu
: "${LIANZHI_REDIS_PASSWORD:?Set a private Redis password in the local env file}"
umask 077
# Redis accepts a SHA256 ACL password; never put the plaintext password in process arguments.
password_hash="$(printf '%s' "$LIANZHI_REDIS_PASSWORD" | sha256sum | cut -d ' ' -f 1)"
printf 'user default on #%s ~* &* +@all\n' "$password_hash" > /tmp/lianzhi-users.acl
cat > /tmp/lianzhi-redis.conf <<'CONFIG'
bind 0.0.0.0
protected-mode yes
port 6379
dir /data
save ""
appendonly yes
appendfsync always
maxmemory 256mb
maxmemory-policy noeviction
aclfile /tmp/lianzhi-users.acl
CONFIG
exec redis-server /tmp/lianzhi-redis.conf
