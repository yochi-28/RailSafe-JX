#!/usr/bin/env bash
set -euo pipefail

# Build a native .app image and a distributable .dmg on macOS.
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_ROOT"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This builder must run on macOS. jpackage does not cross-build macOS apps." >&2
  exit 2
fi
if ! command -v jpackage >/dev/null 2>&1 || ! command -v jdeps >/dev/null 2>&1; then
  echo "JDK 21 (including jpackage/jdeps) is required. Install a full JDK 21 and set JAVA_HOME." >&2
  exit 2
fi
if [[ "${JAVA_HOME:-}" != "" && -x "$JAVA_HOME/bin/jpackage" ]]; then JPACKAGE="$JAVA_HOME/bin/jpackage"; else JPACKAGE="$(command -v jpackage)"; fi

VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -n 1)"
APP_JAR="railsafe-jx-${VERSION}.jar"
INPUT="$PROJECT_ROOT/target/installer-input"
DIST="$PROJECT_ROOT/dist/macos"
ICON_PNG="$PROJECT_ROOT/src/main/resources/railsafe-icon.png"
ICONSET="$PROJECT_ROOT/target/railsafe.iconset"
ICON_ICNS="$PROJECT_ROOT/packaging/icons/railsafe.icns"

if [[ ! -f "$ICON_PNG" ]]; then echo "Missing app icon: $ICON_PNG" >&2; exit 2; fi

# Package the platform-specific JavaFX natives resolved by Maven on this Mac.
# Invoke the Maven wrapper through bash so executable-bit metadata is not required.
bash ./mvnw clean package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$INPUT/lib"
mkdir -p "$INPUT" "$DIST"
cp "$PROJECT_ROOT/target/$APP_JAR" "$INPUT/$APP_JAR"

# jpackage accepts a main JAR (not a --class-path option). Add a relative
# Class-Path manifest entry so JavaFX and JDBC dependency jars load from app/lib.
CLASSPATH_ENTRIES=()
for dependency_jar in "$INPUT"/lib/*.jar; do
  [[ -f "$dependency_jar" ]] || continue
  CLASSPATH_ENTRIES+=("lib/$(basename "$dependency_jar")")
done
if [[ ${#CLASSPATH_ENTRIES[@]} -eq 0 ]]; then echo "No runtime dependency jars were copied into $INPUT/lib" >&2; exit 2; fi
MANIFEST="$PROJECT_ROOT/target/installer-manifest.mf"
# Manifest physical lines must be folded to 72 bytes maximum. Dependency paths
# have ASCII names and are folded only at spaces to keep each URI token intact.
{
  printf 'Class-Path:'
  line_length=11
  entry_count=0
  for entry in "${CLASSPATH_ENTRIES[@]}"; do
    entry_length=${#entry}
    if (( line_length + 1 + entry_length > 69 )); then
      if (( entry_count == 0 )); then
        echo "Dependency path is too long for a manifest entry: $entry" >&2
        exit 2
      fi
      # Keep one separating space on the previous line; the leading space on
      # the continuation line is removed by the JAR manifest parser.
      printf ' \r\n '
      line_length=1
    else
      printf ' '
      line_length=$((line_length + 1))
    fi
    printf '%s' "$entry"
    line_length=$((line_length + entry_length))
    entry_count=$((entry_count + 1))
  done
  printf '\r\n\r\n'
} > "$MANIFEST"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then
  JAR_TOOL="$JAVA_HOME/bin/jar"
else
  JAR_TOOL="$(command -v jar)"
fi
"$JAR_TOOL" --update --file "$INPUT/$APP_JAR" --manifest "$MANIFEST"

# macOS .icns requires a native iconset. iconutil ships with macOS.
if ! command -v iconutil >/dev/null 2>&1 || ! command -v sips >/dev/null 2>&1; then
  echo "Apple icon tools (sips/iconutil) are missing. Install Xcode Command Line Tools: xcode-select --install" >&2
  exit 2
fi
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
make_icon() { local px="$1" name="$2"; sips -z "$px" "$px" "$ICON_PNG" --out "$ICONSET/$name" >/dev/null; }
make_icon 16 icon_16x16.png
make_icon 32 icon_16x16@2x.png
make_icon 32 icon_32x32.png
make_icon 64 icon_32x32@2x.png
make_icon 128 icon_128x128.png
make_icon 256 icon_128x128@2x.png
make_icon 256 icon_256x256.png
make_icon 512 icon_256x256@2x.png
make_icon 512 icon_512x512.png
make_icon 1024 icon_512x512@2x.png
iconutil -c icns "$ICONSET" -o "$ICON_ICNS"

rm -rf "$DIST/portable" "$DIST/installer"
mkdir -p "$DIST/portable" "$DIST/installer"
COMMON=(--name "RailSafe-JX" --app-version "$VERSION" --vendor "RailSafe-JX" \
  --description "Concurrent railway simulation and deadlock resolution demonstrator" \
  --input "$INPUT" --main-jar "$APP_JAR" --main-class "com.railsafe.jx.RailSafeLauncher" \
  --icon "$ICON_ICNS" --java-options "-Dfile.encoding=UTF-8")

"$JPACKAGE" --type app-image "${COMMON[@]}" --dest "$DIST/portable"
"$JPACKAGE" --type dmg "${COMMON[@]}" \
  --mac-package-identifier "com.railsafe.jx" --mac-package-name "RailSafe-JX" \
  --dest "$DIST/installer"

echo "Build complete:"
echo "  Portable app: $DIST/portable/RailSafe-JX.app"
echo "  Installer:    $DIST/installer/RailSafe-JX-${VERSION}.dmg"
echo "Database note: first run uses saved local MySQL settings if present; otherwise the app uses its embedded H2 fallback. For automatic MySQL setup, run scripts/run-railsafe-macos.sh once from the source project."
