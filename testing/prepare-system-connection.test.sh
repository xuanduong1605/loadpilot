#!/usr/bin/env bash
# Tests only local temporary files, never /etc, systemd or sshd.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source "$root/scripts/prepare-system-connection.sh"
mkdir -p "$root/.tools/quick-connect-review"
test_dir=$(mktemp -d "$root/.tools/quick-connect-review/files.XXXXXX")
case $test_dir in "$root/.tools/quick-connect-review/"*) ;; *) echo 'Unsafe test path' >&2; exit 1;; esac
trap 'rm -f -- "$test_dir/env" "$test_dir/hosts" "$test_dir/original-env" "$test_dir/original-hosts"; rmdir -- "$test_dir"' EXIT
env_file=$test_dir/env; hosts_file=$test_dir/hosts
printf '# preserved comment\nDATABASE_URL=jdbc:postgresql://localhost/loadpilot\nPOSTGRES_PASSWORD=fixture-only\nCONNECTION_ALLOWED_ORIGINS=https://existing.invalid:443\nCONNECTION_ALLOWED_ADDRESSES=10.0.0.2\nCONNECTION_MASTER_KEY=existing-fixture-key\n' > "$env_file"
printf '127.0.0.1 localhost\n::1 localhost\n' > "$hosts_file"
prepare_connection_files "$env_file" "$hosts_file" kubernetes.docker.internal 16443 http://127.0.0.1:18080
grep -Fxq 'POSTGRES_PASSWORD=fixture-only' "$env_file"
grep -Fxq 'CONNECTION_MASTER_KEY=existing-fixture-key' "$env_file"
grep -Fxq 'CONNECTION_ALLOWED_ORIGINS=https://existing.invalid:443,https://kubernetes.docker.internal:16443,http://127.0.0.1:18080' "$env_file"
grep -Fxq 'CONNECTION_ALLOWED_ADDRESSES=10.0.0.2,127.0.0.1' "$env_file"
cp "$env_file" "$test_dir/original-env"; cp "$hosts_file" "$test_dir/original-hosts"
prepare_connection_files "$env_file" "$hosts_file" kubernetes.docker.internal 16443 http://127.0.0.1:18080
cmp "$env_file" "$test_dir/original-env"; cmp "$hosts_file" "$test_dir/original-hosts"
for host in 'evil;command' 10.0.0.1; do
  if prepare_connection_files "$env_file" "$hosts_file" "$host" 16443 http://127.0.0.1:18080; then echo 'Unsafe hostname accepted' >&2; exit 1; fi
done
if prepare_connection_files "$env_file" "$hosts_file" kubernetes.docker.internal 16443 http://evil.invalid:18080; then echo 'Unsafe API origin accepted' >&2; exit 1; fi
cmp "$env_file" "$test_dir/original-env"; cmp "$hosts_file" "$test_dir/original-hosts"
printf 'CONNECTION_ALLOWED_ADDRESSES=other\n' >> "$env_file"
if prepare_connection_files "$env_file" "$hosts_file" kubernetes.docker.internal 16443 http://127.0.0.1:18080; then echo 'Duplicate setting accepted' >&2; exit 1; fi
cp "$test_dir/original-env" "$env_file"
printf '10.0.0.3 kubernetes.docker.internal\n' > "$hosts_file"
if prepare_connection_files "$env_file" "$hosts_file" kubernetes.docker.internal 16443 http://127.0.0.1:18080; then echo 'Existing mapping overwritten' >&2; exit 1; fi
cmp "$env_file" "$test_dir/original-env"
printf 'DATABASE_URL=fixture\nCONNECTION_MASTER_KEY=\n' > "$env_file"
printf '127.0.0.1 localhost\n' > "$hosts_file"
prepare_connection_files "$env_file" "$hosts_file" localhost 16443 http://127.0.0.1:18080
generated=$(connection_setting "$env_file" CONNECTION_MASTER_KEY)
[[ ${#generated} == 44 ]]
[[ $(printf '%s' "$generated" | base64 -d | wc -c) == 32 ]]
grep -Fxq 'CONNECTION_ALLOWED_ADDRESSES=127.0.0.1,::1' "$env_file"
echo 'PASS: exact destinations; unrelated settings/key preserved; idempotence; unsafe input, duplicate settings and host conflict rejected; missing key generated.'
