# Experimental ColorOS lock cards

These are template sources, not a compiled or enrolled native card. The Android
APK includes ColorOsLockCardProvider at `${applicationId}.coloros.lockcards`.
No UMS discovery metadata is advertised yet, because doing so without a valid
compiled RPK URL would publish broken entries in the device catalogue.

The compact/wide templates mirror the existing One UI content arrangement and
use the same app-side lock settings, account and formatted follower data. This
first pass uses shared lock defaults rather than separate native-instance
configuration. It does not change or replace the Samsung widgets.

Before native device testing:

1. Obtain the compatible OPPO/lite compiler and CARD_CLIENT admission route.
2. Substitute __APPLICATION_ID__ in the manifest for the chosen APK variant
   (Github debug currently uses com.tjg.twidget.debug). Compile .ux into the
   engine's .template.json format and package/sign the RPK using that toolchain.
3. Supply a supported RPK resource URL and assigned card identifiers in the
   pantanal.card.provider registration, with lock entrance bit 64 and confirmed
   size/distribution values. Do not copy system card type identifiers.
4. Confirm actual caller identity. Provider accepts its own UID or named host
   packages signed with the device platform certificate. It does not trust
   request Bundle host/sha256 fields. OEM builds using different engine signing
   require reviewed certificate pins; do not weaken this to package-name checks.
5. Validate template data binding, host colours, material and AOD. Static white
   template foreground is only a starting point, not verified live colour/AOD
   adaptation. forceInspirationTheme requests host material; no fake blur is
   rendered by Twidget. Finish host-mediated tap-to-configure routing and actual
   vector logo assets before treating native lock functionality as complete.

Data uses create/update/subscribe responses (code=0, data=JSON) and notifications
at provider/RPK/path. No credentials, general queries or file access are exposed.
The provider rejects unknown package/path/method/oversized instance input. It
serves cached data and does not launch activities from Binder callbacks.

The ColorOS home style is available now for ordinary Followers and Your Brief
AppWidgets. Its translucent 24dp surface is a fallback appearance, not a claim
that ColorOS grants ordinary AppWidgets native backdrop blur. Existing launcher
blur restrictions still apply; native home-card enrollment is not included.

## Validation for this initial branch

`./gradlew :app:assembleGithubDebug :app:testGithubDebugUnitTest` passes (512
unit tests). No APK installation or device interaction was performed. Protocol
unit tests cover accepted compact/wide opaque instance IDs, malformed or missing
identity, cross-card requests, unsupported commands and teardown validation.
Caller certificate enforcement and native template behavior require a device
integration test; the unit suite does not establish these.
