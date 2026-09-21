#!/usr/bin/env bash
# Publish the firmware client to the MDM, in one command with no arguments.
#
#   tools/publish-client.sh                 # build QCOM, publish to stage
#   tools/publish-client.sh --apk <path>    # publish an APK already built (skips ~20 min)
#   tools/publish-client.sh --tree ~/A15/GMS
#   tools/publish-client.sh --live          # the restaurant fleet — asks first
#   tools/publish-client.sh --dry-run       # show what it would do, publish nothing
#
# This is a thin wrapper over build-and-publish.sh, which does the real work (build,
# verify the signer, publish); read `tools/BUILD_AND_PUBLISH.md` for the why.
#
# ── The admin key is deliberately NOT in this file ────────────────────────────
# This repository is PUBLIC. A key committed here is published to the internet and
# stays in git history after you delete it — rotating is the only undo. So the key
# lives outside the repo and is read from, in order:
#
#   1. $MDM_ADMIN_KEY
#   2. ~/.config/aio-mdm/publish.key   (mode 600)
#
# Set it up once:
#   install -d -m700 ~/.config/aio-mdm
#   printf '%s\n' '<key>' > ~/.config/aio-mdm/publish.key && chmod 600 ~/.config/aio-mdm/publish.key
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_TREE="$HOME/A15/QCOM6125_A15_T7_new"
STAGE_URL="https://mdm-stage.dev.aioapp.com"
LIVE_URL="https://mdm.dev.aioapp.com"

TREE="$DEFAULT_TREE"; SERVER="$STAGE_URL"; APK=""; LIVE=0; DRY=0
EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --live) LIVE=1; SERVER="$LIVE_URL"; shift ;;
    --stage) SERVER="$STAGE_URL"; shift ;;
    --tree) TREE="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --apk-latest) APK="$TREE/qssi/out/target/product/qssi/system/priv-app/mdm-client/mdm-client.apk"; shift ;;
    --dry-run) DRY=1; shift ;;
    # anything else (--slot, --variant, --build-only, ...) goes straight through
    *) EXTRA+=("$1"); shift ;;
  esac
done

# ── the key ──────────────────────────────────────────────────────────────────
KEY="${MDM_ADMIN_KEY:-}"
if [ -z "$KEY" ] && [ -f "$HOME/.config/aio-mdm/publish.key" ]; then
  KEY="$(head -n1 "$HOME/.config/aio-mdm/publish.key" | tr -d '[:space:]')"
fi
if [ -z "$KEY" ]; then
  cat >&2 <<'MSG'
No admin key found. Store it once, outside this repo:

  install -d -m700 ~/.config/aio-mdm
  printf '%s\n' '<key>' > ~/.config/aio-mdm/publish.key && chmod 600 ~/.config/aio-mdm/publish.key

or pass it in the environment: MDM_ADMIN_KEY=<key> tools/publish-client.sh
MSG
  exit 2
fi
# Never echo the key — report only enough to recognise which one was picked up.
echo "→ key: ${KEY:0:8}… (${#KEY} chars) from ${MDM_ADMIN_KEY:+MDM_ADMIN_KEY}${MDM_ADMIN_KEY:-~/.config/aio-mdm/publish.key}"

# ── live is a different thing from stage ─────────────────────────────────────
if [ "$LIVE" -eq 1 ] && [ "$DRY" -eq 0 ]; then
  echo
  echo "About to publish to LIVE — $LIVE_URL (the restaurant fleet)."
  echo "Every device on an older client will be offered this build."
  printf 'Type "live" to continue: '
  read -r ok
  [ "$ok" = "live" ] || { echo "aborted"; exit 1; }
fi

# ── the tree has its own copy of the client ──────────────────────────────────
# build-and-publish.sh compiles <tree>/qssi/packages/apps/mdm-client, NOT this repo.
# They are separate directories kept in step by hand, so a change committed here does
# nothing until it is copied across — and the failure is silent the loudest way
# possible: the build succeeds, and produces the previous APK. This repo is the
# git-tracked source of truth, so it wins. --no-sync builds whatever the tree has.
SYNC=1
for a in "${EXTRA[@]:-}"; do [ "$a" = "--no-sync" ] && SYNC=0; done
TREE_APP="$TREE/qssi/packages/apps/mdm-client"
if [ "$SYNC" -eq 1 ] && [ "$DRY" -eq 0 ] && [ -d "$TREE_APP" ]; then
  if ! diff -rq "$HERE/../src" "$TREE_APP/src" >/dev/null 2>&1 \
     || ! cmp -s "$HERE/../AndroidManifest.xml" "$TREE_APP/AndroidManifest.xml"; then
    echo "→ syncing this repo into the build tree"
    cp -r "$HERE/../src" "$TREE_APP/" 2>/dev/null
    for f in Android.bp AndroidManifest.xml; do
      [ -f "$HERE/../$f" ] && cp "$HERE/../$f" "$TREE_APP/$f"
    done
    [ -d "$HERE/../res" ] && cp -r "$HERE/../res" "$TREE_APP/"
  else
    echo "→ tree copy already matches the repo"
  fi
fi

ARGS=(-t "$TREE" -s "$SERVER" -k "$KEY")
[ -n "$APK" ] && ARGS+=(--apk "$APK")
[ "${#EXTRA[@]}" -gt 0 ] && ARGS+=("${EXTRA[@]}")

if [ "$DRY" -eq 1 ]; then
  echo
  echo "dry run — would execute:"
  echo "  $HERE/build-and-publish.sh ${ARGS[*]/$KEY/<key>}"
  exit 0
fi

echo "→ publishing $([ -n "$APK" ] && basename "$APK" || echo "a fresh build from $TREE") to $SERVER"
exec "$HERE/build-and-publish.sh" "${ARGS[@]}"
