# Sourced by build-and-publish.sh and publish-client.sh: wait_until_idle returns once no
# build is running, or fails after AIO_BUILD_WAIT_MAX seconds. Needs $TREE.
#
# Only build while no other build is running. The OTA console (~/A15/otapub/otaweb.py,
# http://100.113.189.96:8777) builds images in these same trees, and the other sessions
# working on the trees start builds too; two builds in one tree fight over out/.lock, and a
# client build during an image build can land half-synced client files in that image.
# The console knows about its own jobs, its queue, and build processes started outside it
# ("external"), so ask it and wait until all three are empty. If the console can't be
# reached, fall back to looking for a Soong process in the tree. AIO_BUILD_WAIT_MAX (seconds,
# default 3600) bounds the wait; the build is refused rather than started over a busy tree.
OTAWEB="${OTAWEB:-http://100.113.189.96:8777}"
wait_until_idle() {
  local tok cookie why start=$(date +%s) max="${AIO_BUILD_WAIT_MAX:-3600}" said=""
  cookie="$(mktemp)"; trap 'rm -f "$cookie"' RETURN
  while :; do
    why=""
    tok="$(cat "$HOME/A15/otapub/logs/otaweb-token" 2>/dev/null || true)"
    if [ -n "$tok" ] && curl -sS -m 5 -c "$cookie" -o /dev/null -X POST -H 'Content-Type: application/json' \
         -d "{\"token\":\"$tok\"}" "$OTAWEB/api/login" 2>/dev/null; then
      why="$(curl -sS -m 8 -b "$cookie" "$OTAWEB/api/state" 2>/dev/null | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print("console did not answer"); sys.exit()
r = d.get("running")
if r: print("the OTA console is running: %s" % r.get("title", r.get("kind", "a job")))
elif d.get("queue"): print("the OTA console has %d job(s) queued" % len(d["queue"]))
elif d.get("external"):
    e = d["external"][0]
    print("a build is running outside the console: %s" % (e.get("title") or e.get("cmd") or e))
' 2>/dev/null)"
    else
      why="console did not answer"
    fi
    if [ "$why" = "console did not answer" ]; then
      why=""
      for p in $(pgrep -f soong_ui 2>/dev/null); do
        [ "$(readlink "/proc/$p/cwd" 2>/dev/null)" = "$TREE/qssi" ] && { why="a Soong build is running in $TREE (pid $p)"; break; }
      done
    fi
    [ -z "$why" ] && return 0
    if [ $(( $(date +%s) - start )) -ge "$max" ]; then
      echo "still busy after ${max}s: $why — not building" >&2
      return 1
    fi
    [ "$why" != "$said" ] && { echo "→ waiting: $why"; said="$why"; }
    sleep 30
  done
}
