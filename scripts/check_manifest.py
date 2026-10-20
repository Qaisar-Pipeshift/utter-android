"""Gate: permissions and components allowed in the app manifest (docs/spec.md §10)."""
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
MANIFEST = "app/src/main/AndroidManifest.xml"
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.RECORD_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.POST_PROMOTED_NOTIFICATIONS",
}
# Services the system binds only while their UI is visible: the keyboard and a Quick Settings tile.
ALLOWED_SERVICE_PERMISSIONS = {
    "android.permission.BIND_INPUT_METHOD",
    "android.permission.BIND_QUICK_SETTINGS_TILE",
}
FORBIDDEN_ACTIONS = {"android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED"}


def main() -> int:
    root = ET.parse(MANIFEST).getroot()
    bad = []
    for el in root.iter("uses-permission"):
        name = el.get(A + "name")
        if name not in ALLOWED_PERMISSIONS:
            bad.append(f"permission not allowed: {name}")
    app = root.find("application")
    if app is None or app.get(A + "allowBackup") != "false":
        bad.append('application must set android:allowBackup="false"')
    if app is not None and app.get(A + "enableOnBackInvokedCallback") != "true":
        bad.append('application must set android:enableOnBackInvokedCallback="true" (predictive back)')
    for svc in root.iter("service"):
        if svc.get(A + "permission") not in ALLOWED_SERVICE_PERMISSIONS:
            bad.append(f"service not allowed: {svc.get(A + 'name')}")
        if svc.get(A + "foregroundServiceType"):
            bad.append(f"foreground service not allowed: {svc.get(A + 'name')}")
    for action in root.iter("action"):
        if action.get(A + "name") in FORBIDDEN_ACTIONS:
            bad.append(f"forbidden broadcast: {action.get(A + 'name')}")
    for b in bad:
        print(b)
    print(f"manifest: {len(bad)} findings")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
