# Launcher picker previews

The picker offers 2×1, 4×1, 2×2 and 4×2. Larger live widget resizing remains supported.

Android 15+ generated previews use `WidgetPreviews.artwork`, which calls the live follower and Brief renderers. Picker settings always use the ROM default style/font and the device theme, independently of saved widget or app overrides.

One UI Home reads its own `previewLayoutSmall/WideSmall/Medium/Large` metadata. These point to One UI artwork. Standard `android:previewLayout` points to Material artwork for launchers that do not support generated previews. Each XML fallback scales the whole composition together, using separate background, primary, secondary and decoration PNG layers. Framework `android:tint` resolves the text and surface colours in the launcher's theme; Android 12+ Material colours come from the wallpaper palette. The artwork's alpha preserves One UI opacity. The Brief icon keeps its original colours.

The bundled fallback examples are English at font scale 1, like other static picker artwork; generated previews use the current locale. Both fonts are baked into the fallback layers so launchers cannot substitute their own fonts or independently resize labels.

## Regenerating fallback artwork

After changing either renderer, build and install the GitHub debug app and its Android test APK on an API 31+ AVD. Run `WidgetPreviewAssetsInstrumentedTest#exportPickerArtwork`, then copy `cache/picker-assets/*.png` from the app sandbox into `app/src/main/res/drawable-nodpi/`. Copy the differing layers from `cache/picker-assets-night/` into `drawable-night-nodpi/` too (identical files can be omitted). Separate night masks preserve light-on-dark text antialiasing. The exporter fixes density at 3× and font scale at 1, separates tintable text from the original artwork, and does not change AVD display overrides.

Run `WidgetPickerPreviewInstrumentedTest` to inflate every XML preview in real RemoteViews, compare it with the live renderer in light/dark contexts and verify the provider metadata. Inspect the generated `cache/picker-preview-sheet.png` as well.

## Follower widget proportions

The follower layouts follow the Figma home widgets at 162×76, 352×76, 162×176 and 352×176 dp. Large-card headings use proportional size caps and shrink for long counts; footer logos stay at 12 dp independently of handle text. One UI compact widgets have pill-shaped surfaces.

Material cards optionally contain the username and delta. The per-widget `containedFooter` setting defaults to false and is also available in widget defaults. Compact layouts and Brief retain their existing footer treatment. The delta badge uses the supplied Figma asset in `docs/design-assets/widget-delta-badge.svg`, rasterised at 4× into `drawable-xxxhdpi`; its text is centred by visible glyph bounds on both axes.

`WidgetProportionsInstrumentedTest` checks containment scope, save/cancel behaviour, fixed logo size, and badge text centring across fonts and densities. Settings use the system wallpaper surface through the window theme and a rounded transparent preview; no wallpaper bitmap access is required.

The configuration scroll view disables native edge fades because One UI on API 36+ uses transparent, isolated canvas layers for those strips. Clearing a wallpaper opening inside such a layer exposes the page background and creates a straight cutoff. `WidgetSettingsRenderingInstrumentedTest.wallpaperScrollStaysOutsideNativeFadeLayers` guards this exception, including recreation and the Samsung transparent fade colour. Other screens retain native fading.
