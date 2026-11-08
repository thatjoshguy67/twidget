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
dialog parent panels, complete menu/dropdown surfaces, search frames and bottom navigation.
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

The follow-up audit checked every call to the shared decorative-stroke helper in
the reference APK (15 call sites, including app-specific menu and pane-transition
calls), plus direct constructor/resource uses. The latter picker/button resources
use focus-state outlines; they are not the always-visible gradient treatment.
The programmatically built add-card sheet and snackbar also explicitly install
the observer when their separate window is shown.

| Reference surface | Twidget integration |
| --- | --- |
| Floating toolbar / capsule-slot backgrounds | Existing projection backgrounds, retaining native transitions |
| Alert panels | `parentPanel`, including plain fills whose rounded contour is supplied by blur/window clipping |
| Menu popup content | Complete popup background/content ancestor, including header and padding |
| Native list dropdowns | Settings dropdown explicitly installs the window observer after showing |
| Expanded search / collapsed search FAB | Search plate frame and backed search button; no second rim on inner content |
| Bottom navigation | Native navigation background |
| Divider action bar | Native DividerButtonLayout, including widget-editor actions |
| Main tabs | Backed TabLayout surface |
| Responsive drawer / floating pane | Native pane surfaces and the older design wrapper's drawer panel |
| Suggestion snackbar | SnackbarContentLayout, using the reference's dark-surface stroke color |
| Go-to-top | RecyclerView's public overlay view |
| Index tip | Kept controller/view fields bridge the overlay outside normal child traversal |
| Legacy fast-scroll preview | Existing fast-scroller bridge also decorates its preview bubble |
| Twidget floating equivalents | Notice/onboarding back buttons, Brief footer circles and compact tweet-action capsules |
| Add-card sheet | Rounded custom modal surface |

Insets, ovals and independent per-corner radii are read from the current fill or
ripple mask. A rectangular fill outline does not cancel the component's fallback
radius: on One UI the window/blur layer often supplies the rounded clipping.
Ordinary content cards and focus-only control outlines retain their own styling.

`SeslSurfaceHighlightInstrumentedTest` checks light/dark rim pixels, resizing,
inset ovals and asymmetric corners, blur-panel radius fallback, foreground
composition/replacement, circular track tint, native dropdown observation and
the native/custom surface families above. The renderer uses APIs available at
Twidget's API 26 minimum.
