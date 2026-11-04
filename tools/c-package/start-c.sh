#!/bin/sh
set -eu
cd "$(dirname "$0")"
exec java -jar ./lianzhi-c.jar --spring.profiles.active=demo,standalone --server.address=127.0.0.1 --server.port="${SERVER_PORT:-8088}"
