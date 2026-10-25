#!/usr/bin/env bash
set -euo pipefail

# One-time/idempotent local MySQL bootstrap for RailSafe-JX on macOS.
# It never resets or drops the railsafe_jx database.

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This setup script is intended for macOS." >&2
  exit 1
fi

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_DIR="${HOME}/.railsafe-jx"
CONFIG_FILE="${CONFIG_DIR}/mysql.properties"
DB_NAME="railsafe_jx"
APP_USER="railsafe_app"
ADMIN_USER="${RAILSAFE_MYSQL_ADMIN_USER:-root}"
ROOT_CLIENT_FILE=""
TEMP_CLIENT_FILE=""

cleanup() {
  [[ -z "$ROOT_CLIENT_FILE" ]] || rm -f "$ROOT_CLIENT_FILE"
  [[ -z "$TEMP_CLIENT_FILE" ]] || rm -f "$TEMP_CLIENT_FILE"
}
trap cleanup EXIT

mkdir -p "$CONFIG_DIR"
chmod 700 "$CONFIG_DIR"

BREW="$(command -v brew || true)"
if [[ -z "$BREW" ]]; then
  for candidate in /opt/homebrew/bin/brew /usr/local/bin/brew; do
    if [[ -x "$candidate" ]]; then BREW="$candidate"; break; fi
  done
fi
if [[ -z "$BREW" ]]; then
  cat >&2 <<'MSG'
Homebrew was not found. Install Homebrew first, then rerun this script:
https://brew.sh/
This script will then install/start MySQL and configure the RailSafe-JX database.
MSG
  exit 1
fi

MYSQL_FORMULA=""
for formula in mysql@8.4 mysql; do
  if "$BREW" list --versions "$formula" >/dev/null 2>&1; then
    MYSQL_FORMULA="$formula"
    break
  fi
done
if [[ -z "$MYSQL_FORMULA" ]]; then
  echo "RailSafe-JX: MySQL is not installed through Homebrew; installing mysql@8.4..."
  "$BREW" install mysql@8.4
  MYSQL_FORMULA="mysql@8.4"
fi

MYSQL_PREFIX="$("$BREW" --prefix "$MYSQL_FORMULA")"
MYSQL_BIN="${MYSQL_PREFIX}/bin/mysql"
MYSQLADMIN_BIN="${MYSQL_PREFIX}/bin/mysqladmin"
if [[ ! -x "$MYSQL_BIN" ]]; then
  echo "Could not find the MySQL client at: $MYSQL_BIN" >&2
  exit 1
fi

# Starting an already-running service is harmless; Homebrew manages the launch agent.
echo "RailSafe-JX: ensuring the local MySQL service is running..."
"$BREW" services start "$MYSQL_FORMULA" >/dev/null 2>&1 || true
SERVER_READY=0
for ((attempt = 0; attempt < 60; attempt++)); do
  if [[ -x "$MYSQLADMIN_BIN" ]] && "$MYSQLADMIN_BIN" --connect-timeout=2 -h 127.0.0.1 ping >/dev/null 2>&1; then
    SERVER_READY=1
    break
  fi
  sleep 1
done
if [[ "$SERVER_READY" -ne 1 ]]; then
  echo "MySQL did not become available on 127.0.0.1:3306." >&2
  echo "Check status with: $BREW services list | grep mysql" >&2
  exit 1
fi

read_property() {
  local key="$1"
  local line=""
  if [[ -f "$CONFIG_FILE" ]]; then
    line="$(/usr/bin/grep -m 1 "^${key}=" "$CONFIG_FILE" || true)"
  fi
  printf '%s' "${line#*=}"
}

make_client_file() {
  local username="$1"
  local password="$2"
  local host="${3:-127.0.0.1}"
  local escaped_password
  escaped_password="${password//\\/\\\\}"
  escaped_password="${escaped_password//\"/\\\"}"
  umask 077
  TEMP_CLIENT_FILE="$(mktemp "${TMPDIR:-/tmp}/railsafe-mysql-client.XXXXXX")"
  {
    printf '[client]\nuser=%s\n' "$username"
    printf 'password="%s"\n' "$escaped_password"
    printf 'host=%s\nport=3306\n' "$host"
  } > "$TEMP_CLIENT_FILE"
  chmod 600 "$TEMP_CLIENT_FILE"
}

# A previously set-up account should work without asking for the MySQL root password again.
SAVED_URL="$(read_property url)"
SAVED_USER="$(read_property user)"
SAVED_PASSWORD="$(read_property password)"
if [[ -n "$SAVED_URL" && -n "$SAVED_USER" && -n "$SAVED_PASSWORD" ]]; then
  make_client_file "$SAVED_USER" "$SAVED_PASSWORD"
  if "$MYSQL_BIN" --defaults-extra-file="$TEMP_CLIENT_FILE" --database="$DB_NAME" \
      --batch --skip-column-names --execute='SELECT 1' >/dev/null 2>&1; then
    echo "RailSafe-JX: local MySQL database and saved app credentials are ready."
    echo "Missing application tables are created automatically at startup."
    exit 0
  fi
  rm -f "$TEMP_CLIENT_FILE"
  TEMP_CLIENT_FILE=""
  echo "Saved RailSafe-JX MySQL credentials no longer work; repairing the local app account..."
fi

# First try a fresh Homebrew MySQL server, which typically lets the local root account
# connect without a password. If it doesn't, prompt for the administrator password securely.
if "$MYSQL_BIN" -u "$ADMIN_USER" --batch --skip-column-names --execute='SELECT 1' >/dev/null 2>&1; then
  ROOT_CLIENT_FILE=""
else
  printf 'Enter the local MySQL %s password (input hidden): ' "$ADMIN_USER"
  IFS= read -r -s ADMIN_PASSWORD
  printf '\n'
  escaped_admin_password="${ADMIN_PASSWORD//\\/\\\\}"
  escaped_admin_password="${escaped_admin_password//\"/\\\"}"
  umask 077
  ROOT_CLIENT_FILE="$(mktemp "${TMPDIR:-/tmp}/railsafe-mysql-admin.XXXXXX")"
  {
    printf '[client]\nuser=%s\n' "$ADMIN_USER"
    printf 'password="%s"\n' "$escaped_admin_password"
    printf 'host=127.0.0.1\nport=3306\n'
  } > "$ROOT_CLIENT_FILE"
  chmod 600 "$ROOT_CLIENT_FILE"
  unset ADMIN_PASSWORD escaped_admin_password
  if ! "$MYSQL_BIN" --defaults-extra-file="$ROOT_CLIENT_FILE" --batch --skip-column-names --execute='SELECT 1' >/dev/null 2>&1; then
    echo "MySQL administrator authentication failed. No project files were changed." >&2
    exit 1
  fi
fi

mysql_as_admin() {
  if [[ -n "$ROOT_CLIENT_FILE" ]]; then
    "$MYSQL_BIN" --defaults-extra-file="$ROOT_CLIENT_FILE" "$@"
  else
    "$MYSQL_BIN" -u "$ADMIN_USER" "$@"
  fi
}

# Determine whether this is a fresh database before applying the full, indexed schema.
DATABASE_ALREADY_EXISTS="$(mysql_as_admin --batch --skip-column-names --execute="SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='${DB_NAME}'")"

# An app-specific password is generated locally; the administrator password is never saved.
APP_PASSWORD="$(openssl rand -hex 24)"
mysql_as_admin --batch <<SQL
CREATE DATABASE IF NOT EXISTS ${DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${APP_USER}'@'localhost' IDENTIFIED BY '${APP_PASSWORD}';
ALTER USER '${APP_USER}'@'localhost' IDENTIFIED BY '${APP_PASSWORD}';
CREATE USER IF NOT EXISTS '${APP_USER}'@'127.0.0.1' IDENTIFIED BY '${APP_PASSWORD}';
ALTER USER '${APP_USER}'@'127.0.0.1' IDENTIFIED BY '${APP_PASSWORD}';
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${APP_USER}'@'localhost';
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${APP_USER}'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

# The password is random hex, so values are safe in Java properties and a MySQL option file.
CONFIG_TMP="${CONFIG_FILE}.tmp"
( umask 077; cat > "$CONFIG_TMP" <<EOF2
url=jdbc:mysql://127.0.0.1:3306/${DB_NAME}?serverTimezone=UTC&allowPublicKeyRetrieval=true
user=${APP_USER}
password=${APP_PASSWORD}
EOF2
)
chmod 600 "$CONFIG_TMP"
mv "$CONFIG_TMP" "$CONFIG_FILE"

# On a brand-new database, apply the checked-in schema as the administrator so the
# optional performance indexes are created too. Existing databases are never reset.
if [[ "$DATABASE_ALREADY_EXISTS" == "0" ]]; then
  mysql_as_admin < "$PROJECT_ROOT/database/schema.sql"
fi

rm -f "$TEMP_CLIENT_FILE"
TEMP_CLIENT_FILE=""
make_client_file "$APP_USER" "$APP_PASSWORD"
if ! "$MYSQL_BIN" --defaults-extra-file="$TEMP_CLIENT_FILE" --database="$DB_NAME" \
    --batch --skip-column-names --execute='SELECT 1' >/dev/null 2>&1; then
  echo "The RailSafe-JX MySQL account was created, but its connection test failed." >&2
  echo "Review the local MySQL service and rerun this script." >&2
  exit 1
fi

printf '\nRailSafe-JX local MySQL setup is complete.\n'
printf 'Database: %s\n' "$DB_NAME"
printf 'Application account: %s (localhost only)\n' "$APP_USER"
printf 'Credentials saved with owner-only permissions at: %s\n' "$CONFIG_FILE"
printf 'RailSafe-JX will create its required tables automatically on startup.\n'
