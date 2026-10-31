# ColorOS count fitting and contour glow

## Recording evidence

The user recording `Record_2026-10-30-17-30-55_b783bf344239542886fee7b48fa4b892.mp4`
shows settled, per-word ellipses in the large card: `Nine`, `Thirty`, and `Four`
become `Ni…`, `Thi…`, and `Fo…`. Narrower tall sizes are affected too.
The compact numeric layout appears during one-row resizing. The previous tests
only proved app-process text layout. Adding parcel round trips and applying the
views with the installed launcher's theme did not reproduce the recorded issue.
Do not treat these tests as proof of actual launcher appearance.

The launcher also contains `CustomLauncherAppWidgetHostView.resetChildViewSize`,
which independently adjusts descendant geometry and TextView sizes. This is an
additional host path outside the app's measurements; the exact cause of the
recorded per-word discrepancy has not been isolated inside the launcher process.

## Count rendering

The tall count now uses one transparent paragraph bitmap from the same shared
fitter and system-font emphasis rules as the other styles. A native ImageView
with `fitStart` scales the whole paragraph into its actual bounds, including
when the host changes size before delivering new options. The account footer
remains native and the tagged blur surface is a separate sibling. This is a
scalable count layer, not a claim that the count is native TextViews. Compact
counts retain native TextView autosizing. One-row classification uses the same
110dp height threshold as the existing AOSP widget mode selection.

Artwork uses the existing day/night RemoteViews helper and bitmap budget. App
upgrades republish stored widget state so an older cached layout does not wait
for the scheduled refresh. The tall layout has a new resource identity to avoid
reapplying image actions to an older FrameLayout with the same child id.

## Native contour glow

The supplied launcher has the following path:

- `CustomLauncherAppWidgetHostView.createBlurForView` assigns the widget radius
  and requests `CardBackgroundBlurManager.createBlurForView(..., 7)`.
- `7` is `TYPE_WIDGET`. Both `LayerBlurDrawable` and `RenderEffectDrawable`
  support texture stroke and inner-shadow parameters.
- `LauncherMaterialEffectUtils.getBlurServiceEffectParams(7)` returns the native
  `WIDGET` stroke/shadow preset.
- `LayerBlurDrawable.refreshBlurDrawableParam` and
  `RenderEffectDrawable.updateBackdropEffectParams` apply those parameters when
  `TextureStrokeController` is enabled, temporary stroke visibility is enabled,
  and the native radius is positive.

Read-only device inspection found `system_material_blur_enable=1` and
`system_material_stroke_enable=1`. These settings were not changed.

The app leaves contour glow to the launcher. Both probe layouts omit the extra
root outline clip so its fixed 24dp corner mask cannot cut a different native
smooth-corner outline. The tagged background still receives its outline and
clipping from the launcher. No static or animated imitation border is added.
These changes remain scoped to the ColorOS debug probe.

## Verification limits

Instrumentation covers serialized layouts, light/dark artwork transparency,
compact count shrinking, and scaling an already-applied paragraph through
mismatched host sizes. This does not prove visual parity with Clock's dedicated
launcher implementation, nor does it prove the contour glow's appearance in a
new launcher recording. Those require checking the resulting launcher state.

## 31 October: bound the ColorOS root before OEM measurement (first attempt)

The installed launcher 17.3.12 measurement rule was invoked in a controlled
on-device audit. A match-parent root given a 683x389 area measured 823x469;
an explicitly sized root remained 683x389. The optimiser's canonical-height
measurement and the blur allowlist's skipped scale-down explain the whole-card
oversizing, including cropped text, footer and native edge shape.

`ColorOsNativeFollowerViews.create` now sets explicit root dimensions through
RemoteViews layout actions on Android 12+. Its child blur surface, scalable
paragraph and footer still size relative to that root. Those actions survive
serialization and reapplication when widget options change. The renderer is
selected only for the debug ColorOS probe with the ColorOS style; ordinary
providers and other styles retain their existing sizing and artwork paths.
No One UI or Pixel sizing logic, layouts, shared fitter, or artwork renderer is
changed by this correction.

The regression test executes the installed OEM rule on serialized probe views,
checks compact/tall transitions and same-layout reapplication, and retains an
unbounded control that must reproduce oversizing. Ordinary-provider tests also
assert that their root layout parameters remain match-parent. Fresh launcher
screenshots are still required to verify visual behavior through real drags.


## 31 October: live resize bounds and virtual size callbacks

The 15:43:55 recording demonstrates overflow during drag and left-edge clipping
once the resize settles. The first explicit-root test used actual viewport
sizes as its input and therefore missed the callback/viewport mismatch.

The installed launcher's `WidgetSizes.getWidgetSizeOptions` uses
`AlignMeasureHelper.getPaddingFitMeasure` for optimised widgets. This reports
an enlarged virtual canvas in `appWidgetSizes` and min/max dimensions.
`CustomLauncherAppWidgetHostView` skips its normal alignment scale-down for
blur-allowlisted providers, so fixing a root to these virtual dimensions is
insufficient. The resize code's clipping changes can reveal and then hide the
same overflow; clipping alone cannot make its content fit.

The ColorOS publication now uses size-aware RemoteViews. Android's actual
`AppWidgetHostView.onLayout` chooses a fitting variant against the live frame,
independently of provider callbacks. Every visible variant has explicit pixel
bounds below its selection key, leaving room for the framework's rounding
slack. A zero-size invisible fallback cannot overflow during initial layout or
very small intermediate bounds. Compact alternatives cover one-row transitions.
There are at most 16 variants, as required by RemoteViews.

The transparent paragraph uses shared Bitmap actions and two theme-selected
ImageViews, so all variants share one light/dark bitmap pair. Repeating day/night
Icon actions would count the same imagery multiple times against the platform
memory limit. The blur and contour glow remain owned by the launcher.

The regression test now runs an actual AppWidgetHostView with the installed
OEM measurement rule, serialised views, changing live viewports, both clipping
states, and a late provider update after release. It asserts card containment,
paragraph containment and shared bitmap identity/budget. The only change to the
shared widget entry point is inside its pre-existing ColorOS probe branch.
Ordinary One UI and Pixel rendering/sizing paths remain unchanged.

Debug builds for this work are local. Instrumentation verifies the controlled
host lifecycle, not the appearance of the complete launcher during a real drag;
a fresh launcher recording remains the visual verification step.
