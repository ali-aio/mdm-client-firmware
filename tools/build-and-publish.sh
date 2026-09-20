#!/usr/bin/env bash
# Build the firmware client inside an AOSP tree and publish it to the MDM as the hosted
# client APK — what a device behind on its client installs over itself (ClientUpdater).
#
#   tools/build-and-publish.sh -t ~/A15/QCOM6125_A15_T7_new -s https://mdm-stage.dev.aioapp.com -k "$ADMIN_API_KEY"
#   tools/build-and-publish.sh -t ~/A15/GMS --variant userdebug --build-only
#
# This cannot live in GitHub Actions: the APK has to be signed with the platform key of
# the image it will run on, and those keys live in the build tree, not in a runner.
#
# Signing, and why --from-target-files matters:
#   A `user` build's out/ APK is signed with the tree's dev key, not the release key —
#   sign_target_files_apks re-signs everything on the way into the signed target-files.
#   Publishing the out/ copy to devices running a release-keys image gives every one of
#   them "signed with a different key" at install. For a user build, pass
#   --from-target-files <signed target_files.zip> and the APK is taken from there.
set -euo pipefail

TREE=""; SERVER=""; KEY=""; VARIANT="user"; TF=""; BUILD_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    -t|--tree) TREE="$2"; shift 2 ;;
    -s|--server) SERVER="${2%/}"; shift 2 ;;
    -k|--key) KEY="$2"; shift 2 ;;
    --variant) VARIANT="$2"; shift 2 ;;
    --from-target-files) TF="$2"; shift 2 ;;
    --build-only) BUILD_ONLY=1; shift ;;
    -h|--help) sed -n 2,17p "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$TREE" ] || { echo "need -t TREE (an AOSP tree under ~/A15)" >&2; exit 2; }
[ -d "$TREE/qssi" ] || { echo "$TREE does not look like one of our trees (no qssi/)" >&2; exit 2; }
case "$VARIANT" in user|userdebug) ;; *) echo "variant must be user or userdebug" >&2; exit 2 ;; esac
if [ "$BUILD_ONLY" -eq 0 ]; then
  [ -n "$SERVER" ] && [ -n "$KEY" ] || { echo "need -s SERVER and -k ADMIN_API_KEY, or --build-only" >&2; exit 2; }
fi

# The client is a qssi (system) module, so it builds in the qssi lunch target — the same
# one build-wifionly.sh uses for the system half of the image.
echo "→ building mdm-client in $TREE (qssi-$VARIANT)"
(
  cd "$TREE/qssi"
  # envsetup and lunch are interactive-shell shaped: unset -u around them.
  set +u
  source build/envsetup.sh
  lunch "qssi-$VARIANT"
  m mdm-client
)

OUT="$TREE/qssi/out/target/product/qssi/system/priv-app/mdm-client/mdm-client.apk"
[ -f "$OUT" ] || OUT="$(find "$TREE/qssi/out" -name 'mdm-client.apk' -path '*priv-app*' -print -quit 2>/dev/null || true)"
[ -n "$OUT" ] && [ -f "$OUT" ] || { echo "built, but no mdm-client.apk found under out/" >&2; exit 1; }

APK="$OUT"
if [ -n "$TF" ]; then
  [ -f "$TF" ] || { echo "no target-files at $TF" >&2; exit 1; }
  echo "→ taking the release-signed APK out of $(basename "$TF")"
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  # The path inside target-files mirrors the image layout.
  unzip -o -q "$TF" 'SYSTEM/priv-app/mdm-client/mdm-client.apk' -d "$TMP" \
    || { echo "mdm-client.apk is not in that target-files" >&2; exit 1; }
  APK="$TMP/SYSTEM/priv-app/mdm-client/mdm-client.apk"
elif [ "$VARIANT" = "user" ] && [ "$BUILD_ONLY" -eq 0 ]; then
  echo "refusing to publish: a user build's out/ APK carries the tree's dev key, not the" >&2
  echo "release key the devices trust. Re-run with --from-target-files <signed .zip>." >&2
  exit 1
fi

echo "→ $(stat -c %s "$APK") bytes"
if command -v aapt >/dev/null; then aapt dump badging "$APK" 2>/dev/null | head -1; fi
if command -v apksigner >/dev/null; then apksigner verify --print-certs "$APK" 2>/dev/null | head -2; fi

if [ "$BUILD_ONLY" -eq 1 ]; then
  echo "→ built only; APK at $APK"
  exit 0
fi

# release-keys images take the release slot, userdebug images the test slot — a device
# is only ever offered the build signed with the key it already trusts.
SLOT="firmware-release-keys"
[ "$VARIANT" = "userdebug" ] && SLOT="firmware-test-keys"

echo "→ publishing to $SERVER (slot $SLOT)"
CHANGELOG=$(git -C "$(dirname "$0")/.." log -1 --pretty=%s 2>/dev/null | python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read().strip()))' || true)
curl -fsS -X POST "$SERVER/api/v1/agent-apk?slot=$SLOT&name=mdm-client.apk&changelog=$CHANGELOG" \
  -H "X-API-Key: $KEY" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK"
echo
echo "→ devices on an older client now show \"Update agent\" on their device page."
