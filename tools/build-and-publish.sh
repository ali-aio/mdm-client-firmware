#!/usr/bin/env bash
# Build the firmware client inside an AOSP tree and publish it to the MDM as the hosted
# client APK — what a device behind on its client installs over itself (ClientUpdater).
#
#   tools/build-and-publish.sh -t ~/A15/QCOM6125_A15_T7_new -s https://mdm-stage.dev.aioapp.com -k "$ADMIN_API_KEY"
#   tools/build-and-publish.sh -t ~/A15/GMS --variant userdebug --build-only
#   tools/build-and-publish.sh -t ~/A15/QCOM6125_A15_T7_new -s ... -k ... --slot firmware-qcom
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

TREE=""; SERVER=""; KEY=""; VARIANT="user"; TF=""; BUILD_ONLY=0; SLOT=""; APKIN=""; CHANGELOG_IN=""
while [ $# -gt 0 ]; do
  case "$1" in
    -t|--tree) TREE="$2"; shift 2 ;;
    -s|--server) SERVER="${2%/}"; shift 2 ;;
    -k|--key) KEY="$2"; shift 2 ;;
    --variant) VARIANT="$2"; shift 2 ;;
    --slot) SLOT="$2"; shift 2 ;;
    --apk) APKIN="$2"; shift 2 ;;
    --changelog) CHANGELOG_IN="$2"; shift 2 ;;
    --from-target-files) TF="$2"; shift 2 ;;
    --build-only) BUILD_ONLY=1; shift ;;
    -h|--help) sed -n 2,17p "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
# -t is what --apk infers the slot from; with an explicit --slot and an existing APK the
# tree is not needed at all.
if [ -z "$APKIN" ] || [ -z "$SLOT" ]; then
  [ -n "$TREE" ] || { echo "need -t TREE (an AOSP tree under ~/A15), or --apk with --slot" >&2; exit 2; }
  [ -d "$TREE/qssi" ] || { echo "$TREE does not look like one of our trees (no qssi/)" >&2; exit 2; }
fi
case "$VARIANT" in user|userdebug) ;; *) echo "variant must be user or userdebug" >&2; exit 2 ;; esac
if [ "$BUILD_ONLY" -eq 0 ]; then
  [ -n "$SERVER" ] && [ -n "$KEY" ] || { echo "need -s SERVER and -k ADMIN_API_KEY, or --build-only" >&2; exit 2; }
fi

# apksigner and aapt live in the SDK build-tools and are not normally on PATH. Without
# them the signer checks below would silently skip — which is the one thing they must
# not do, since a wrong key is only discovered when every device refuses the install.
if ! command -v apksigner >/dev/null || ! command -v aapt >/dev/null; then
  for BT in "${ANDROID_HOME:-$HOME/android_sdk}"/build-tools/*; do
    [ -x "$BT/apksigner" ] && PATH="$BT:$PATH"
  done
fi

# The client is a qssi (system) module, so it builds in the qssi lunch target — the same
# one build-wifionly.sh uses for the system half of the image.
if [ -n "$APKIN" ]; then
  echo "→ skipping the build: publishing the APK passed to --apk"
elif [ -z "$TF" ]; then
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
if [ -n "$APKIN" ]; then
  [ -f "$APKIN" ] || { echo "no APK at $APKIN" >&2; exit 1; }
  OUT="$APKIN"
elif [ -z "$TF" ]; then
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
if [ -z "$TF" ] && [ -n "$TREE" ] && command -v apksigner >/dev/null && [ -f "$REF_TF" ]; then
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

# One slot per signing key, not per variant: a tree's `user` and `userdebug` images are
# signed with the same platform certificate, so they share a slot and a device is only
# ever offered the build whose key it already trusts. (The old release-keys/test-keys
# split is gone — those slots no longer exist on the server and a publish to one is
# refused.)
#
# The certificate each slot's devices trust, from AgentAPKSlots in the server. Checked
# below, because the target-files comparison above cannot catch a wrong *slot*: it only
# proves the APK matches its own tree, and a GMS build published to firmware-qcom is
# refused by every device at install.
QCOM_CERT="01d88e1a89168532d7a2701710367b19a21b287df3f7fe8cbfe2f2e4d7a77c7a"
GMS_CERT="c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"

if [ -z "$SLOT" ]; then
  # Infer from the tree's name, which is how they are laid out under ~/A15.
  case "$(basename "$TREE" | tr '[:upper:]' '[:lower:]')" in
    *gms*) SLOT="firmware-gms"; WANT_CERT="$GMS_CERT" ;;
    *)     SLOT="firmware-qcom"; WANT_CERT="$QCOM_CERT" ;;
  esac
else
  case "$SLOT" in
    firmware-qcom) WANT_CERT="$QCOM_CERT" ;;
    firmware-gms)  WANT_CERT="$GMS_CERT" ;;
    dpc|lite-demo|menu-board) WANT_CERT="" ;;   # not platform-signed; no cert to assert
    *) echo "unknown slot: $SLOT" >&2; exit 2 ;;
  esac
fi

if [ -n "$WANT_CERT" ] && command -v apksigner >/dev/null; then
  GOT_CERT=$(signer "$APK")
  if [ -n "$GOT_CERT" ] && [ "$GOT_CERT" != "$WANT_CERT" ]; then
    echo "refusing to publish: $APK is signed with $GOT_CERT," >&2
    echo "but $SLOT expects $WANT_CERT. Devices in that slot would reject it." >&2
    echo "Build the client in the right tree, or pass --slot to name the one you mean." >&2
    exit 1
  fi
  [ -n "$GOT_CERT" ] && echo "→ signer matches $SLOT ($GOT_CERT)"
fi

echo "→ publishing to $SERVER (slot $SLOT)"
# The changelog is what the Clients page shows against the release, so it has to describe
# THIS build. It defaults to the last commit's subject — which is only right when the
# change being published is committed, and is otherwise the previous release's message.
# Pass --changelog when the tree carries work that is not committed here.
CHANGELOG_TEXT="${CHANGELOG_IN:-$(git -C "$(dirname "$0")/.." log -1 --pretty=%s 2>/dev/null || true)}"
if [ -z "$CHANGELOG_IN" ]; then
  echo "→ changelog from the last commit: \"$CHANGELOG_TEXT\""
  echo "  (uncommitted work in the tree will not appear here — pass --changelog to name it)"
fi
CHANGELOG=$(printf '%s' "$CHANGELOG_TEXT" | python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read().strip()))' || true)
curl -fsS -X POST "$SERVER/api/v1/agent-apk?slot=$SLOT&name=mdm-client.apk&changelog=$CHANGELOG" \
  -H "X-API-Key: $KEY" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK"
echo
echo "→ devices on an older client now show \"Update agent\" on their device page."
