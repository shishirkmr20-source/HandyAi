#!/bin/bash
# Upload to catbox.moe (200 MB limit, permanent storage)
set -e
FILE_PATH="$1"
FILE_SIZE=$(stat -c%s "$FILE_PATH")
FILE_SIZE_MB=$((FILE_SIZE / 1024 / 1024))
echo "Uploading: $FILE_PATH ($FILE_SIZE_MB MB) to catbox.moe" >&2

# Mimic a real browser request
RESPONSE=$(curl -s -X POST \
    -A "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0" \
    -H "Referer: https://catbox.moe/" \
    -H "Origin: https://catbox.moe" \
    -H "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" \
    -H "Accept-Language: en-US,en;q=0.5" \
    -F "reqtype=fileupload" \
    -F "fileToUpload=@$FILE_PATH" \
    "https://catbox.moe/user/api.php")

echo "$RESPONSE"
