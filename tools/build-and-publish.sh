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
# Signing — two different keys, do not confuse them:
#   * The OTA *payload* key (PAX releasekey for user, Rockchip testkey for userdebug)
#     signs the update package; the device checks it against otacerts.zip.
#   * The *app* key signs mdm-client.apk. This tree signs apps with the Rockchip
#     platform key in both variants, and does not re-sign on the way into target-files,
#     so the out/ APK carries the same certificate as the one in the shipped image.
#   Rather than trust either claim, the script compares the built APK's signer against
#   the copy in the tree's target-files and refuses to publish on a mismatch — a wrong
#   key means every device rejects the update at install.
#   --from-target-files <zip> publishes that copy directly and skips the build.
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
if [ -z "$TF" ]; then
  echo "→ building mdm-client in $TREE (qssi-$VARIANT)"
  (
    cd "$TREE/qssi"
    # envsetup and lunch are interactive-shell shaped: unset -u around them.
    set +u
    source build/envsetup.sh
    lunch "qssi-$VARIANT"
    m mdm-client
  )
else
  echo "→ skipping the build: taking the APK from target-files"
fi

OUT=""
if [ -z "$TF" ]; then
  OUT="$TREE/qssi/out/target/product/qssi/system/priv-app/mdm-client/mdm-client.apk"
  [ -f "$OUT" ] || OUT="$(find "$TREE/qssi/out" -name 'mdm-client.apk' -path '*priv-app*' -print -quit 2>/dev/null || true)"
  [ -n "$OUT" ] && [ -f "$OUT" ] || { echo "built, but no mdm-client.apk found under out/" >&2; exit 1; }
fi

APK="$OUT"
if [ -n "$TF" ]; then
  [ -f "$TF" ] || { echo "no target-files at $TF" >&2; exit 1; }
  echo "→ taking the release-signed APK out of $(basename "$TF")"
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  # The path inside target-files mirrors the image layout.
  unzip -o -q "$TF" 'SYSTEM/priv-app/mdm-client/mdm-client.apk' -d "$TMP" \
    || { echo "mdm-client.apk is not in that target-files" >&2; exit 1; }
  APK="$TMP/SYSTEM/priv-app/mdm-client/mdm-client.apk"
fi

# Prove the built APK is signed with the key the shipped image uses, by comparing it
# with the copy inside the tree's own target-files. A mismatch is not publishable: the
# device refuses an update signed with anything but the certificate it already trusts.
signer() { apksigner verify --print-certs "$1" 2>/dev/null | awk '/SHA-256 digest/ {print $NF; exit}'; }
REF_TF="${TF:-$TREE/target/out/dist/merged-qssi_trinket-target_files.zip}"
if [ -z "$TF" ] && command -v apksigner >/dev/null && [ -f "$REF_TF" ]; then
  REFDIR="$(mktemp -d)"; trap 'rm -rf "$REFDIR"' EXIT
  if unzip -o -q "$REF_TF" 'SYSTEM/priv-app/mdm-client/mdm-client.apk' -d "$REFDIR" 2>/dev/null; then
    BUILT=$(signer "$APK")
    SHIPPED=$(signer "$REFDIR/SYSTEM/priv-app/mdm-client/mdm-client.apk")
    if [ -n "$BUILT" ] && [ -n "$SHIPPED" ] && [ "$BUILT" != "$SHIPPED" ]; then
      echo "refusing to publish: the built APK is signed with $BUILT," >&2
      echo "the image ships $SHIPPED. Every device would reject this at install." >&2
      exit 1
    fi
    echo "→ signer matches the shipped image ($BUILT)"
  fi
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
