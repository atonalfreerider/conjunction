#!/usr/bin/env bash
# Runs the rules and integration tests against the Firebase Emulator Suite (Auth, Firestore,
# Functions, Storage). Needs Java 21+ for the emulators.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi

# The emulator reads secrets from .secret.local; the fake moderator (.env.local) never uses it.
[[ -f .secret.local ]] || echo "TYPESAFE_API_KEY=unset" > .secret.local

npm run build
exec npx firebase emulators:exec --project conjunction-dev --only auth,firestore,functions,storage \
  "npx vitest run --project emulator ${*:-}"
