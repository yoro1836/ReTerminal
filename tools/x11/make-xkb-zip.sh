#!/usr/bin/env bash
# Build core/main/src/main/assets/xkb.zip (XKB keyboard data for the Termux:X11
# server the app starts; it reads it from $XKB_CONFIG_ROOT) from Debian's xkb-data.
set -euo pipefail
VER=2.42-1
SHA=196ff18533382f64e057ea49df2bb486bd4275a4cc0917361edb560b8756dada
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
curl -sSfL -o "$W/x.deb" "https://deb.debian.org/debian/pool/main/x/xkeyboard-config/xkb-data_${VER}_all.deb"
echo "$SHA  $W/x.deb" | sha256sum -c - >/dev/null
(cd "$W" && ar x x.deb && mkdir d && tar -xf data.tar.* -C d)
OUT=$ROOT/core/main/src/main/assets/xkb.zip
rm -f "$OUT"
# -X: no extra attributes; symlinks stored as copies; sorted, fixed times for a stable zip.
(cd "$W/d/usr/share/X11/xkb" && find . -exec touch -h -d @0 {} + && find . -type f -o -type l | LC_ALL=C sort | zip -qX -9 -@ "$OUT")
ls -la "$OUT"
