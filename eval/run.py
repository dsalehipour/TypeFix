#!/usr/bin/env python3
"""Eval harness for the TypeFix correction prompt.

Extracts the live system prompt from CorrectionSupport.swift (single source of
truth), runs every case in cases.json against the Anthropic API, and grades the
output against the expected correction (case/punctuation-insensitive so we judge
whether the WORDS are right, not exact formatting).

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
SWIFT = os.path.join(ROOT, "Sources", "TypeFix", "CorrectionSupport.swift")

KEY = os.environ.get("ANTHROPIC_API_KEY", "")
MODEL = os.environ.get("MODEL", "claude-sonnet-4-6")
WORKERS = int(os.environ.get("WORKERS", "8"))


def load_prompt():
    src = open(SWIFT, encoding="utf-8").read()
    m = re.search(r'systemPrompt = """\n(.*?)\n    """', src, re.S)
    if not m:
        sys.exit("Could not find systemPrompt in CorrectionSupport.swift")
    block = m.group(1)
    lines = [ln[4:] if ln.startswith("    ") else ln for ln in block.split("\n")]
    text = "\n".join(lines)
    text = re.sub(r"\\\n", "", text)  # apply Swift line-continuation
    return text


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
