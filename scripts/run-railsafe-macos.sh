#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if [[ ! -f pom.xml || ! -f mvnw ]]; then
  echo "Error: project files pom.xml and mvnw were not found." >&2
  exit 1
fi
chmod +x ./mvnw

# Explicit user-supplied JDBC settings take precedence. Otherwise, bootstrap and use
# the project's local MySQL database automatically.
if [[ -n "${RAILSAFE_DB_URL:-}" ]]; then
  echo "RailSafe-JX: using the explicitly configured JDBC database."
else
  chmod +x "$PROJECT_ROOT/scripts/setup-mysql-macos.sh"
  "$PROJECT_ROOT/scripts/setup-mysql-macos.sh"
  CONFIG_FILE="${HOME}/.railsafe-jx/mysql.properties"
  read_property() {
    local key="$1" line=""
    line="$(/usr/bin/grep -m 1 "^${key}=" "$CONFIG_FILE" || true)"
    printf '%s' "${line#*=}"
  }
  export RAILSAFE_DB_URL="$(read_property url)"
  export RAILSAFE_DB_USER="$(read_property user)"
  export RAILSAFE_DB_PASSWORD="$(read_property password)"
  if [[ -z "$RAILSAFE_DB_URL" || -z "$RAILSAFE_DB_USER" || -z "$RAILSAFE_DB_PASSWORD" ]]; then
    echo "Local MySQL credentials are missing or incomplete in $CONFIG_FILE" >&2
    exit 1
  fi
  echo "RailSafe-JX: launching with local MySQL database railsafe_jx."
fi

exec ./mvnw javafx:run
