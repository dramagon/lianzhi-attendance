#!/bin/sh
set -eu
cd "$(dirname "$0")"
case "${1:-}" in
  ''|--check) ;;
  *) echo 'Usage: sh start-demo.sh [--check]' >&2; exit 1 ;;
esac
for demo_file in app.jar manifest.json checksums.sha256 start-demo.cmd start-demo.ps1 check-demo.cmd README.txt; do
  if [ ! -f "$demo_file" ]; then echo "Incomplete package: $demo_file is missing. Extract the entire ZIP." >&2; exit 1; fi
done
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c checksums.sha256 || { echo 'Package checksum mismatch. Extract a clean copy; keep demo-data.' >&2; exit 1; }
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 -c checksums.sha256 || { echo 'Package checksum mismatch. Extract a clean copy; keep demo-data.' >&2; exit 1; }
else
  echo 'SHA-256 checker unavailable. Install sha256sum or shasum.' >&2; exit 1
fi
demo_java=java
if [ -x runtime/bin/java ]; then
  demo_java=./runtime/bin/java
elif [ -n "${JAVA_HOME:-}" ]; then
  demo_java="$JAVA_HOME/bin/java"
fi
if ! demo_version=$("$demo_java" -version 2>&1); then
  echo 'Cannot run Java. Install Java 17+, or set JAVA_HOME to a working installation.' >&2; exit 1
fi
demo_major=$(printf '%s\n' "$demo_version" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
if [ "$demo_major" = 1 ]; then
  demo_major=$(printf '%s\n' "$demo_version" | sed -n 's/.*version "1\.\([0-9][0-9]*\).*/\1/p' | head -n 1)
fi
case "$demo_major" in ''|*[!0-9]*) echo 'Cannot determine Java version. Java 17+ is required.' >&2; exit 1 ;; esac
if [ "$demo_major" -lt 17 ]; then echo "Java $demo_major is too old. Java 17+ is required." >&2; exit 1; fi
# Probe write access without touching the database or leaving a probe file behind.
demo_probe=$(mktemp .demo-check-XXXXXX) || { echo 'Extract to a writable directory.' >&2; exit 1; }
rm -f "$demo_probe"
echo "Preflight passed: package, Java $demo_major and writable directory."
if [ "${1:-}" = --check ]; then exit 0; fi
mkdir -p demo-data
echo 'Open http://127.0.0.1:18080/login?redirect=/attendance/presentation'
echo 'Choose the demo administrator. Keep this terminal open; Ctrl+C stops this process.'
echo 'Synthetic employees and simulated location; no real face SDK.'
exec "$demo_java" -jar app.jar --spring.profiles.active=demo,classroom --lianzhi.demo=true \
  --server.address=127.0.0.1 --server.port=18080 --server.servlet.session.cookie.secure=false \
  '--spring.datasource.url=jdbc:h2:file:./demo-data/lianzhi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE' \
  --spring.datasource.username=sa --spring.datasource.password= --lianzhi.avatar-directory=./demo-data/avatars \
  --lianzhi.face.enabled=false --lianzhi.face.required-for-punch=false \
  --LIANZHI_KNOWLEDGE_ENABLED=true --LIANZHI_AI_BASE_URL= --LIANZHI_AI_MODEL= \
  --LIANZHI_EMBEDDING_BASE_URL= --LIANZHI_EMBEDDING_MODEL= --LIANZHI_OCR_URL= --LIANZHI_TRANSCRIPTION_URL=
