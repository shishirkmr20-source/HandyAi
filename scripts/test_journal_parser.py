#!/usr/bin/env python3
"""
Quick port of JournalIntentParser logic to validate the title/content
extraction on realistic chat inputs. This is NOT used by the app —
it's a sanity check that the Kotlin parser will produce clean output.
"""
import re

CREATE_TRIGGERS = [
    "add a journal", "add a journal entry", "add journal entry",
    "create a journal", "create a journal entry", "create journal entry",
    "write a journal", "write a journal entry", "write in my journal",
    "write in the journal", "write journal",
    "save a journal", "save a journal entry", "save journal",
    "save a journal note", "save a note in my journal",
    "journal entry:", "journal:",
    "new journal entry", "new journal",
    "log in my journal", "log a journal",
    "note in my journal", "make a journal entry", "make a journal"
]

LEADING_FILLER = [
    "please could you", "could you please", "can you please",
    "would you please", "could you", "can you", "would you",
    "please", "kindly", "just",
    "i want you to", "i want to", "i'd like to", "i would like to",
    "i'd like you to", "i want",
    "to remember that", "to note that", "to record that",
    "to say that", "saying that", "saying",
    "that says", "with the text", "with the content",
    "with the message", "with the note",
    "saying the following", "with the following",
    "about",
    "for me to", "for me",
    "that",
    "how",
    "to my journal saying", "in my journal saying",
    "to my journal that", "in my journal that",
    "to my journal", "in my journal",
    "saying that today", "saying that"
]

TRAILING_FILLER = [
    "please", "thanks", "thank you", "thx", "ty",
    "ok?", "okay?", "ok", "okay",
    "will you", "would you", "could you",
    "that would be great", "that would be nice",
    "if you can", "if you could", "if you don't mind",
    "i'd appreciate it", "i would appreciate it",
    "for me", "please?"
]

VERBISH_TAIL_WORDS = {
    "to", "for", "with", "about", "from", "into", "onto", "over",
    "is", "are", "was", "were", "be", "been", "being",
    "have", "has", "had", "do", "does", "did",
    "will", "would", "should", "could", "might", "must",
    "i", "you", "he", "she", "they", "we",
    "the", "a", "an", "and", "or", "but",
    "went", "go", "going", "want", "wanted", "need", "needed",
    "felt", "feel", "feeling", "got", "get", "getting"
}

def is_letter(ch):
    return ch is not None and ch.isalpha()

def strip_leading_filler(text):
    current = text.strip()
    for _ in range(10):
        changed = False
        lower = current.lower()
        for filler in sorted(LEADING_FILLER, key=len, reverse=True):
            if lower.startswith(filler):
                next_idx = len(filler)
                next_char = current[next_idx] if next_idx < len(current) else None
                is_boundary = next_char is None or not is_letter(next_char)
                if not is_boundary:
                    continue
                current = current[len(filler):]
                current = current.lstrip(',.:; -— "\'""')
                current = current.strip()
                changed = True
                break
        if not changed:
            break
    return current

def strip_trailing_filler(text):
    current = text.strip()
    for _ in range(5):
        changed = False
        lower = current.lower()
        for filler in sorted(TRAILING_FILLER, key=len, reverse=True):
            if lower.endswith(filler):
                current = current[:len(current)-len(filler)]
                current = current.rstrip(',.:; -— ""\'?!')
                current = current.strip()
                changed = True
                break
        if not changed:
            break
    return current

def try_explicit_title_split(text):
    seps = [':', ' - ', ' — ', ' – ', ' | ']
    for sep in seps:
        idx = text.find(sep)
        if idx <= 0: continue
        before = text[:idx].strip()
        after = text[idx+len(sep):].strip()
        if not before or not after: continue
        if len(before) > 60: continue
        if '.' in before: continue
        last_word = re.split(r'\s+', before)[-1].lower().rstrip(',.:;')
        if last_word in VERBISH_TAIL_WORDS: continue
        return before, after
    return "", text

def auto_title_from_content(content):
    text = content.strip()
    if not text: return ""
    sentence_end = -1
    for ch in '.!?':
        i = text.find(ch)
        if 1 <= i <= 80:
            if sentence_end == -1 or i < sentence_end:
                sentence_end = i
    if sentence_end > 0:
        candidate = text[:sentence_end].strip()
    else:
        words = re.split(r'\s+', text)
        taken = []
        for w in words:
            if len(taken) >= 6: break
            cleaned = w.rstrip(',;')
            if cleaned != w and cleaned:
                taken.append(cleaned)
                break
            if w.startswith(',') or w.startswith(';'): break
            taken.append(w)
        candidate = ' '.join(taken)
    if len(candidate) <= 50:
        truncated = candidate
    else:
        cut = candidate[:50]
        last_space = cut.rfind(' ')
        truncated = cut[:last_space] if last_space > 20 else cut
    truncated = truncated.rstrip(',.:; -—').strip()
    if not truncated: return ""
    return truncated[0].upper() + truncated[1:]

def clean_title(raw):
    t = raw.strip().strip('"\'""').strip().rstrip(':,;').strip()
    if t:
        t = t[0].upper() + t[1:]
    return t

def parse(message):
    lower = message.lower().strip()
    if not lower: return None
    matched_trigger = None
    match_idx = 10**9
    for trigger in sorted(CREATE_TRIGGERS, key=len, reverse=True):
        idx = lower.find(trigger)
        if idx >= 0 and idx < match_idx:
            match_idx = idx
            matched_trigger = trigger
    if matched_trigger is None: return None
    after = message[match_idx + len(matched_trigger):].strip()
    # Mimic Kotlin trimStart(':', '-', '—', ' ', '"', '\'') — strips ANY of these chars from start
    after = after.lstrip(': -— "\'').strip()
    if not after: return None
    after = strip_leading_filler(after)
    after = strip_trailing_filler(after)
    if not after: return None
    title, content = try_explicit_title_split(after)
    if title and content:
        return (clean_title(title)[:120], content.strip()[:4000], None)
    elif content:
        auto = auto_title_from_content(content)
        return (auto[:120], content.strip()[:4000], None)
    elif title:
        return (clean_title(title)[:120], "", None)
    return None

# ── Test cases ────────────────────────────────────────────────────────
TESTS = [
    ("Add a journal entry: today was a good day, I finished my project",
     "today was a good day", None),
    ("Add a journal entry about how I'm feeling grateful for my family today",
     None, "I'm feeling grateful"),
    ("Can you add a journal entry for me, today was a good day",
     "today was a good day", None),
    ("Please write in my journal that I'm feeling anxious about the presentation",
     None, "I'm feeling anxious"),
    ("Journal: I went for a run this morning and felt great",
     None, "I went for a run"),
    ("Save a journal note: feeling anxious about the presentation",
     "feeling anxious", None),
    ("I want to add a journal entry to remember that I had a productive day today",
     None, "I had a productive day"),
    ("Add a journal entry please, thanks",
     None, None),  # nothing meaningful after filler strip → null
    ("Add a journal entry",
     None, None),  # nothing after trigger → null
    ("Add a journal entry: Meeting notes - discussed Q3 roadmap with the team",
     "Meeting notes", "discussed Q3 roadmap"),
    # Additional edge cases
    ("Hey add a journal entry: I finally beat that boss after 30 tries",
     "I finally beat", None),
    ("Could you add a journal entry, I'm feeling great today",
     None, "I'm feeling great"),
    ("Add a journal entry about my morning routine",
     None, "my morning routine"),
    ("Write in journal: Gratitude - thankful for family and health",
     "Gratitude", "thankful for family"),
]

print("=== JournalIntentParser test cases ===\n")
passed = 0
failed = 0
for i, (msg, exp_title, exp_content) in enumerate(TESTS, 1):
    result = parse(msg)
    print(f"Test {i}: {msg!r}")
    if result is None:
        print(f"  -> null (no entry created)")
        if exp_title is None and exp_content is None:
            print(f"  ✓ (expected: nothing to save)")
            passed += 1
        else:
            print(f"  ✗ UNEXPECTED null")
            failed += 1
    else:
        title, content, mood = result
        print(f"  -> title={title!r}")
        print(f"     content={content!r}")
        ok = True
        if exp_title is not None:
            if exp_title.lower() in title.lower():
                print(f"  ✓ title contains expected substring {exp_title!r}")
            else:
                print(f"  ✗ title does NOT contain {exp_title!r}")
                ok = False
        if exp_content is not None:
            if exp_content.lower() in content.lower():
                print(f"  ✓ content contains expected substring {exp_content!r}")
            else:
                print(f"  ✗ content does NOT contain {exp_content!r}")
                ok = False
        # Sanity: title shouldn't start with filler words
        bad_starts = ('about ', 'for me', 'how ', 'that ', 'please', 'saying', 'with ')
        if title.lower().startswith(bad_starts):
            print(f"  ✗ title starts with filler word!")
            ok = False
        if ok:
            passed += 1
        else:
            failed += 1
    print()

print(f"=== Results: {passed} passed, {failed} failed ===")
