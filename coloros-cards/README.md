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

## Ordinary AppWidget allowlist probe

Debug builds now expose **Twidget ColorOS Blur Test**, a disposable Followers
provider owned by com.tjg.twidget.debug. Its fully qualified receiver class is
com.heytap.quicksearchbox.ui.appwidgets.DesktopSearchWidgetProvider to exercise
the launcher's class-name allowlist. This does not install/replace Quick Search,
rename existing Twidget providers, or modify launcher/system files.

The test defaults to ColorOS when first configured. Only this provider, in a
debug build, with ColorOS selected uses widget_coloros_host_blur and a child
FrameLayout tagged OplusBlurBg. Its foreground bitmap has no baked background.
One UI and Material switch back to ordinary untagged layouts; normal Followers
and Brief providers remain controls. Existing widgets do not migrate to the probe.
Refresh, pinned-account sync and the loading-spinner layout include the probe.

Build/unit checks passed, including exhaustive debug/provider/style guard cases.
Two instrumentation tests passed on the connected phone: the tagged surface is
exclusive to the probe layout and foreground artwork is transparent at the
surface boundaries in light/dark modes. Android registers the receiver under
Twidget Debug. These tests do not prove the launcher's blur actually engages.

To complete the experiment, add the exact test entry, compare ColorOS against
One UI/Material, and move/change the wallpaper behind it. Check launcher blur
logs, resizing and style changes. A translucent fill alone is not a pass. If the
class-name check has changed in this live launcher or material is unavailable,
the test may still fall back without blur.

### Previous bitmap bounds fix (superseded by native foreground)

After the user confirmed native blur engaged, the artwork was found to be a
sibling of the tagged surface. The launcher clips the tagged view to its native
rounded outline, leaving sibling content outside that outline. Artwork and
spinner now live inside the tagged surface with an 8dp inset and child clipping.
Artwork sizing accounts for that inset. Only the active ColorOS probe uses the
reported responsive allocations even when the port advertises Samsung framework
fields. When exact sizes are absent, fallback sizes use the reported portrait/
landscape allocations instead of synthetic larger breakpoint dimensions.

The updated debug APK was installed. Unit checks and four device tests passed;
measurement tests cover compact, wide, square and tall sizes and ensure the
image frame and fitted bitmap stay inside the padded surface. They do not replace
a visual check of the launcher across fold/resize transitions.


### Current probe: native foreground

The screen recording showed bitmap scale/reflow jumps during host resizing.
The ColorOS probe now renders count, caption, delta and account with native
TextViews and a resource logo. Text auto-sizes within measured view bounds;
compact cards use a numerical count, taller cards keep the written count.
Long content ellipsizes at minimum text size and retains a full accessibility
label. The system font is used; the custom artwork-font control is hidden only
for this probe's ColorOS style.

The tagged blur view is now background-only. It and the foreground share a
rounded, clipped parent, preventing blur effects from encompassing text while
keeping content within one bounded frame. Layout uses ordinary live measurement,
not a bitmap per responsive breakpoint. Root click actions, selected account,
logo, delta preference and loading indicator are retained. Very narrow compact
cards omit the visible delta to give the count room; accessibility retains it.
One UI, Material and normal AppWidget component identities are unchanged.

The build/unit suite passed. Three instrumentation tests passed on the connected
phone: native/blur effect separation, text/footer bounds with long counts and
1.0/1.5 font scales across multiple sizes and themes, and ordinary-style bitmap
renderer isolation. The updated APK is installed. This removes the app-side
bitmap scaling mechanism; the launcher's own non-seamless foldable transition
can still temporarily soften a resize preview. A new visual resize check remains
necessary before claiming every transition issue is resolved.
