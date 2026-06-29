#!/bin/bash
# Upload a file to catbox.moe and print the resulting URL.
# Usage: upload_to_catbox.sh <file_path>
set -e

FILE_PATH="$1"
if [ ! -f "$FILE_PATH" ]; then
    echo "ERROR: File not found: $FILE_PATH" >&2
    exit 1
fi

FILE_SIZE=$(stat -c%s "$FILE_PATH")
FILE_SIZE_MB=$((FILE_SIZE / 1024 / 1024))
echo "Uploading: $FILE_PATH ($FILE_SIZE_MB MB)" >&2

# catbox.moe user API. Needs a User-Agent header.
RESPONSE=$(curl -s -X POST \
    -A "HandyAi-Release-Uploader/1.0 (contact: dev@handyai.local)" \
    -F "reqtype=fileupload" \
    -F "fileToUpload=@$FILE_PATH" \
    "https://catbox.moe/user/api.php")

if [[ "$RESPONSE" =~ ^https://files\.catbox\.moe/ ]]; then
    echo "$RESPONSE"
    exit 0
else
    echo "ERROR: Upload failed. Response from catbox.moe:" >&2
    echo "$RESPONSE" >&2
    exit 1
fi
