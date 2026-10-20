"""Gate: string resources aapt would reject (an unescaped apostrophe fails the whole build late, in mergeResources)."""
import pathlib
import re
import sys

STRING = re.compile(r"<string\b[^>]*>(.*?)</string>", re.S)


def main() -> int:
    bad = []
    for path in sorted(pathlib.Path("app/src/main/res").glob("values*/strings.xml")):
        text = path.read_text(encoding="utf-8")
        for m in STRING.finditer(text):
            body = m.group(1)
            if body.startswith('"') and body.endswith('"'):
                continue
            if re.search(r"(?<!\\)'", body):
                line = text.count("\n", 0, m.start()) + 1
                bad.append(f"{path.as_posix()}:{line}: unescaped apostrophe (use \\')")
    for b in bad:
        print(b)
    print(f"resources: {len(bad)} findings")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
