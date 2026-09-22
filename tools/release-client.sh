#!/usr/bin/env bash
# Release the firmware client everywhere, in one command: build BOTH trees and publish
# each build to stage and (optionally) live.
#
#   tools/release-client.sh                 # both trees -> stage only
#   tools/release-client.sh --live          # both trees -> stage, then live (asks once)
#   tools/release-client.sh --dry-run       # print every step, publish nothing
#   tools/release-client.sh --qcom-only     # skip the GMS tree
#   tools/release-client.sh --gms-only      # skip the QCOM tree
#
# Why a wrapper over a wrapper: a release is four publishes (2 trees x 2 servers) that must
# all carry the SAME version and the SAME commit, and the slow part — the AOSP build — must
# happen exactly once per tree. This builds each tree once (publish-client.sh), then reuses
# that APK for the second server with --apk-latest.
#
# Slots are per signing key: the QCOM tree is signed with the Rockchip platform cert
# (firmware-qcom), the GMS tree with the AOSP test-keys cert (firmware-gms). A device is
# only ever offered the slot whose key it already trusts, and build-and-publish.sh refuses a
# mismatched signer, so the two trees can never cross-publish.
#
# The changelog on each release entry comes from the LAST COMMIT SUBJECT, so this refuses to
# run with a dirty tree: a release published from uncommitted work is labelled with the
# previous change and cannot be reproduced from git.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
PUBLISH="$HERE/publish-client.sh"

QCOM_TREE="${QCOM_TREE:-$HOME/A15/QCOM6125_A15_T7_new}"
GMS_TREE="${GMS_TREE:-$HOME/A15/GMS}"

LIVE=0; DRY=0; DO_QCOM=1; DO_GMS=1; ALLOW_DIRTY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --live)        LIVE=1; shift ;;
    --dry-run)     DRY=1; shift ;;
    --qcom-only)   DO_GMS=0; shift ;;
    --gms-only)    DO_QCOM=0; shift ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    -h|--help)     sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# ── the release must be a commit ─────────────────────────────────────────────
if [ "$ALLOW_DIRTY" -eq 0 ] && [ -n "$(git -C "$REPO" status --porcelain)" ]; then
  cat >&2 <<'MSG'
Working tree is dirty. Commit first — the release changelog is taken from the last commit
subject, so publishing now would label this build with the previous change.

  git -C . commit -am "client X.Y.Z (code N): <what changed>"

(--allow-dirty overrides, for a re-publish of an already-committed build.)
MSG
  exit 1
fi

VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$REPO/AndroidManifest.xml" | head -n1)"
CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$REPO/AndroidManifest.xml" | head -n1)"
SUBJECT="$(git -C "$REPO" log -1 --format=%s)"

echo "releasing client $VERSION (code $CODE)"
echo "  commit:    $(git -C "$REPO" rev-parse --short HEAD) — $SUBJECT"
echo "  trees:     $([ "$DO_QCOM" -eq 1 ] && echo -n "qcom ")$([ "$DO_GMS" -eq 1 ] && echo -n "gms")"
echo "  servers:   stage$([ "$LIVE" -eq 1 ] && echo -n " + LIVE")"
echo

# ── one confirmation for the whole release, not one per publish ──────────────
if [ "$LIVE" -eq 1 ] && [ "$DRY" -eq 0 ]; then
  echo "This publishes to LIVE — the restaurant fleet."
  echo "Every device on a client older than $CODE will be offered this build."
  printf 'Type "live" to continue: '
  read -r ok
  [ "$ok" = "live" ] || { echo "aborted"; exit 1; }
  echo
fi

run() {
  if [ "$DRY" -eq 1 ]; then echo "  would run: $*"; return 0; fi
  "$@"
}

# Publishing to live re-prompts inside publish-client.sh; the release was already confirmed
# once above, so answer that prompt from here rather than asking the operator four times.
run_live() {
  if [ "$DRY" -eq 1 ]; then echo "  would run: $*"; return 0; fi
  printf 'live\n' | "$@"
}

release_tree() {
  local name="$1" tree="$2"
  echo "── $name ─────────────────────────────────────────────"
  if [ ! -d "$tree" ]; then
    echo "  tree not found: $tree — skipped" >&2
    return 1
  fi
  # Builds the tree (slow); publish-client.sh infers the slot from the signing key.
  run "$PUBLISH" --tree "$tree"
  # Same APK, second server: no rebuild, so both servers are guaranteed the same bytes.
  if [ "$LIVE" -eq 1 ]; then
    run_live "$PUBLISH" --live --tree "$tree" --apk-latest
  fi
  echo
}

rc=0
[ "$DO_QCOM" -eq 1 ] && { release_tree "QCOM ($QCOM_TREE)" "$QCOM_TREE" || rc=1; }
[ "$DO_GMS"  -eq 1 ] && { release_tree "GMS ($GMS_TREE)"  "$GMS_TREE"  || rc=1; }

if [ "$rc" -eq 0 ]; then
  echo "client $VERSION (code $CODE) released to stage$([ "$LIVE" -eq 1 ] && echo -n " and live")."
  echo "Devices on an older client now show \"Update agent\" on their device page."
else
  echo "one or more trees failed — see above" >&2
fi
exit "$rc"
