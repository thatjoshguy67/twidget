# Latest recording and first-party Clock comparison

30 October 2026. Latest recording pulled to:
/Users/tjg/Documents/Codex/ColorOS-Widget-Recordings/ColorOS-widget-2026-10-30-170429.mp4
Duration about 19 seconds, 1080x816. Reviewed sampled transition frames.
The recording shows Twidget resizing and ends with a folder opening; it does
not include a Clock widget resize sequence. The Clock comparison below is based
on implementation evidence, not a claimed side-by-side visual measurement.

## What the recording establishes

The blur-enabled ordinary AppWidget still alternates between host-scaled previews
and updated content. Crossing the compact-height threshold changes written count
into digits. Whole-preview softness and reflow remain visible despite removal
of text bitmaps. Thus bitmap scaling was not the sole cause of the behavior.
The flat system typography in that build also differs from the other Twidget
styles' per-word emphasis and glyph-fitted sizing.

## Why first-party Clock differs

Clock.apk pulled from the connected ROM contains
com.coloros.widget.launcher.LauncherWidgetOne, extending BaseLauncherWidget.
The base AppWidget callbacks simply call super/log; this is not a normal app-side
implementation publishing the visible inspiration clock. The provider is marked
hide_from_picker and guarded by OPPO_COMPONENT_SAFE, with first-party reload/text
appearance broadcasts in its manifest.

The saved launcher contains com.android.launcher3.inspirationclock.InspirationClock,
a launcher-side FrameLayout implementing DraggableView, IMultipleSizeView and
launcher lifecycle/input interfaces. It has a dedicated InspirationClockController,
InspirationClockResizeDragState and InspirationClockResizeDragAnimator.
onMeasure adjusts child bounds from clock padding and the resize drag state's
background geometry. onDragStateChange coordinates snapshots and span state.
This is direct host-side layout/animation control that an ordinary third-party
AppWidget does not receive through AppWidgetOptionsChanged callbacks.

The launcher also has class mappings replacing legacy Clock/weather providers
with inspiration clock representations. Separately, Clock ships JS RPK alarm,
stopwatch and timer cards with forceInspirationTheme and semantic colours;
these are another rendering path and must not be conflated with InspirationClock.

In contrast, our test passes a class-name allowlist check in
CustomLauncherAppWidgetHostView. It does not become InspirationClock or the
original Quick Search package: isQuickSearchWidget separately checks the package
name. The inspected generic host explicitly selects non-seamless animation for
blur-allowlisted widgets on fold-screen devices. This explains why successful
blur does not establish identical first-party resize treatment.

The launcher analysis uses the saved reference APK; it is not an exhaustive
verification of every currently modified launcher method. Clock was re-pulled
from the connected guest. Relevant extracted source is preserved under the
original APK bundle's Blur-Audit/clock-reference directory.

## Typography correction made during this follow-up

The tall ColorOS foreground now uses WidgetArtworkRenderer.followerHero and
FollowerHeroLayout.fit directly, with the same system-font glyph measurements,
per-word weights, subdued connectors, shared sizing, spacing and supporting
noun/delta roles. It creates native TextViews for fitted runs rather than
flattening those runs into a bitmap. Each run retains its own theme-aware colour.
Its native footer uses the original 12dp logo and shared paragraph/footer space.
Compact text retains native auto-fit with a 26sp maximum and 700/500 count/delta
weights; it is not claimed to replicate every compact bitmap-row metric.

A device test initially exposed that a normal StyleSpan did not apply font-weight
adjustment. Using a bold-base StyleSpan with an adjustment relative to 700 fixed
that behavior on this ROM. The updated device tests compare actual run text,
text sizes and resulting typeface weights against the shared fitter, in addition
to bounds/effect isolation and ordinary-renderer regression checks.
The build/unit suite and all four targeted device tests passed. The updated APK
is installed; One UI and Material retain their existing rendering paths.

This restores the system-font hero rules, not first-party Clock's private host
integration. Exact Clock transition parity still requires a deeper supported
host/card integration, or a launcher modification. A Clock resize recording is
still needed for a matched visual comparison before attributing each preview
artifact to a specific animation mechanism.
