"""Gate: nothing deployment-specific or secret-looking in tracked files (docs/spec.md §10).

Fails on company domains, IPv4 addresses (other than loopback/unspecified), API-key shapes and long hex strings.
Commit SHAs that pin GitHub Actions (`uses: owner/repo@<sha>`) are allowed.
"""
import re
import subprocess
import sys

RULES = [
    ("company domain", re.compile(r"pipeshift\.com", re.I)),
    ("IPv4 address", re.compile(r"\b(?!127\.0\.0\.1\b)(?!0\.0\.0\.0\b)(?:\d{1,3}\.){3}\d{1,3}\b")),
    ("stt key", re.compile(r"\bstt_[A-Za-z0-9_\-]{16,}")),
    ("openai-style key", re.compile(r"\bsk-[A-Za-z0-9_\-]{16,}")),
    ("bearer token", re.compile(r"Bearer\s+[A-Za-z0-9_\-\.]{20,}")),
    ("long hex", re.compile(r"\b[0-9a-fA-F]{32,}\b")),
]
SKIP_FILES = {"LICENSE", "scripts/check_public_hygiene.py"}
BINARY = re.compile(r"\.(png|jpg|jpeg|webp|jar|keystore|jks)$", re.I)


def main() -> int:
    files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.split()
    bad = []
    for path in files:
        if path in SKIP_FILES or BINARY.search(path):
            continue
        with open(path, encoding="utf-8", errors="replace") as f:
            for n, line in enumerate(f, 1):
                for name, rx in RULES:
                    if name == "long hex" and "uses:" in line:
                        continue
                    if name == "IPv4 address" and re.search(r"version|\d+\.\d+\.\d+\.\d+-", line, re.I):
                        continue
                    if rx.search(line):
                        bad.append(f"{path}:{n}: {name}")
    for b in bad:
        print(b)
    print(f"hygiene: {len(files)} files, {len(bad)} findings")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
