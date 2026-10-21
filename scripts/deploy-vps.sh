#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/opt/loadpilot
release=$(realpath "${1:?Usage: deploy-vps.sh /opt/loadpilot/releases/RUN_ID}")
[[ "$release" =~ ^/opt/loadpilot/releases/[0-9]+$ ]] || { echo 'Unexpected release path'; exit 1; }
test -f "$root/.env"
test -f "$release/release.env"
mkdir -p "$root/backups"
exec 9>"$root/deploy.lock"
flock -n 9 || { echo 'Another deployment is active'; exit 1; }
# Each release resolves .env and secrets relative to its own Compose path.
ln -sfn "$root/.env" "$release/.env"
ln -sfn "$root/secrets" "$release/secrets"
compose() {
  local directory=$1
  shift
  local files=(-f "$directory/infra/compose/vps.yml")
  docker compose --env-file "$root/.env" --env-file "$directory/release.env" "${files[@]}" "$@"
}
previous=$(readlink -f "$root/current" || true)
compose "$release" config --quiet
compose "$release" pull
if [[ -n "$previous" && -f "$previous/release.env" ]]; then
  # Capture a backup before Flyway migrations. Copy it off this host separately.
  compose "$previous" exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$root/backups/predeploy-$(date -u +%Y%m%dT%H%M%SZ).dump"
fi
healthy=false
if compose "$release" up -d --remove-orphans; then
  for attempt in $(seq 1 36); do
    if curl --fail --silent --max-time 4 http://127.0.0.1:8088/healthz >/dev/null \
       && curl --fail --silent --max-time 4 http://127.0.0.1:8088/ >/dev/null; then
      healthy=true
      break
    fi
    sleep 5
  done
fi
if [[ "$healthy" == true ]]; then
  if [[ -n "$previous" ]]; then ln -sfn "$previous" "$root/previous"; fi
  ln -sfn "$release" "$root/current"
  echo "Deployment healthy: $(cat "$release/COMMIT")"
else
  echo 'Deployment failed; restoring the previous images. Database migrations are not reversed.' >&2
  compose "$release" logs --tail 80 platform web >&2 || true
  if [[ -n "$previous" && -f "$previous/release.env" ]]; then
    compose "$previous" up -d --remove-orphans
    curl --retry 12 --retry-delay 5 --retry-connrefused --fail --max-time 5 http://127.0.0.1:8088/healthz
  fi
  exit 1
fi
