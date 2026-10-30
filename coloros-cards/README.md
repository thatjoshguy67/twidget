# Experimental ColorOS native cards

The debug APK now embeds a compiled, development-signed RPK at
assets/coloros-cards.rpk and advertises four UMS RPK card entries:
Followers, Your Brief, compact lock and wide lock. The registration and RPK
exist only in the debug source set, with provisional local test identifiers.
These identifiers are not OPPO-assigned production identifiers.

## Blur isolation

Only the new native home templates request `backdrop-filter: host-blur` and
`theme-background-color: uxCardColorBackground`, with semantic text colours.
One UI and Material RemoteViews rendering is unchanged. The ordinary ColorOS
AppWidget style still uses its baked translucent fallback; selecting it does
not convert an installed AppWidget into an Instant Card. Add the native entries
from the ColorOS card picker to test the host API, if UMS admits them.

The compiler warns about OEM-specific CSS, but retains it. Both the build script
and check-package.py inspect compiled output and refuse a package missing the
host-blur properties. This proves packaging, not runtime blur. The host must
supply its blur interface, enable material and accept the card.

## Rebuild

Run npm install in this directory (hap-toolkit pinned to 2.1.1), then
`npm run build:debug` and `python3 scripts/check-package.py`. Build the Android
GithubDebug variant afterwards. The script uses an isolated temporary project,
the toolkit's development signing certificate and the debug APK application ID.
It never starts a server, connects to a device or installs an app. Do not ship
this RPK or the provisional catalogue IDs as a production integration.

## Data and limitations

The provider supports the engine's APK-backed lite lifecycle protocol, validates
package/path/instance input, and restricts Binder callers to the app UID or
named, platform-signed hosts. It does not trust host/sha256 request fields.
Followers reads shared Followers defaults; Brief uses existing cached Brief
presentation. Lock cards retain shared One UI lock settings and their initial
compact/wide source layouts. No credentials or arbitrary file access are exposed.
Updates notify card observers after cache, refresh or widget-settings changes.

Separate native-instance configuration, vector logo assets, authenticated
click routing and live colour/AOD validation are still incomplete. The lock
source templates have not been changed to request the new desktop host blur.
These prototype templates do not yet reproduce every bitmap detail of the
existing Android widgets.

## Device result

The GithubDebug APK built and installed successfully, and Android's package
resolver lists the RPKCARD_UPDATE provider. That does not establish UMS admission.
A read-only catalogue query could not run in the guest environment: the host
content command hit a Parcel protocol mismatch, and the guest namespace retry
failed loading com/oplus/media/OplusBitmapChecker. No UMS/launcher configuration
was changed to bypass authorization. Picker visibility and actual blur remain
unverified and need a device UI check.

### Follow-up after re-adding the widget

Launcher logs identify the placed Followers component as an ordinary AppWidget
with cardType=-1. Re-adding it and choosing ColorOS does not select this RPK.
A read-only copy of UMS's card_configs database contained 44 RpkLocalCardConfig
records and 1062 CardConfig records, with zero Twidget rows in either table.
Reinstalling the APK while UMS was running triggered package scanning; afterwards
both tables still had zero Twidget entries. This confirms absence from that
catalogue, not the precise reason for exclusion.

ColorOsAdmissionInstrumentedTest passed on the connected phone: the app's own
provider accepted a valid native-card request and returned success. Its direct
OCS query returned IllegalArgumentException rather than an authorization result.
Consequently authorization denial is not established by this test. The test
reports diagnostic status through instrumentation without dumping follower data
or credentials, and does not change UMS permissions or configuration.

No visible native blur has been verified. The native catalogue/discovery and
admission path still needs resolving before a native rendering test is possible.
