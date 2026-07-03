#!/usr/bin/env python3
"""Single-source the correction system prompt across platforms.

The canonical prompt lives in `prompt/system-prompt.txt`. This script renders it
into the Swift and Kotlin sources, replacing the block between the
`PROMPT-GEN:BEGIN` / `PROMPT-GEN:END` marker comments in each file. The generated
blocks stay checked in, so builds work even if this never runs; it only keeps the
two platforms from drifting.

Usage:
  python3 scripts/sync_prompt.py           # rewrite the source files in place
  python3 scripts/sync_prompt.py --check   # exit non-zero if anything is stale
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PROMPT_TXT = os.path.join(ROOT, "prompt", "system-prompt.txt")

SWIFT = os.path.join(ROOT, "Sources", "TypeFix", "CorrectionSupport.swift")
KOTLIN = os.path.join(
    ROOT, "android", "app", "src", "main", "java", "com", "typefix",
    "keyboard", "correction", "CorrectionText.kt",
)

GENERATED_NOTE = (
    "generated from prompt/system-prompt.txt by scripts/sync_prompt.py — do not edit by hand"
)

# Matches a whole marker region including the two marker comment lines, capturing
# the indentation so we can re-emit the markers at the same column.
REGION = re.compile(
    r"(?P<indent>[ \t]*)//[ \t]*PROMPT-GEN:BEGIN.*?\n"
    r".*?"
    r"(?P=indent)//[ \t]*PROMPT-GEN:END",
    re.DOTALL,
)


def load_prompt():
    with open(PROMPT_TXT, encoding="utf-8") as f:
        # Keep internal blank lines; drop only the trailing newline(s).
        return f.read().rstrip("\n")


def _indented(text, indent):
    """Indent every non-blank line by `indent`; leave blank lines empty."""
    return "\n".join((indent + line) if line else "" for line in text.split("\n"))


def swift_block(prompt, indent):
    body = _indented(prompt, indent)
    return (
        f'{indent}// PROMPT-GEN:BEGIN ({GENERATED_NOTE})\n'
        f'{indent}static let systemPrompt = """\n'
        f'{body}\n'
        f'{indent}"""\n'
        f'{indent}// PROMPT-GEN:END'
    )


def kotlin_block(prompt, indent):
    body = _indented(prompt, indent + "    ")
    return (
        f'{indent}// PROMPT-GEN:BEGIN ({GENERATED_NOTE})\n'
        f'{indent}val systemPrompt: String = """\n'
        f'{body}\n'
        f'{indent}""".trimIndent()\n'
        f'{indent}// PROMPT-GEN:END'
    )


def render(path, builder, prompt):
    with open(path, encoding="utf-8") as f:
        src = f.read()
    match = REGION.search(src)
    if not match:
        sys.exit(
            f"error: no PROMPT-GEN:BEGIN/END markers found in {os.path.relpath(path, ROOT)}"
        )
    indent = match.group("indent")
    return src[: match.start()] + builder(prompt, indent) + src[match.end():]


TARGETS = [
    (SWIFT, swift_block),
    (KOTLIN, kotlin_block),
]


def main():
    check = "--check" in sys.argv[1:]
    prompt = load_prompt()
    stale = []
    for path, builder in TARGETS:
        rendered = render(path, builder, prompt)
        with open(path, encoding="utf-8") as f:
            current = f.read()
        rel = os.path.relpath(path, ROOT)
        if rendered == current:
            continue
        if check:
            stale.append(rel)
        else:
            with open(path, "w", encoding="utf-8") as f:
                f.write(rendered)
            print(f"updated {rel}")

    if check:
        if stale:
            print("Prompt is out of sync in:")
            for rel in stale:
                print(f"  {rel}")
            print("Run: python3 scripts/sync_prompt.py")
            sys.exit(1)
        print("Prompt in sync.")


if __name__ == "__main__":
    main()
