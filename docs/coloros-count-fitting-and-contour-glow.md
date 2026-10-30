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
