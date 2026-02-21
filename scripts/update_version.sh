#!/bin/bash
#
# Usage: ./scripts/update_version.sh <version>
#
# Examples:
#   ./scripts/update_version.sh 3.0.252
#   ./scripts/update_version.sh 3.0.252 --no-beta
#
# The versionCode is derived from the last segment of the version string.
# By default, "(beta)" is appended to the versionName. Use --no-beta to omit it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION_FILE="$PROJECT_ROOT/build_common/version_common.gradle"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <version> [--no-beta]"
    echo "  e.g. $0 3.0.252"
    exit 1
fi

VERSION="$1"
BETA=true

if [ "${2:-}" = "--no-beta" ]; then
    BETA=false
fi

# Validate version format (X.Y.Z)
if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "Error: Version must be in X.Y.Z format (e.g. 3.0.252)"
    exit 1
fi

# Extract versionCode from the last segment
VERSION_CODE="${VERSION##*.}"

# Build versionName
if [ "$BETA" = true ]; then
    VERSION_NAME="$VERSION (beta)"
else
    VERSION_NAME="$VERSION"
fi

# Update version_common.gradle
sed -i '' -E "s/versionCode = [0-9]+/versionCode = $VERSION_CODE/" "$VERSION_FILE"
sed -i '' -E "s/versionName = \".*\"/versionName = \"$VERSION_NAME\"/" "$VERSION_FILE"

echo "Updated version to:"
echo "  versionCode = $VERSION_CODE"
echo "  versionName = \"$VERSION_NAME\""
echo ""
echo "File updated: $VERSION_FILE"
