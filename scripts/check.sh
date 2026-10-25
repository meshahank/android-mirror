#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
test -f "$root/shared/schemas/protocol-v1.json"
node -e "JSON.parse(require('fs').readFileSync('$root/shared/schemas/protocol-v1.json')); JSON.parse(require('fs').readFileSync('$root/shared/fixtures/pair-request.json')); console.log('protocol fixture: valid JSON')"
if command -v npm >/dev/null && [ -d "$root/desktop/node_modules" ]; then (cd "$root/desktop" && npm run build); else echo 'desktop build skipped: install Node dependencies first'; fi
if [ -x "$root/android/gradlew" ]; then (cd "$root/android" && ./gradlew testDebugUnitTest); else echo 'Android build skipped: Gradle wrapper JAR/SDK unavailable in this environment'; fi
