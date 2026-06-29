#!/bin/bash
# Upload a file to litter.catbox.moe (temporary storage, 1h-72h)
# Usage: upload_to_litterbox.sh <file_path> <time: 1h/12h/24h/72h>
set -e

FILE_PATH="$1"
TIME="${2:-72h}"
if [ ! -f "$FILE_PATH" ]; then
    echo "ERROR: File not found: $FILE_PATH" >&2
    exit 1
fi

FILE_SIZE=$(stat -c%s "$FILE_PATH")
FILE_SIZE_MB=$((FILE_SIZE / 1024 / 1024))
echo "Uploading: $FILE_PATH ($FILE_SIZE_MB MB) for $TIME" >&2

RESPONSE=$(curl -s -X POST \
    -A "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0" \
    -H "Referer: https://litter.catbox.moe/" \
    -F "reqtype=fileupload" \
    -F "time=$TIME" \
    -F "fileToUpload=@$FILE_PATH" \
    "https://litter.catbox.moe/resources/internals/api.php")

if [[ "$RESPONSE" =~ ^https://litterbox\.catbox\.moe/ ]]; then
    echo "$RESPONSE"
    exit 0
else
    echo "ERROR: Upload failed. Response:" >&2
    echo "$RESPONSE" >&2
    exit 1
fi
