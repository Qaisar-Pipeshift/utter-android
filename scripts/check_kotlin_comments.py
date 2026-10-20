"""Gate: Kotlin comments nest, so "/*" inside a comment (e.g. `audio/*`) leaves the file with an unclosed comment."""
import pathlib
import re
import sys

NESTED = re.compile(r"\S/\*")  # "/*" glued to a preceding character, e.g. audio/*, text/*


def main() -> int:
    bad = []
    for path in sorted(pathlib.Path("app/src").rglob("*.kt")):
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.lstrip()
            in_comment = stripped.startswith(("*", "//", "/**", "/*"))
            if in_comment and NESTED.search(stripped.lstrip("/*").lstrip()):
                bad.append(f"{path.as_posix()}:{n}: '/*' inside a comment opens a nested comment")
    for b in bad:
        print(b)
    print(f"kotlin comments: {len(bad)} findings")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
