# One UI 9.5 surface backport

Twidget retains its pinned SESL9 1.0.x family. This app-local compatibility
layer implements the decorative rim traced in Voice Recorder 21.5.95.41
(AppCompat 2.0.25-sesl9, Core 2.0.18-sesl9, Material 2.0.27-sesl9).
The reference APK SHA-256 was
`8207dcf6f080be3075e908849aaeeb65ba0e191bd704d53af67bbda05b9ec5a5`.

The reference shared attachment helper constructs a foreground inner stroke:
1dp wide, white at 70% opacity in light themes or 10% in dark themes, fading to
transparent with stops `[0, .75]` and angle 270 degrees. The observed effect is
static. Our renderer uses Canvas and LinearGradient and needs no Samsung blur API.

The existing window observer discovers floating projection background views,
dialog parent panels, menu popup lists, search plates and bottom navigation.
It also covers windows inflated by AppCompat and lazy content. Attaching to the
projection background itself preserves native floating geometry, alpha and
resize transitions. The renderer reads the current background outline on draw,
falling back to the matching SESL component radius. Ordinary round rectangles
are used; Samsung smooth-corner contour parity is not claimed.

Existing foregrounds are composed below the rim with LayerDrawable rather than
replaced. Discovery is idempotent, and later foreground replacement is respected.
Active Samsung theme packages suppress the rim, as in the reference helper.
App theme changes recreate the window, refreshing its theme-dependent colors.
This does not add the newer capsule-slot layout engine or change backdrop blur.

The other confirmed correction is theme-aware MODE_CIRCLE progress tracks.
The compatibility layer recognizes the private circular background layer and
sets its tint through SESL's public API. R8 preserves that class name for this
check. Explicit app tints, horizontal bars, gradient-circle mode and indeterminate
spinners retain their existing treatment.

`SeslSurfaceHighlightInstrumentedTest` checks light/dark rim pixels, resizing,
foreground composition and replacement, circular track tint and live toolbar /
dialog integration. The renderer uses APIs available at Twidget's API 26 minimum.
