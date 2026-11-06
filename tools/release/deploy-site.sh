#!/usr/bin/env bash
# Deploy the static website image to the docker host that runs behind Portainer.
#
#   tools/release/deploy-site.sh --check   # read-only: report endpoint + container
#   tools/release/deploy-site.sh           # pull, replace, health-gate, rollback on failure
#
# Env:
#   PORTAINER_URL        required  e.g. https://portainer.example.tld
#   PORTAINER_TOKEN      required  sent as the X-API-Key header
#   PORTAINER_ENDPOINT   optional  docker endpoint id (default 4)
#   IMAGE                required for deploy, e.g. ghcr.io/k3vinb5/free-social-website:1e7b5a1
#   CONTAINER            optional  container name (default freesocial-web)
#   REGISTRY_TOKEN       optional  registry password for the pull; defaults to $GITHUB_TOKEN
#   REGISTRY_USER        optional  registry username for the pull; defaults to $GITHUB_ACTOR
#                                 then 'github' (GHCR wants the token's own actor)
#
# This script only pulls and swaps — it never builds or pushes an image. The image
# is built and pushed by .github/workflows/website-image.yml (the single
# build-and-push definition); build-release.yml then calls this script with the
# tag that workflow published. Registry credentials here are pull-only, handed to
# the docker host as X-Registry-Auth for the /images/create call.
#
# The docker API is reached through Portainer's proxy:
#   $PORTAINER_URL/api/endpoints/<id>/docker/<path>
# Portainer runs on a self-signed cert, hence curl -k. EVERY credential header —
# X-API-Key and the X-Registry-Auth blob for the pull — is written to a 0600 curl
# config file and passed with -K, so neither ever appears in `ps` or in an argv.
#
# Flow: pull image -> snapshot the running container (stop, rename to <name>-prev)
# -> create the new container on the same network/proxy -> start -> wait for the
# image HEALTHCHECK to report healthy. Any failure after the rename starts
# <name>-prev again and exits 1. Success deletes <name>-prev.
#
# Rollback is armed BEFORE the first mutating call (the stop), and it is driven by
# the docker API's actual state rather than by progress flags, so a failure or a
# signal at any step — stop, rename, create, start, health wait — still ends with
# the original container running under its original name.
#
# A failed run, a Ctrl-C and a cancelled/timed-out CI job (SIGTERM) all take the
# same path: drop the replacement, restore the original name, start it, exit 1 —
# and the temp dir holding the credential files is removed every time.
#
# No secret is ever placed on a command line: the API key lives in a 0600 curl
# config, the registry password reaches jq through a 0600 file (--rawfile), and
# the finished X-Registry-Auth header is written straight into another 0600 curl
# config. `ps` therefore shows no secret in any argv, jq's included.
set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
usage: tools/release/deploy-site.sh [--check]

  --check   read-only: docker endpoint reachability plus the current container's
            name/image/State.Status/State.Health.Status. No mutations.

  (no flag) deploy $IMAGE: pull it on the docker host, replace $CONTAINER,
            gate on health, roll back to <container>-prev on failure.

See the header of this script for the env contract.
EOF
  exit 2
}

die() {
  echo "::error::$*" >&2
  exit 1
}

case "${1-}" in
  --check) MODE=check ;;
  "") MODE=deploy ;;
  -h | --help) usage ;;
  *) echo "error: unknown argument '$1'" >&2; usage ;;
esac

command -v jq >/dev/null 2>&1 || die "jq is required"

: "${PORTAINER_URL:?PORTAINER_URL must be set}"
: "${PORTAINER_TOKEN:?PORTAINER_TOKEN must be set}"

CONTAINER="${CONTAINER:-freesocial-web}"
PORTAINER_ENDPOINT="${PORTAINER_ENDPOINT:-4}"
BASE="${PORTAINER_URL%/}"
HTTP_TIMEOUT="${HTTP_TIMEOUT:-60}"
PULL_TIMEOUT="${PULL_TIMEOUT:-300}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-60}"

WORK="$(mktemp -d)"
chmod 700 "$WORK"
umask 077

# $WORK holds the credential-bearing curl config files, so it must go on every
# exit path, including the ones that fail before the rollback handler exists.
WORK_READY=1
cleanup() {
  if [ "${WORK_READY:-0}" = 1 ] && [ -n "${WORK:-}" ]; then
    rm -rf -- "$WORK"
    WORK_READY=0
  fi
}

# A cancelled run (Ctrl-C, or a cancelled/timed-out Actions job that SIGTERMs the
# step) must not leave the old container stopped and renamed: convert the signal
# into a failure exit so the EXIT handler performs the full rollback and removes
# $WORK. A signal must never report success — the deployment did not land.
on_signal() {
  echo "::error::received SIG$1 — aborting the deploy" >&2
  exit 1
}

# Registered before anything else can fail, so no exit path can leak $WORK; the
# EXIT handler is upgraded to on_exit (rollback + cleanup) further down.
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap cleanup EXIT

# Credentials in a curl config file, never on the command line.
AUTH_CFG="$WORK/curl.cfg"
REG_CFG="$WORK/registry.cfg"
token_escaped="${PORTAINER_TOKEN//\\/\\\\}"
token_escaped="${token_escaped//\"/\\\"}"
printf 'header = "X-API-Key: %s"\n' "$token_escaped" >"$AUTH_CFG"

url_of() { printf '%s/api/endpoints/%s/docker%s' "$BASE" "$PORTAINER_ENDPOINT" "$1"; }

# http <method> <path> <outfile> [json-body] -> prints the HTTP status code
http() {
  local method="$1" path="$2" out="$3" body="${4-}"
  local args=(-sS -k -K "$AUTH_CFG" -X "$method" -o "$out" -w '%{http_code}' --max-time "$HTTP_TIMEOUT")
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "$body")
  fi
  curl "${args[@]}" "$(url_of "$path")"
}

# api <method> <path> [json-body] -> prints the HTTP status code, body in $WORK/last.json
api() {
  http "$1" "$2" "$WORK/last.json" "${3-}"
}

show_state() { # <name> -> prints "name / image / status / health"
  local name="$1" code
  code="$(http GET "/containers/$name/json" "$WORK/state.json")" || true
  if [ "$code" != "200" ]; then
    echo "container: $name (not found, http ${code:-no-response})"
    return 0
  fi
  jq -r '
    "container: \(.Name | ltrimstr("/"))\n" +
    "image:     \(.Config.Image // "?")\n" +
    "state:     \(.State.Status // "?")\n" +
    "health:    \(.State.Health.Status // "none")"
  ' "$WORK/state.json"
}

check() {
  local code
  code="$(http GET "/_ping" "$WORK/ping.txt")" || true
  if [ "$code" = "200" ] && [ "$(tr -d '\r\n' <"$WORK/ping.txt")" = "OK" ]; then
    echo "docker endpoint: ok (${BASE}/api/endpoints/${PORTAINER_ENDPOINT}/docker, http 200)"
  else
    echo "docker endpoint: unreachable (http ${code:-no-response})"
    exit 1
  fi
  show_state "$CONTAINER"
}

ROLLBACK_ARMED=0
NEW_ID=""

# Best-effort, idempotent rollback. It reads the docker API's *actual* state
# instead of trusting progress flags, so it is correct whichever step a failure
# or a signal interrupted (stop, rename, create, start, health wait):
#   (a) replacement created here  -> stop + force-delete it
#   (b) original renamed to -prev -> free the original name, rename it back
#   (c) original exists but is not running -> start it
# Every step tolerates "not reached yet" (404) and "already in that state" (304)
# and never aborts the remaining steps.
rollback() {
  set +e
  local prev="${CONTAINER}-prev" code state
  echo "::error::deploy failed after the container was touched — rolling back" >&2

  # (a) drop the replacement, if we got as far as creating it
  if [ -n "$NEW_ID" ]; then
    echo "rolling back: removing the replacement ${NEW_ID:0:12}" >&2
    api POST "/containers/${NEW_ID}/stop?t=10" >/dev/null 2>&1
    code="$(api DELETE "/containers/${NEW_ID}?force=1" 2>/dev/null)"
    case "$code" in
      204 | 404) echo "rolled back: replacement ${NEW_ID:0:12} is gone" >&2 ;;
      *) echo "::warning::could not delete the replacement ${NEW_ID:0:12} (http ${code:-no-response})" >&2 ;;
    esac
    NEW_ID=""
  fi

  # (b) the original lives under <name>-prev once the rename happened. That name
  # is the authority: if -prev exists, whatever answers to $CONTAINER is not the
  # original (a replacement may hold it after a lost create response), so free it.
  code="$(api GET "/containers/${prev}/json" 2>/dev/null)"
  if [ "$code" = "200" ]; then
    code="$(api GET "/containers/${CONTAINER}/json" 2>/dev/null)"
    if [ "$code" = "200" ]; then
      echo "rolling back: dropping the container holding the name ${CONTAINER}" >&2
      api POST "/containers/${CONTAINER}/stop?t=10" >/dev/null 2>&1
      api DELETE "/containers/${CONTAINER}?force=1" >/dev/null 2>&1
    fi
    code="$(api POST "/containers/${prev}/rename?name=$(jq -rn --arg v "$CONTAINER" '$v|@uri')" 2>/dev/null)"
    case "$code" in
      204) echo "rolled back: renamed ${prev} back to ${CONTAINER}" >&2 ;;
      404) echo "::warning::${prev} vanished before the rename back" >&2 ;;
      *) echo "::error::could not rename ${prev} back (http ${code:-no-response}) — manual intervention required" >&2 ;;
    esac
  fi

  # (c) make sure the original is running again
  code="$(api GET "/containers/${CONTAINER}/json" 2>/dev/null)"
  if [ "$code" = "200" ]; then
    state="$(jq -r '.State.Status // "?"' "$WORK/last.json" 2>/dev/null)"
    if [ "$state" = "running" ]; then
      echo "rolled back: ${CONTAINER} is running" >&2
    else
      code="$(api POST "/containers/${CONTAINER}/start" 2>/dev/null)"
      case "$code" in
        204 | 304) echo "rolled back: started ${CONTAINER} (was ${state})" >&2 ;;
        *) echo "::error::could not start ${CONTAINER} (http ${code:-no-response}) — manual intervention required" >&2 ;;
      esac
    fi
  else
    echo "::error::no container named ${CONTAINER} after the rollback — manual intervention required" >&2
  fi

  echo "::error::deployment aborted; ${CONTAINER} keeps serving the previous image" >&2
  return 0
}

# A failing command or die() both land here: roll back, remove the credential
# temp dir, then keep the failure.
on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ] && [ "$ROLLBACK_ARMED" = "1" ]; then
    ROLLBACK_ARMED=0
    rollback
  fi
  cleanup
  exit "$rc"
}

# Signals are already trapped onto on_signal above; this upgrades the EXIT
# handler from cleanup-only to rollback + cleanup now that rollback exists.
trap on_exit EXIT

deploy() {
  : "${IMAGE:?IMAGE must be set (full image reference, e.g. ghcr.io/k3vinb5/free-social-website:1e7b5a1)}"
  if [[ "$IMAGE" == *"@"* ]]; then
    die "IMAGE must be a tagged reference, not a digest: $IMAGE"
  fi

  local repo tag ref
  ref="${IMAGE##*/}"
  if [[ "$ref" == *:* ]]; then
    tag="${ref##*:}"
    repo="${IMAGE%:*}"
  else
    repo="$IMAGE"
    tag="latest"
  fi
  [ -n "$repo" ] && [ -n "$tag" ] || die "cannot split IMAGE into repository/tag: $IMAGE"

  echo "docker endpoint: ${BASE}/api/endpoints/${PORTAINER_ENDPOINT}/docker"
  echo "target container: ${CONTAINER}"
  echo "target image:     ${IMAGE}"

  local code
  code="$(http GET "/_ping" "$WORK/ping.txt")" || die "cannot reach the docker endpoint"
  [ "$code" = "200" ] || die "docker endpoint returned http ${code}"

  # --- pull -----------------------------------------------------------------
  local reg_user="${REGISTRY_USER:-${GITHUB_ACTOR:-github}}"
  local reg_token="${REGISTRY_TOKEN:-${GITHUB_TOKEN:-}}" have_reg_auth=0 pull_code
  if [ -n "$reg_token" ]; then
    # The password must never appear in ANY argv, jq's included, so it is written
    # to a 0600 file and read back with jq --rawfile; the finished header is then
    # streamed into a second 0600 curl config without ever being echoed.
    local reg_secret="$WORK/registry.secret" reg_authfile="$WORK/registry.auth"
    printf '%s' "$reg_token" >"$reg_secret"
    if ! jq -cn --arg u "$reg_user" --rawfile p "$reg_secret" \
      '{username:$u,password:$p}' | base64 -w0 >"$reg_authfile"; then
      rm -f -- "$reg_secret" "$reg_authfile"
      die "could not build the registry auth header"
    fi
    rm -f -- "$reg_secret"
    [ -s "$reg_authfile" ] || { rm -f -- "$reg_authfile"; die "registry auth header is empty"; }
    {
      printf 'header = "X-Registry-Auth: '
      cat "$reg_authfile"
      printf '"\n'
    } >"$REG_CFG"
    rm -f -- "$reg_authfile"
    have_reg_auth=1
    echo "pulling ${repo}:${tag} (registry auth as ${reg_user}, header from a 0600 file)"
  else
    echo "pulling ${repo}:${tag} (no registry credentials supplied)"
  fi
  local pull_args=(-sS -k -K "$AUTH_CFG" -X POST -o "$WORK/pull.json" -w '%{http_code}' --max-time "$PULL_TIMEOUT")
  if [ "$have_reg_auth" = 1 ]; then
    pull_args+=(-K "$REG_CFG")
  fi
  pull_code="$(curl "${pull_args[@]}" \
    "$(url_of "/images/create?fromImage=$(jq -rn --arg v "$repo" '$v|@uri')&tag=$(jq -rn --arg v "$tag" '$v|@uri')")")" ||
    die "image pull request failed (transport error)"
  if [ "$pull_code" != "200" ]; then
    head -c 400 "$WORK/pull.json" >&2 || true
    die "image pull failed (http ${pull_code})"
  fi
  if jq -rs 'map(select(.error != null or .errorDetail != null)) | length > 0' "$WORK/pull.json" 2>/dev/null | grep -qx true; then
    jq -rs 'map(select(.error != null or .errorDetail != null)) | .[0]' "$WORK/pull.json" 2>/dev/null | head -c 400 >&2 || true
    die "image pull reported an error"
  fi
  echo "pull complete"

  # --- snapshot the running container ---------------------------------------
  code="$(http GET "/containers/${CONTAINER}/json" "$WORK/old.json")" ||
    die "cannot read container ${CONTAINER}"
  [ "$code" = "200" ] || die "container ${CONTAINER} not found (http ${code})"

  local old_name network restart_policy
  old_name="$(jq -r '.Name | ltrimstr("/")' "$WORK/old.json")"
  network="$(jq -r '.HostConfig.NetworkMode // "bridge"' "$WORK/old.json")"
  restart_policy="$(jq -r '.HostConfig.RestartPolicy.Name // empty' "$WORK/old.json")"
  [ -n "$restart_policy" ] || restart_policy="unless-stopped"
  echo "current image: $(jq -r '.Config.Image // "?"' "$WORK/old.json")"
  echo "network: ${network}  restart policy: ${restart_policy}"
  [ "$old_name" = "$CONTAINER" ] || die "unexpected container name '$old_name' (wanted '$CONTAINER')"

  local prev="${CONTAINER}-prev"
  # A stale -prev from an earlier failed run would block the rename.
  code="$(api GET "/containers/${prev}/json")" || true
  if [ "$code" = "200" ]; then
    echo "removing stale ${prev}"
    api DELETE "/containers/${prev}?force=1" >/dev/null || die "cannot remove stale ${prev}"
  fi

  # Arming here, before the first mutating call, is what makes a SIGTERM/INT
  # during the stop recoverable: from this point a failure or a signal always
  # runs the state-aware rollback above.
  echo "stopping ${CONTAINER}"
  ROLLBACK_ARMED=1
  code="$(api POST "/containers/${CONTAINER}/stop?t=30")" || die "stop failed"
  case "$code" in
    204 | 304) ;;
    *) die "stop returned http ${code}" ;;
  esac

  # --- rename (last point before the point of no return) --------------------
  code="$(api POST "/containers/${CONTAINER}/rename?name=${prev}")" || die "rename failed"
  [ "$code" = "204" ] || die "rename returned http ${code}"
  echo "renamed ${CONTAINER} -> ${prev}"

  # --- create + start the replacement ---------------------------------------
  local create_body
  create_body="$(jq -cn \
    --arg image "$IMAGE" \
    --arg net "$network" \
    --arg restart "$restart_policy" \
    --arg name "$CONTAINER" \
    '{
       Image: $image,
       HostConfig: {
         RestartPolicy: {Name: $restart},
         NetworkMode: $net
       }
     }
     + (if ($net == "host" or $net == "none" or $net == "bridge" or $net == "default" or ($net | startswith("container:")))
        then {}
        else {NetworkingConfig: {EndpointsConfig: {($net): {Aliases: [$name]}}}}
        end)')"

  code="$(http POST "/containers/create?name=${CONTAINER}" "$WORK/create.json" "$create_body")" ||
    die "create request failed"
  if [ "$code" != "201" ]; then
    head -c 400 "$WORK/create.json" >&2 || true
    die "container create returned http ${code}"
  fi
  NEW_ID="$(jq -r '.Id // empty' "$WORK/create.json")"
  [ -n "$NEW_ID" ] || die "container create returned no id"
  jq -r '.Warnings[]? | "warning: \(.)"' "$WORK/create.json" || true
  echo "created ${NEW_ID:0:12}"

  code="$(api POST "/containers/${NEW_ID}/start")" || die "start request failed"
  case "$code" in
    204 | 304) ;;
    *) die "start returned http ${code}" ;;
  esac
  echo "started ${NEW_ID:0:12}"

  # --- health gate ----------------------------------------------------------
  local deadline health state waited=0
  deadline=$((SECONDS + HEALTH_TIMEOUT))
  while :; do
    code="$(http GET "/containers/${NEW_ID}/json" "$WORK/new.json")" || die "cannot read the new container state"
    [ "$code" = "200" ] || die "reading the new container returned http ${code}"
    state="$(jq -r '.State.Status // "?"' "$WORK/new.json")"
    health="$(jq -r '.State.Health.Status // empty' "$WORK/new.json")"

    if [ "$health" = "healthy" ]; then
      echo "health: healthy after ${waited}s"
      break
    fi
    if [ -z "$health" ]; then
      # Image carries no HEALTHCHECK: fall back to "started and still running".
      if [ "$state" = "running" ]; then
        echo "health: none (image has no HEALTHCHECK); state is running after ${waited}s"
        break
      fi
      die "container is '${state}' and has no HEALTHCHECK"
    fi
    if [ "$health" = "unhealthy" ]; then
      die "container reported unhealthy after ${waited}s"
    fi
    if [ "$state" = "exited" ] || [ "$state" = "dead" ]; then
      die "container is '${state}' while waiting for health"
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      die "container not healthy after ${HEALTH_TIMEOUT}s (state: ${state}, health: ${health:-none})"
    fi
    sleep 3
    waited=$((waited + 3))
  done

  # --- commit ---------------------------------------------------------------
  ROLLBACK_ARMED=0
  code="$(api DELETE "/containers/${prev}?force=1")" || true
  case "$code" in
    204) echo "removed ${prev}" ;;
    404) echo "${prev} already gone" ;;
    *) echo "::warning::could not remove ${prev} (http ${code:-no-response})" ;;
  esac

  echo "deployed ${IMAGE} to ${CONTAINER}"
  show_state "$CONTAINER"
}

if [ "$MODE" = "check" ]; then
  check
else
  deploy
fi
