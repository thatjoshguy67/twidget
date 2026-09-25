# Launcher picker previews

The picker offers 2×1, 4×1, 2×2 and 4×2. Larger live widget resizing remains supported.

Android 15+ generated previews use `WidgetPreviews.artwork`, which calls the live follower and Brief renderers. Picker settings always use the ROM default style/font and the device theme, independently of saved widget or app overrides.

One UI Home reads its own `previewLayoutSmall/WideSmall/Medium/Large` metadata. These point to One UI artwork. Standard `android:previewLayout` points to Material artwork for launchers that do not support generated previews. Each XML fallback scales the whole composition together, using separate background, primary, secondary and decoration PNG layers. Framework `android:tint` resolves the text and surface colours in the launcher's theme; Android 12+ Material colours come from the wallpaper palette. The artwork's alpha preserves One UI opacity. The Brief icon keeps its original colours.

The bundled fallback examples are English at font scale 1, like other static picker artwork; generated previews use the current locale. Both fonts are baked into the fallback layers so launchers cannot substitute their own fonts or independently resize labels.

## Regenerating fallback artwork

After changing either renderer, build and install the GitHub debug app and its Android test APK on an API 31+ AVD. Run `WidgetPreviewAssetsInstrumentedTest#exportPickerArtwork`, then copy `cache/picker-assets/*.png` from the app sandbox into `app/src/main/res/drawable-nodpi/`. Copy the differing layers from `cache/picker-assets-night/` into `drawable-night-nodpi/` too (identical files can be omitted). Separate night masks preserve light-on-dark text antialiasing. The exporter fixes density at 3× and font scale at 1, separates tintable text from the original artwork, and does not change AVD display overrides.

Run `WidgetPickerPreviewInstrumentedTest` to inflate every XML preview in real RemoteViews, compare it with the live renderer in light/dark contexts and verify the provider metadata. Inspect the generated `cache/picker-preview-sheet.png` as well.
