#!/bin/bash
set -e
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
"$PROJECT_ROOT/packaging/scripts/build-macos.sh"
