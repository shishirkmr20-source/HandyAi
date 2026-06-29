#!/bin/bash
# Create a clean zip of the handyai source code (excluding build artifacts).
set -e

SRC_DIR="/home/z/my-project/handyai"
OUT_ZIP="/tmp/my-project/download/HandyAi-v1.2.9-source.zip"

# Remove .git directory and any build artifacts before zipping
rm -rf "$SRC_DIR/.git"
rm -rf "$SRC_DIR/.gradle"
rm -rf "$SRC_DIR/.idea"
rm -rf "$SRC_DIR/.cxx"
find "$SRC_DIR" -type d -name "build" -prune -exec rm -rf {} +
find "$SRC_DIR" -type d -name ".gradle" -prune -exec rm -rf {} +
find "$SRC_DIR" -type d -name ".idea" -prune -exec rm -rf {} +
find "$SRC_DIR" -type f -name "*.iml" -delete
find "$SRC_DIR" -type f -name "local.properties" -delete

# Create the zip
rm -f "$OUT_ZIP"
cd "$(dirname "$SRC_DIR")"
zip -r -q "$OUT_ZIP" "$(basename "$SRC_DIR")"

ls -lh "$OUT_ZIP"
