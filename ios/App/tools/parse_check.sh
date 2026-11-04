#!/usr/bin/env bash
# Parse-only check of every iOS app source with the Swift frontend (works on Linux, where the
# SwiftUI / UIKit / CoreBluetooth SDKs are missing): catches syntax errors, not type errors. The
# full type check happens on macOS CI (.github/workflows/ios-build.yml).
#
#   ios/App/tools/parse_check.sh [path/to/swift]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
swift="${1:-${SWIFT:-swift}}"
frontend="$(dirname "$(command -v "$swift" || echo "$swift")")/swift-frontend"
[ -x "$frontend" ] || frontend="$swift -frontend"
status=0
count=0
while IFS= read -r -d '' f; do
  count=$((count + 1))
  if ! $frontend -parse -swift-version 5 "$f"; then
    echo "PARSE ERROR: $f" >&2
    status=1
  fi
done < <(find "$here/../Conjunction" -name '*.swift' -print0)
echo "parsed $count files"
exit $status
