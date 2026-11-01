#!/usr/bin/env bash
# Invoked explicitly over the operator's existing SSH login. No kubeconfig or tokens.
set -euo pipefail

connection_setting() {
  local file=$1 key=$2 line value='' count=0
  while IFS= read -r line || [[ -n $line ]]; do
    if [[ $line == "$key="* ]]; then
      (( count += 1 )); value=${line#*=}
    fi
  done < "$file"
  if (( count > 1 )); then echo "Duplicate service setting; resolve manually: $key" >&2; return 1; fi
  if [[ $value == \"*\" || $value == \'*\' ]]; then value=${value:1:${#value}-2}; fi
  printf '%s' "$value"
}

prepare_connection_files() {
  local env_file=$1 hosts_file=$2 host=$3 port=$4 api_origin=$5 existing literal line key value mapping
  [[ -f $env_file && ! -L $env_file && -f $hosts_file && ! -L $hosts_file ]] || { echo 'Expected existing regular service environment and hosts files.' >&2; return 1; }
  [[ $host =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ && $port =~ ^[0-9]+$ ]] || { echo 'Invalid Kubernetes hostname or port.' >&2; return 1; }
  (( port >= 1024 && port <= 65535 )) || { echo 'Invalid tunnel port.' >&2; return 1; }
  if [[ $host =~ ^[0-9.]+$ && $host != 127.0.0.1 ]]; then echo 'Use a hostname or 127.0.0.1 Kubernetes endpoint.' >&2; return 1; fi
  [[ $api_origin =~ ^http://127\.0\.0\.1:([0-9]+)$ ]] || { echo 'Expected a loopback HTTP API origin.' >&2; return 1; }
  (( BASH_REMATCH[1] >= 1024 && BASH_REMATCH[1] <= 65535 )) || { echo 'Invalid API tunnel port.' >&2; return 1; }
  local -A settings=() found=()
  for key in CONNECTION_ALLOWED_ORIGINS CONNECTION_ALLOWED_ADDRESSES; do
    existing=$(connection_setting "$env_file" "$key") || return 1
    literal=${existing//\[/}; literal=${literal//\]/}
    [[ -z $literal || $literal =~ ^[A-Za-z0-9._~:/,-]+$ ]] || { echo "Review nonliteral setting manually: $key" >&2; return 1; }
    settings[$key]=$existing
  done
  for value in "https://$host:$port" "$api_origin"; do
    case ,${settings[CONNECTION_ALLOWED_ORIGINS]}, in
      *",$value,"*) ;;
      *) settings[CONNECTION_ALLOWED_ORIGINS]+="${settings[CONNECTION_ALLOWED_ORIGINS]:+,}$value" ;;
    esac
  done
  case ,${settings[CONNECTION_ALLOWED_ADDRESSES]}, in
    *,127.0.0.1,*) ;;
    *) settings[CONNECTION_ALLOWED_ADDRESSES]+="${settings[CONNECTION_ALLOWED_ADDRESSES]:+,}127.0.0.1" ;;
  esac
  if [[ $host == localhost ]]; then
    case ,${settings[CONNECTION_ALLOWED_ADDRESSES]}, in *,::1,*) ;; *) settings[CONNECTION_ALLOWED_ADDRESSES]+=,::1 ;; esac
  fi
  existing=$(connection_setting "$env_file" CONNECTION_MASTER_KEY) || return 1
  if [[ -z $existing ]]; then
    settings[CONNECTION_MASTER_KEY]=$(head -c 32 /dev/urandom | base64 | tr -d '\r\n')
    [[ ${#settings[CONNECTION_MASTER_KEY]} == 44 ]] || { echo 'Could not generate the encryption key.' >&2; return 1; }
  fi
  mapping=$(awk -v name="$host" '{sub(/#.*/, ""); for(i=2;i<=NF;i++) if(tolower($i)==tolower(name)) print $1}' "$hosts_file")
  if [[ $host != localhost && -n $mapping ]] && [[ $(printf '%s\n' "$mapping" | sort -u) != 127.0.0.1 ]]; then
    echo 'Existing hostname mapping is not loopback; resolve it manually before setup.' >&2; return 1
  fi
  local env_tmp hosts_tmp
  env_tmp=$(mktemp "${env_file%/*}/.loadpilot-env.XXXXXX")
  hosts_tmp=$(mktemp "${hosts_file%/*}/.loadpilot-hosts.XXXXXX")
  if ! cp -p -- "$env_file" "$env_tmp" || ! cp -p -- "$hosts_file" "$hosts_tmp"; then
    rm -f -- "$env_tmp" "$hosts_tmp"; return 1
  fi
  {
    while IFS= read -r line || [[ -n $line ]]; do
      key=${line%%=*}
      if [[ $key =~ ^CONNECTION_(ALLOWED_ORIGINS|ALLOWED_ADDRESSES|MASTER_KEY)$ && -v settings[$key] ]]; then printf '%s=%s\n' "$key" "${settings[$key]}"; found[$key]=1
      else printf '%s\n' "$line"; fi
    done < "$env_file"
    for key in "${!settings[@]}"; do [[ -v found[$key] ]] || printf '%s=%s\n' "$key" "${settings[$key]}"; done
  } > "$env_tmp"
  if [[ $host != localhost && $host != 127.0.0.1 && -z $mapping ]]; then
    printf '\n127.0.0.1 %s # Loadpilot SSH tunnel\n' "$host" >> "$hosts_tmp"
    if ! mv -f -- "$hosts_tmp" "$hosts_file"; then rm -f -- "$env_tmp" "$hosts_tmp"; return 1; fi
  else rm -f -- "$hosts_tmp"; fi
  if cmp -s -- "$env_file" "$env_tmp"; then rm -f -- "$env_tmp"
  elif ! mv -f -- "$env_tmp" "$env_file"; then rm -f -- "$env_tmp"; return 1; fi
  echo 'Exact loopback destinations configured. Existing key and unrelated settings preserved.'
}

main() {
  [[ $EUID == 0 ]] || { echo 'VPS preparation requires root or passwordless sudo for this command.' >&2; return 1; }
  [[ $# == 3 ]] || { echo 'Expected Kubernetes hostname, Kubernetes port and forwarded API origin.' >&2; return 1; }
  local ssh_config gateway_ports
  ssh_config=$(/usr/sbin/sshd -T) || { echo 'Cannot verify SSH server forwarding policy.' >&2; return 1; }
  gateway_ports=$(awk '$1 == "gatewayports" { print $2 }' <<< "$ssh_config")
  [[ $gateway_ports == no || $gateway_ports == clientspecified ]] || { echo 'Configure GatewayPorts=no before using this helper.' >&2; return 1; }
  prepare_connection_files /etc/loadpilot/platform.env /etc/hosts "$@"
  systemctl restart loadpilot.service
  systemctl is-active --quiet loadpilot.service
  echo 'Loadpilot restarted. Sign in again after opening the tunnel.'
}
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then main "$@"; fi
