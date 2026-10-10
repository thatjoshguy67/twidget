"""Fail if an optimized Play AAB contains sideload installation capabilities."""

import sys
from zipfile import ZipFile


def verify(path):
    with ZipFile(path) as bundle:
        manifests = [n for n in bundle.namelist() if n.endswith("/manifest/AndroidManifest.xml")]
        dex_files = [n for n in bundle.namelist() if n.endswith(".dex")]
        if not manifests or not dex_files:
            raise ValueError("Expected an Android App Bundle containing manifests and DEX")
        forbidden_manifest = (
            b"android.permission.REQUEST_INSTALL_PACKAGES",
            b".update_files",
            b"UpdateReminderReceiver",
            b"android.permission.FOREGROUND_SERVICE",
            b"android.permission.POST_PROMOTED_NOTIFICATIONS",
            b"androidx.work.impl.foreground.SystemForegroundService",
        )
        forbidden_code = (
            b"android.intent.action.INSTALL_PACKAGE",
            b"android.settings.MANAGE_UNKNOWN_APP_SOURCES",
            b"twidget-debug-latest.apk",
            b"APK download failed with HTTP",
            b"https://api.twitterapis.com/twitter/user/followers_v2",
        )
        for names, markers in ((manifests, forbidden_manifest), (dex_files, forbidden_code)):
            for name in names:
                data = bundle.read(name)
                for marker in markers:
                    if marker in data:
                        raise ValueError(f"{name} still contains {marker.decode()}")
    print("Play bundle verified: no APK updater, foreground service or on-device follower scan endpoint")


if __name__ == "__main__":
    verify(sys.argv[1])
