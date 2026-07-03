#!/usr/bin/env python3
"""Eval harness for the TypeFix correction prompt.

Reads the canonical system prompt from prompt/system-prompt.txt (the single
source of truth shared by the macOS and Android apps), runs every case in
cases.json against the Anthropic API, and grades the output against the expected
correction (case/punctuation-insensitive so we judge whether the WORDS are
right, not exact formatting).

Usage:
  ANTHROPIC_API_KEY=... python3 eval/run.py
Optional env: MODEL (default claude-sonnet-4-6), WORKERS (default 8).
"""
import os
import re
import sys
import json
import urllib.request
import concurrent.futures

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PROMPT_TXT = os.path.join(ROOT, "prompt", "system-prompt.txt")

KEY = os.environ.get("ANTHROPIC_API_KEY", "")
MODEL = os.environ.get("MODEL", "claude-sonnet-4-6")
WORKERS = int(os.environ.get("WORKERS", "8"))


def load_prompt():
    try:
        with open(PROMPT_TXT, encoding="utf-8") as f:
            return f.read().rstrip("\n")
    except OSError as e:
        sys.exit(f"Could not read {PROMPT_TXT}: {e}")


PROMPT = load_prompt()


def correct(text):
    body = json.dumps({
        "model": MODEL,
        "max_tokens": 1024,
        "system": PROMPT,
        "messages": [{"role": "user", "content": text}],
    }).encode("utf-8")
    req = urllib.request.Request(
        "https://api.anthropic.com/v1/messages",
        data=body,
        headers={
            "x-api-key": KEY,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=90) as r:
        data = json.load(r)
    out = "".join(b.get("text", "") for b in data.get("content", []))
    return out.strip().strip('"').strip()


def norm(s):
    s = s.lower().replace("'", "").replace("\u2019", "")  # "let's" == "lets"
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def run_one(case):
    inp, exp = case["in"], case["out"]
    try:
        got = correct(inp)
    except Exception as e:  # noqa: BLE001
        return (inp, exp, f"ERROR: {e}", False)
    return (inp, exp, got, norm(got) == norm(exp))


def main():
    if not KEY:
        sys.exit("Set ANTHROPIC_API_KEY")
    cases = json.load(open(os.path.join(HERE, "cases.json"), encoding="utf-8"))
    results = [None] * len(cases)
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(run_one, c): i for i, c in enumerate(cases)}
        for fut in concurrent.futures.as_completed(futs):
            results[futs[fut]] = fut.result()

    passed = sum(1 for r in results if r[3])
    print(f"\nMODEL: {MODEL}")
    print(f"PASS: {passed}/{len(results)}\n")
    print("=== MISMATCHES (review for acceptability) ===\n")
    for inp, exp, got, ok in results:
        if not ok:
            print(f"IN : {inp}")
            print(f"EXP: {exp}")
            print(f"GOT: {got}\n")


if __name__ == "__main__":
    main()
