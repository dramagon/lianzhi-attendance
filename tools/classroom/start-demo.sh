#!/bin/sh
set -eu
cd "$(dirname "$0")"
demo_java=java
if [ -n "${JAVA_HOME:-}" ]; then demo_java="$JAVA_HOME/bin/java"; fi
mkdir -p demo-data
echo 'Open http://127.0.0.1:18080/login?redirect=/attendance/presentation'
echo 'Choose the demo administrator. Keep this terminal open; Ctrl+C stops this process.'
echo 'Synthetic employees and simulated location; no real face SDK.'
exec "$demo_java" -jar app.jar --spring.profiles.active=demo,classroom --lianzhi.demo=true \
  --server.address=127.0.0.1 --server.port=18080 --server.servlet.session.cookie.secure=false \
  '--spring.datasource.url=jdbc:h2:file:./demo-data/lianzhi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;OPTIMIZE_REUSE_RESULTS=FALSE' \
  --spring.datasource.username=sa --spring.datasource.password= --lianzhi.avatar-directory=./demo-data/avatars \
  --lianzhi.face.enabled=false --lianzhi.face.required-for-punch=false \
  --LIANZHI_KNOWLEDGE_ENABLED=true --LIANZHI_AI_BASE_URL= --LIANZHI_AI_MODEL= \
  --LIANZHI_EMBEDDING_BASE_URL= --LIANZHI_EMBEDDING_MODEL= --LIANZHI_OCR_URL= --LIANZHI_TRANSCRIPTION_URL=
