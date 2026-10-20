"""Gate: the release APK merges in nothing we did not declare (v0.1.4, docs/spec.md §10).

Runs after `:app:assembleRelease`: reads the merged (or packaged) release manifest and fails when any
service, receiver, provider or activity outside our own package and the two known AndroidX components appears,
or when a permission outside the five declared ones appears. The full component list is printed either way.
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
ROOT = pathlib.Path("app/build/intermediates")
COMPONENT_TAGS = ("service", "receiver", "provider", "activity", "activity-alias")
OURS = "io.github.qaisarpipeshift.utter."
ALLOWED_COMPONENTS = {
    # AndroidX Startup creates its provider in every app that uses startup; the app declares no initializers.
    "androidx.startup.InitializationProvider",
    # Compose's baseline-profile installer registers this receiver in release builds.
    "androidx.profileinstaller.ProfileInstallReceiver",
}
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.RECORD_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.POST_PROMOTED_NOTIFICATIONS",
    # androidx.core declares this signature permission in every app for ContextCompat.registerReceiver; inert while
    # no code registers a receiver (Utter registers none).
    "io.github.qaisarpipeshift.utter.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}


def find_manifest():
    if not ROOT.is_dir():
        return None
    found = []
    for path in ROOT.rglob("AndroidManifest.xml"):
        parts = path.parts
        merged = any(p.startswith(("merged_manifest", "packaged_manifest")) for p in parts)
        if not merged or "release" not in parts:
            continue
        if any(p in ("debug", "test", "androidTest") for p in parts):
            continue
        found.append(path)
    packaged = [p for p in found if any(p.startswith("packaged_manifest") for p in p.parts)]
    candidates = packaged or found
    return sorted(candidates)[0] if candidates else None


def main() -> int:
    manifest = find_manifest()
    if manifest is None:
        print("merged-manifest: no release manifest under app/build/intermediates; run :app:assembleRelease first")
        return 1
    print(f"merged manifest: {manifest.as_posix()}")
    root = ET.parse(manifest).getroot()
    package = root.get("package") or ""

    components = []
    bad = []
    for tag in COMPONENT_TAGS:
        for el in root.iter(tag):
            name = el.get(A + "name")
            if name is None:
                bad.append(f"{tag} without android:name")
                continue
            if name.startswith(".") and package:
                name = package + name
            components.append((tag, name))
            if not (name.startswith(OURS) or name in ALLOWED_COMPONENTS):
                bad.append(f"{tag} not allowed: {name}")

    permissions = []
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for el in root.iter(tag):
            name = el.get(A + "name")
            if name is None:
                bad.append(f"{tag} without android:name")
                continue
            permissions.append(name)
            if name not in ALLOWED_PERMISSIONS:
                bad.append(f"permission not allowed: {name}")

    print(f"components: {len(components)}")
    for tag, name in sorted(components):
        print(f"  {tag}: {name}")
    print(f"permissions: {len(permissions)}")
    for name in sorted(set(permissions)):
        print(f"  {name}")

    for finding in bad:
        print(finding)
    print(f"merged-manifest: {len(bad)} findings")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
