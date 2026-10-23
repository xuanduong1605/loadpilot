#!/usr/bin/env bash
# Deploy a trusted release on the native VPS. No build or database restore here.
set -Eeuo pipefail
umask 077
root=/opt/loadpilot
release=$(realpath -e "${1:?Usage: deploy-vps.sh /opt/loadpilot/releases/RELEASE_ID}")
[[ "$release" =~ ^"$root"/releases/[0-9]+(-[0-9]+)*$ ]] || { echo 'Unexpected release path' >&2; exit 1; }
[[ ${LOADPILOT_ALLOW_APP_ROLLBACK:-false} == true ]] || { echo 'Review migration compatibility and explicitly allow application rollback.' >&2; exit 1; }
public_url=${LOADPILOT_PUBLIC_URL:?Set the configured HTTPS origin}
health_host=${public_url#https://}
[[ "$public_url" == "https://$health_host" && "$health_host" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]] || { echo 'Expected an HTTPS hostname origin without path/port' >&2; exit 1; }
attempts=${LOADPILOT_HEALTH_ATTEMPTS:-30}
interval=${LOADPILOT_HEALTH_INTERVAL:-2}
[[ "$attempts" =~ ^[0-9]+$ && "$interval" =~ ^[0-9]+$ ]] && ((attempts >= 1 && attempts <= 60 && interval <= 10)) || exit 1
test -f /etc/loadpilot/platform.env
test -d "$root/releases"
mkdir -p "$root/backups"
chmod 0700 "$root/backups"
exec 9>"$root/deploy.lock"
flock -n 9 || { echo 'Another deployment is active' >&2; exit 1; }
for file in platform.jar web.tar.gz COMMIT SHA256SUMS; do test -f "$release/$file"; done
(cd "$release" && sha256sum --strict --check SHA256SUMS)
commit=$(cat "$release/COMMIT")
[[ "$commit" =~ ^[a-f0-9]{40}$ ]]
available=$(df -Pk "$root" | awk 'NR == 2 {print $4}')
((available >= 524288)) || { echo 'Need at least 512 MiB free before backup/extraction' >&2; exit 1; }
previous=''
if [[ -L "$root/current" ]]; then
  previous=$(readlink -e "$root/current")
  [[ "$previous" =~ ^"$root"/releases/[0-9]+(-[0-9]+)*$ && "$previous" != "$release" ]] || exit 1
elif [[ -e "$root/current" ]]; then
  echo 'current must be a release symlink' >&2; exit 1
fi

healthy() {
  local expected=$1
  for ((attempt=1; attempt<=attempts; attempt++)); do
    if systemctl is-active --quiet loadpilot.service \
      && curl --fail --silent --max-time 3 http://127.0.0.1:8080/actuator/health | jq -e '.status == "UP"' >/dev/null \
      && [[ $(curl --fail --silent --max-time 5 --resolve "$health_host:443:127.0.0.1" "$public_url/.well-known/loadpilot-release") == "$expected" ]] \
      && [[ $(curl --silent --max-time 5 --output /dev/null --write-out '%{http_code}' --resolve "$health_host:443:127.0.0.1" "$public_url/api/status") == 401 ]]; then
      return 0
    fi
    sleep "$interval"
  done
  return 1
}

if [[ -n "$previous" ]]; then
  healthy "$(cat "$previous/COMMIT")" || { echo 'Previous release health is unknown; reconcile it before deployment.' >&2; exit 1; }
fi
# CI validates the archive's member names/types before transport; hashes bind it.
[[ ! -e "$release/web" ]] || { echo 'Refusing a previously extracted release' >&2; exit 1; }
mkdir -m 0755 "$release/web"
tar -xzf "$release/web.tar.gz" -C "$release/web" --no-same-owner --no-same-permissions
find "$release/web" -type d -exec chmod 0755 {} +
find "$release/web" -type f -exec chmod 0644 {} +
test -f "$release/web/index.html"
test "$(cat "$release/web/.well-known/loadpilot-release")" = "$commit"
backup="$root/backups/predeploy-$(basename "$release")-$(date -u +%Y%m%dT%H%M%SZ).dump"
PGSERVICE=loadpilot-backup pg_dump --no-password --format=custom --file="$backup"
test -s "$backup"

switch_release() {
  local destination=$1
  local next_link="$root/current.$$.${RANDOM}.next"
  ln -s -- "$destination" "$next_link"
  if ! mv -Tf -- "$next_link" "$root/current"; then rm -- "$next_link"; return 1; fi
}

rollback() {
  local code=$1
  trap - ERR INT TERM HUP
  echo 'Deployment interrupted/failed; attempting schema-compatible application rollback. Database is unchanged.' >&2
  if ! sudo -n systemctl stop loadpilot.service; then
    echo 'Cannot stop the service; operator recovery required.' >&2; exit "$code"
  fi
  if [[ -n "$previous" ]]; then
    if switch_release "$previous" && sudo -n systemctl start loadpilot.service && healthy "$(cat "$previous/COMMIT")"; then
      echo 'Previous application release restored; original deployment still failed.' >&2
    else
      echo 'Rollback failed; operator recovery required.' >&2
    fi
  elif [[ -L "$root/current" && $(readlink -f "$root/current") == "$release" ]]; then
    rm -- "$root/current"
    echo 'First release failed; service stopped and current link removed.' >&2
  fi
  exit "$code"
}

# Arm recovery before stop/switch, including signals and unexpected command errors.
trap 'rollback 1' ERR
trap 'rollback 130' INT
trap 'rollback 143' TERM HUP
sudo -n systemctl stop loadpilot.service
switch_release "$release"
sudo -n systemctl start loadpilot.service
healthy "$commit"
trap - ERR INT TERM HUP
printf 'Deployment healthy: %s; backup: %s\n' "$commit" "$backup"
# Foundation only. Before experiments exist, implement maintenance/start locking
# atomically in the backend and enforce authenticated recovery readiness here.
