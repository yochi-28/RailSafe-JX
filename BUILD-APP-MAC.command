#!/bin/bash
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
"$PROJECT_ROOT/packaging/scripts/build-macos.sh"
printf '\nBuild finished. Press Return to close this window.'
read -r _
