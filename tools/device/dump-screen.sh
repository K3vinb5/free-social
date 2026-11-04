#!/usr/bin/env bash
# Dump the current device screen: UI hierarchy XML + PNG screenshot.
#
#   tools/device/dump-screen.sh <label>
#
# Writes reverse/device-dumps/<HHMMSS>-<label>.xml and .png
# Every adb call is wrapped in `timeout 15`; uiautomator dump is retried up to 3x.
#
# Env overrides: ADB_SERIAL (default 56191JEBF26582), ADB_BIN (default adb),
#                OUT_DIR (default <repo>/reverse/device-dumps), ADB_TIMEOUT (default 15).

set -uo pipefail

SERIAL="${ADB_SERIAL:-56191JEBF26582}"
ADB_BIN="${ADB_BIN:-adb}"
ADB_TIMEOUT="${ADB_TIMEOUT:-15}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/reverse/device-dumps}"

REMOTE_XML="/sdcard/window_dump.xml"
REMOTE_PNG="/sdcard/dump-screen.png"

label="${1:-}"
if [ -z "$label" ]; then
  echo "usage: dump-screen.sh <label>" >&2
  exit 2
fi
case "$label" in
  *[[:space:]/]*) echo "error: label must not contain whitespace or slashes: '$label'" >&2; exit 2 ;;
esac

adb_cmd() { timeout "$ADB_TIMEOUT" "$ADB_BIN" -s "$SERIAL" "$@"; }

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  echo "error: adb not found (set ADB_BIN or add platform-tools to PATH)" >&2
  exit 3
fi

mkdir -p "$OUT_DIR" || { echo "error: cannot create $OUT_DIR" >&2; exit 3; }

stamp="$(date +%H%M%S)"
xml_out="$OUT_DIR/$stamp-$label.xml"
png_out="$OUT_DIR/$stamp-$label.png"

# --- UI hierarchy (bounded retries: "could not get idle state", "ERROR", timeouts) ---
dump_ok=0
last_err=""
for attempt in 1 2 3; do
  out="$(adb_cmd shell uiautomator dump "$REMOTE_XML" 2>&1)"
  rc=$?
  if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q "dumped to"; then
    dump_ok=1
    break
  fi
  last_err="${out:-exit $rc}"
  [ "$attempt" -lt 3 ] && sleep 2
done

dump_err=""
xml_ok=0
if [ "$dump_ok" -ne 1 ]; then
  dump_err="uiautomator dump failed after 3 attempts: $last_err"
elif ! adb_cmd pull "$REMOTE_XML" "$xml_out" >/dev/null 2>&1 || [ ! -s "$xml_out" ]; then
  dump_err="failed to pull $REMOTE_XML -> $xml_out"
else
  xml_ok=1
fi

# --- Screenshot ---
png_ok=0
last_err=""
for attempt in 1 2 3; do
  rm -f "$png_out"
  if adb_cmd shell screencap -p "$REMOTE_PNG" >/dev/null 2>&1 \
     && adb_cmd pull "$REMOTE_PNG" "$png_out" >/dev/null 2>&1 \
     && [ -s "$png_out" ]; then
    png_ok=1
    break
  fi
  last_err="screencap/pull failed (attempt $attempt)"
  [ "$attempt" -lt 3 ] && sleep 2
done

if [ "$png_ok" -ne 1 ]; then
  echo "error: screencap failed after 3 attempts: $last_err" >&2
  [ "$xml_ok" -eq 1 ] && echo "xml: $xml_out"
  [ -n "$dump_err" ] && echo "error: $dump_err" >&2
  exit 6
fi

adb_cmd shell rm -f "$REMOTE_XML" "$REMOTE_PNG" >/dev/null 2>&1

echo "png: $png_out"
if [ "$xml_ok" -eq 1 ]; then
  echo "xml: $xml_out"
else
  # Screen kept changing (e.g. a playing video): PNG is still written.
  echo "error: $dump_err (png saved, xml missing)" >&2
  exit 4
fi
