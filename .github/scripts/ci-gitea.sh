#!/usr/bin/env bash
# Starts a fresh Gitea of a given version for the integration tests – no Docker needed, so it works on
# GitHub Actions and in Gitea Actions job containers alike, and locally next to the dev stack.
#
#   .github/scripts/ci-gitea.sh <version|latest|min> <work dir> [port]     e.g. .github/scripts/ci-gitea.sh 28.0.0 /tmp/gitea-ci 3300
#   .github/scripts/ci-gitea.sh stop <work dir>
#
# Downloads the binary from dl.gitea.com, runs it with SQLite on 127.0.0.1:<port>, creates the test users
# `intellij-dev` / `intellij-reviewer` with tokens, writes <work dir>/gitea-test.env (same keys as
# gitea-test.local.env) and runs the seed scripts next to this one.
#
#   GITEA_TEST_ENV=<work dir>/gitea-test.env GITEA_TEST_NO_RUNNER=1 ./gradlew integrationTest
#
# There is no Actions runner: runs are created but never finish, tests that need finished runs are skipped.
set -euo pipefail

MIN_VERSION=28.0.0
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ "${1:-}" = stop ]; then
  dir="$2"
  [ -f "$dir/gitea.pid" ] && kill "$(cat "$dir/gitea.pid")" 2>/dev/null || true
  rm -f "$dir/gitea.pid"
  exit 0
fi

version="${1:?version, latest or min}"
dir="${2:?work dir}"
port="${3:-3300}"

case "$version" in
  latest) version=$(curl -fsSL https://dl.gitea.com/gitea/version.json | python3 -c 'import json,sys; print(json.load(sys.stdin)["latest"]["version"])') ;;
  min) version=$MIN_VERSION ;;
esac

case "$(uname -s)" in Linux) os=linux ;; Darwin) os=darwin ;; *) echo "unsupported OS" >&2; exit 1 ;; esac
case "$(uname -m)" in x86_64|amd64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; *) echo "unsupported CPU" >&2; exit 1 ;; esac

mkdir -p "$dir"
dir="$(cd "$dir" && pwd)"
bin="$dir/gitea-$version"
if [ ! -x "$bin" ]; then
  echo "== download Gitea $version ($os-$arch)"
  curl -fsSL -o "$bin" "https://dl.gitea.com/gitea/$version/gitea-$version-$os-$arch"
  chmod +x "$bin"
fi

export GITEA_WORK_DIR="$dir"
config="$dir/app.ini"
url="http://127.0.0.1:$port"

if [ ! -f "$config" ]; then
  cat > "$config" <<EOF
APP_NAME = Gitea CI
RUN_MODE = prod
WORK_PATH = $dir
; job containers run as root, which Gitea refuses otherwise
I_AM_BEING_UNSAFE_RUNNING_AS_ROOT = true

[server]
HTTP_ADDR = 127.0.0.1
HTTP_PORT = $port
ROOT_URL = $url/
DISABLE_SSH = true
OFFLINE_MODE = true
LFS_START_SERVER = false

[database]
DB_TYPE = sqlite3
PATH = $dir/data/gitea.db

[repository]
ROOT = $dir/repositories
DEFAULT_BRANCH = main

[security]
INSTALL_LOCK = true
INTERNAL_TOKEN = $("$bin" generate secret INTERNAL_TOKEN)
SECRET_KEY = $("$bin" generate secret SECRET_KEY)

[oauth2]
JWT_SECRET = $("$bin" generate secret JWT_SECRET)

[service]
DISABLE_REGISTRATION = true

[actions]
ENABLED = true

[log]
MODE = file
LEVEL = Warn
ROOT_PATH = $dir/log
EOF
fi

echo "== migrate"
"$bin" migrate -c "$config" >/dev/null

env_file="$dir/gitea-test.env"
if [ ! -f "$env_file" ]; then
  echo "== users"
  {
    echo "GITEA_URL=$url"
    for user in intellij-dev intellij-reviewer; do
      key=$(echo "$user" | tr 'a-z-' 'A-Z_')
      password="Ci-$(openssl rand -hex 12)"
      "$bin" admin user create -c "$config" --username "$user" --password "$password" --email "$user@example.test" \
        --must-change-password=false >/dev/null
      token=$("$bin" admin user generate-access-token -c "$config" --username "$user" --token-name ci --scopes all --raw)
      echo "${key}_PASSWORD=$password"
      echo "${key}_TOKEN=$token"
    done
  } > "$env_file"
  chmod 600 "$env_file"
fi

echo "== start Gitea $version on $url"
nohup "$bin" web -c "$config" > "$dir/gitea.out" 2>&1 &
echo $! > "$dir/gitea.pid"
for _ in $(seq 1 60); do
  curl -fsS "$url/api/v1/version" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS "$url/api/v1/version"; echo

echo "== seed"
export GITEA_TEST_ENV="$env_file"
"$SCRIPT_DIR/seed-gitea.sh" >/dev/null
"$SCRIPT_DIR/seed-public.sh" >/dev/null
"$SCRIPT_DIR/seed-pr-list.sh" >/dev/null
echo "== ready: GITEA_TEST_ENV=$env_file"
