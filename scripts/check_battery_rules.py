"""Gate: no background work in app code (docs/spec.md §10). Utter only runs while its UI is on screen."""
import pathlib
import re
import sys

SRC = pathlib.Path("app/src/main/java")
RULES = [
    ("wake lock", re.compile(r"\bWakeLock\b|PowerManager\.(PARTIAL|FULL|SCREEN)_")),
    ("WorkManager", re.compile(r"\bWorkManager\b|\bPeriodicWorkRequest\b|\bOneTimeWorkRequest\b")),
    ("AlarmManager", re.compile(r"\bAlarmManager\b")),
    ("JobScheduler", re.compile(r"\bJobScheduler\b|\bJobService\b")),
    ("foreground service", re.compile(r"\bstartForeground(Service)?\b")),
    ("timer / fixed-rate polling", re.compile(r"\bjava\.util\.Timer\b|\bTimer\(\)|scheduleAtFixedRate|scheduleWithFixedDelay")),
    ("unbounded loop", re.compile(r"\bwhile\s*\(\s*true\s*\)")),
    ("network callback listener", re.compile(r"registerNetworkCallback|registerDefaultNetworkCallback")),
    ("raw android.util.Log", re.compile(r"\bandroid\.util\.Log\b|\bLog\.(v|d|i|w|e|wtf)\(")),
]
# The diagnostics ring buffer is the one place allowed to log (sizes, codes and timings only).
ALLOW = {"Log": {"diag/Diag.kt"}}


def main() -> int:
    bad = []
    for path in sorted(SRC.rglob("*.kt")):
        rel = path.as_posix()
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for name, rx in RULES:
                if name == "raw android.util.Log" and any(rel.endswith(a) for a in ALLOW["Log"]):
                    continue
                if rx.search(line):
                    bad.append(f"{rel}:{n}: {name}")
    for b in bad:
        print(b)
    print(f"battery: {len(bad)} findings")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
