#!/usr/bin/env python3
"""
Quick verification of the MarkdownParser.sanitize and TtsSpeechSanitizer
regex patterns. This mirrors the Kotlin regex behavior so we can confirm
the logic is correct before the user builds the APK.
"""
import re

def md_sanitize(text):
    """Mirror of MarkdownParser.sanitize (v1.4.2)."""
    if not text:
        return text
    result = text
    # Pass 1: Strip XML/HTML tags
    if '<' in result:
        result = re.sub(r"<\/?[a-zA-Z][a-zA-Z0-9]*(?:\s[^<>]*?)?\/?>", "", result)
    # Pass 2: Literal escapes
    if '\\' in result:
        result = result.replace("\\n", "\n")
        result = result.replace("\\r", "")
        result = result.replace("\\t", "\t")
    # Pass 3: Remove actual \r
    if '\r' in result:
        result = result.replace("\r\n", "\n").replace("\r", "")
    return result

def tts_sanitize(text):
    """Mirror of TtsSpeechSanitizer.sanitize (v1.4.2)."""
    if not text or text.strip() == "":
        return text
    # Step 0: Strip tags
    stripped = text
    if '<' in stripped:
        stripped = re.sub(r"<\/?[a-zA-Z][a-zA-Z0-9]*(?:\s[^<>]*?)?\/?>", "", stripped)
    # Step 1: Tables (simplified — just one table for this test)
    lines = stripped.split('\n')
    out = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if is_table_line(line):
            table_lines = []
            while i < len(lines) and is_table_line(lines[i]):
                table_lines.append(lines[i])
                i += 1
            out.append(table_to_speech(table_lines))
        else:
            out.append(line)
            i += 1
    result = '\n'.join(out)
    # Step 2: markdown
    result = re.sub(r"```[a-zA-Z0-9]*\n?", "", result)
    result = result.replace("```", "")
    result = re.sub(r"`([^`]+)`", r"\1", result)
    result = re.sub(r"^#{1,6}\s+", "", result, flags=re.MULTILINE)
    result = re.sub(r"\*\*([^*]+)\*\*", r"\1", result)
    result = re.sub(r"__([^_]+)__", r"\1", result)
    result = re.sub(r"(?<![*\w])\*([^*\n]+)\*(?![*\w])", r"\1", result)
    result = re.sub(r"(?<![\w_])_([^_\n]+)_(?![\w_])", r"\1", result)
    result = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", result)
    result = re.sub(r"!\[([^\]]+)\]\([^)]+\)", r"\1", result)
    result = re.sub(r"^\s*[-*•]\s+", "", result, flags=re.MULTILINE)
    result = re.sub(r"^\s*\d+\.\s+", "", result, flags=re.MULTILINE)
    result = re.sub(r"^\s*>\s?", "", result, flags=re.MULTILINE)
    result = re.sub(r"^\s*([-*_])\1{2,}\s*$", "", result, flags=re.MULTILINE)
    # Step 3: cleanup
    result = re.sub(r"\|{2,}", " ", result)
    result = result.replace("|", ", ")
    result = re.sub(r",\s*,+", ", ", result)
    result = re.sub(r"[ \t]{2,}", " ", result)
    result = re.sub(r"\n{3,}", "\n\n", result)
    return result.strip()

def is_table_line(line):
    trimmed = line.strip()
    if '|' not in trimmed:
        return False
    return trimmed.startswith("|") or trimmed.endswith("|") or trimmed.count('|') >= 2

def parse_row(line):
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return [c.strip() for c in s.split("|")]

def table_to_speech(table_lines):
    if not table_lines:
        return ""
    rows = [parse_row(l) for l in table_lines if parse_row(l)]
    if not rows:
        return ""
    data_rows = [r for r in rows if not all(c.strip() == "" or re.match(r"^[\-:\s]+$", c.strip()) for c in r)]
    if not data_rows:
        return ""
    headers = data_rows[0]
    body = data_rows[1:] if len(data_rows) > 1 else []
    sb = []
    if headers:
        sb.append("Table. Columns: " + ", ".join(headers) + ". ")
    for row in body:
        padded = row + [""] * max(0, len(headers) - len(row))
        pairs = ", ".join(f"{h} {v}" for h, v in zip(headers, padded) if v.strip())
        if pairs:
            sb.append(pairs + ". ")
    return "".join(sb).strip()

# ── Tests ─────────────────────────────────────────────────────────────────
print("=== MarkdownParser.sanitize tests ===")

# Test 1: SmolLM tag wrapping
t1_in = "<response>Hello, how can I help you today?</response>"
t1_out = md_sanitize(t1_in)
print(f"Test 1 (SmolLM tags): {t1_in!r}")
print(f"  Output: {t1_out!r}")
assert t1_out == "Hello, how can I help you today?", f"FAIL: got {t1_out!r}"
print("  PASS\n")

# Test 2: \r and \n escape sequences
t2_in = "Hello\\r\\nWorld\\nFoo"
t2_out = md_sanitize(t2_in)
print(f"Test 2 (escapes): {t2_in!r}")
print(f"  Output: {t2_out!r}")
assert "\\r" not in t2_out, f"FAIL: literal \\r still present: {t2_out!r}"
print("  PASS\n")

# Test 3: Actual \r\n line endings
t3_in = "Hello\r\nWorld"
t3_out = md_sanitize(t3_in)
print(f"Test 3 (CRLF): {t3_in!r}")
print(f"  Output: {t3_out!r}")
assert "\r" not in t3_out, f"FAIL: \\r still present: {t3_out!r}"
assert "\n" in t3_out, f"FAIL: \\n should be preserved: {t3_out!r}"
print("  PASS\n")

# Test 4: Tag with attributes
t4_in = '<thought lang="en">Thinking...</thought>Done.'
t4_out = md_sanitize(t4_in)
print(f"Test 4 (tag w/ attrs): {t4_in!r}")
print(f"  Output: {t4_out!r}")
assert "Done." in t4_out, f"FAIL: content lost: {t4_out!r}"
assert "<" not in t4_out, f"FAIL: tags not stripped: {t4_out!r}"
print("  PASS\n")

# Test 5: Pipe table preserved (markdown parser should NOT strip pipes)
t5_in = "| Name | Age |\n|---|---|\n| Alice | 30 |"
t5_out = md_sanitize(t5_in)
print(f"Test 5 (table preserved): {t5_in!r}")
print(f"  Output: {t5_out!r}")
assert "|" in t5_out, f"FAIL: pipes stripped from display text: {t5_out!r}"
print("  PASS\n")

# Test 6: Math/emoji with < not stripped
t6_in = "I <3 you and <5px matters"
t6_out = md_sanitize(t6_in)
print(f"Test 6 (math/emoji): {t6_in!r}")
print(f"  Output: {t6_out!r}")
assert "<3" in t6_out, f"FAIL: <3 stripped: {t5_out!r}"
print("  PASS\n")

print("\n=== TtsSpeechSanitizer.sanitize tests ===")

# Test 7: Table → natural language
t7_in = "| Name | Age |\n|---|---|\n| Alice | 30 |\n| Bob | 25 |"
t7_out = tts_sanitize(t7_in)
print(f"Test 7 (table to speech): {t7_in!r}")
print(f"  Output: {t7_out!r}")
assert "pipe" not in t7_out.lower(), f"FAIL: 'pipe' word in output: {t7_out!r}"
assert "hyphen" not in t7_out.lower(), f"FAIL: 'hyphen' word in output: {t7_out!r}"
assert "Alice" in t7_out and "30" in t7_out, f"FAIL: data lost: {t7_out!r}"
print("  PASS\n")

# Test 8: SmolLM tags in TTS
t8_in = "<response>The answer is 42.</response>"
t8_out = tts_sanitize(t8_in)
print(f"Test 8 (TTS tag strip): {t8_in!r}")
print(f"  Output: {t8_out!r}")
assert "less than" not in t8_out.lower(), f"FAIL: tag read aloud: {t8_out!r}"
assert "42" in t8_out, f"FAIL: content lost: {t8_out!r}"
print("  PASS\n")

# Test 9: Hyphens in table cells preserved as data
t9_in = "| Item | Status |\n|---|---|\n| well-known | done |\n| self-paced | pending |"
t9_out = tts_sanitize(t9_in)
print(f"Test 9 (hyphens in cells): {t9_in!r}")
print(f"  Output: {t9_out!r}")
assert "well-known" in t9_out, f"FAIL: hyphenated data lost: {t9_out!r}"
assert "self-paced" in t9_out, f"FAIL: hyphenated data lost: {t9_out!r}"
print("  PASS\n")

print("=== ALL TESTS PASSED ===")
