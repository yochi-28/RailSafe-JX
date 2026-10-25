#!/bin/sh
# Lightweight project-local Maven launcher. Downloads Maven 3.9.9 if it is not installed.
set -eu
BASE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MAVEN_VERSION="3.9.9"
if command -v mvn >/dev/null 2>&1; then
  exec mvn -f "${BASE_DIR}/pom.xml" "$@"
fi
WRAPPER_DIR="${HOME}/.m2/wrapper/dists/apache-maven-${MAVEN_VERSION}"
MAVEN_HOME="${WRAPPER_DIR}/apache-maven-${MAVEN_VERSION}"
if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
  mkdir -p "${WRAPPER_DIR}"
  ARCHIVE="${WRAPPER_DIR}/maven.zip"
  if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
    echo 'curl and unzip are required to download Maven.' >&2; exit 1
  fi
  curl -fL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.zip" -o "${ARCHIVE}"
  unzip -q -o "${ARCHIVE}" -d "${WRAPPER_DIR}"
  rm -f "${ARCHIVE}"
fi
exec "${MAVEN_HOME}/bin/mvn" -f "${BASE_DIR}/pom.xml" "$@"
