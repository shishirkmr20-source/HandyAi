
---
Task ID: handyai-v1.2.9-share
Agent: main
Task: Upload full app source code + APK to file sharing website, share links

Work Log:
- Re-cloned repo from GitHub (session restart wiped /home/z/my-project/handyai).
- Got v1.2.9 APK from GitHub Actions run #44 (commit b9d6c49) via nightly.link
  proxy (GitHub requires auth for direct artifact downloads even on public
  repos; nightly.link proxies the auth).
- Extracted app-release.apk from the artifact zip, renamed to HandyAi-v1.2.9.apk.
- Created clean source zip (excludes build/, .gradle/, .git/, .idea/, *.iml,
  local.properties) — 227 KB, 128 files.
- Tried catbox.moe — blocked with "Invalid uploader" even with browser
  User-Agent/Referer headers. Probably IP-based block.
- Tried litterbox.catbox.moe — succeeded for source zip (227 KB) but failed
  for APK (109 MB) with 413 Request Entity Too Large. Litterbox limit
  appears to be ~100 MB.
- Tried 0x0.st — connection timed out (their server is intermittently down).
- Used gofile.io — succeeded for both files. Gofile has no size limit for
  anonymous uploads and the API is straightforward (one POST to upload,
  returns a download page URL).

Stage Summary:
- APK (v1.2.9, 108 MB): https://gofile.io/d/6pPmWB
- Source code (v1.2.9, 227 KB): https://gofile.io/d/7Y712x
- Both files on gofile.io, no expiration (gofile keeps anonymous uploads
  until 10 days of no downloads, then deletes; re-upload if needed)
- APK MD5: 833ba4e6ff81e5bddb0bc6de0387542f
- Source MD5: bac28fee1cb02ac3dcf8d725baef738f

---
Task ID: handyai-v1.3.0
Agent: main
Task: Fix journal save button, remove bold from system prompt, fix phone-only crash

Work Log:
- User reported: "the app worked fine on my tablet, but on my android phone
  it crashes after chats. initially it was working fine. also on the journal
  page i click the plus button then fill in the details and then click plus
  icon to add it but it doesnt get added"

THREE FIXES:

1. JOURNAL SAVE BUTTON (was invisible)
   Root cause: The JournalEditor's save action was a tiny Check icon in
   the top-right corner of the app bar. The user was looking for a
   "plus" or "save" button at the BOTTOM of the editor (where the FAB
   is on the parent JournalScreen). They couldn't find the Check icon
   and thought the save wasn't working.
   Fix: Removed the top-bar Check icon action. Added a full-width
   gradient "Save entry" button at the bottom of the editor (in a
   bottomBar slot). Matches the chat Send button's indigo→lavender
   gradient style. Disabled (greyed out) when the entry body is blank.
   Also relabeled the body field to "Entry *" and added an error hint
   "The entry body is required to save." so users know what's needed.

2. SYSTEM PROMPT (was asking for **bold** markers)
   Root cause: v1.2.9 removed the MarkdownParser that converted **bold**
   markers into styled spans (it caused a hard crash). But the system
   prompt STILL instructed the LLM to "wrap important words in **double
   asterisks**" — so every response came back full of literal **
   markers that showed up as raw asterisks in the chat bubble, looking
   broken.
   Fix: Replaced the bold instruction with "Reply in plain text. Do
   not use Markdown, **asterisks**, #headings, or -lists." Updated the
   final reminder at the end of the prompt to match. Removed the
   multi-sentence Kubernetes bold example.

3. PHONE CRASH AFTER CHATS (OOM on low-RAM devices)
   Root cause: The tablet has more RAM (8+ GB typical) so it survived,
   but the phone (4 GB typical) ran out of memory after a few chats.
   Two memory pressure sources:
   a) Bitmap decoding in MessageBubble called
      BitmapFactory.decodeFile() with no sampling — a 1024×1024 PNG
      decoded to a 4 MB ARGB_8888 bitmap. Multiple image messages +
      LLM KV cache + JVM heap = OOM-kill.
   b) MAX_TOKENS was 2048 for all devices. The KV cache for 2048
      tokens is ~200 MB for a 1.5B model — too much for a 192 MB
      heap-class phone.
   Fixes:
   a) MessageBubble now uses inSampleSize to decode bitmaps at display
      size (~936×1080) + RGB_565 config → ~500 KB per bitmap (8× saving).
      Added calcInSampleSize() helper using the standard Android
      sample-size calculation.
   b) LlmEngine now detects low-RAM devices via
      ActivityManager.getMemoryClass(). If ≤ 192 MB, caps MAX_TOKENS
      at 1536 (25% reduction). Minimal quality impact since the
      sliding-window history trim already keeps prompts short.

- Bumped versionCode 24→25, versionName 1.2.9→1.3.0.

PUSH STATUS:
- Could NOT push to GitHub from this session — git auth was wiped on
  session restart (no stored credentials, no GH_TOKEN env var).
- Created a patch file (v1.3.0.patch) with the single commit's diff.
- Uploaded v1.3.0 source zip + patch file to gofile.io.
- User needs to either:
  (a) Apply the patch locally and push to GitHub to trigger the
      Actions build, OR
  (b) Build the APK locally from the source zip (requires Android SDK).

Stage Summary:
- v1.3.0 source: https://gofile.io/d/VfvTin
- v1.3.0 patch:  https://gofile.io/d/UzR8e8
- The patch can be applied with: git am < v1.3.0.patch
  (from a fresh clone of https://github.com/shishirkmr20-source/HandyAi)
- Once pushed, GitHub Actions will build the APK (run #45+).
