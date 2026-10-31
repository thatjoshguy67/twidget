# ColorOS home-widget integration

## Picker and compatibility

The user chose to retain separate Followers and Your Brief entries, allowing
native blur only for Followers. The installed ColorOS launcher allowlists one
provider class name: `com.heytap.quicksearchbox.ui.appwidgets.DesktopSearchWidgetProvider`.
The app-owned receiver with that name is now the ColorOS Followers adapter,
labelled with the existing Followers label. There is no separate test label.

The original Followers component remains registered. On ColorOS, alternate
provider metadata hides it from the picker without disabling the receiver or
removing placed widgets. The compatibility receiver is hidden by default and
promoted by metadata only on the vendor framework, detected via Oplus/Oppo
framework classes rather than the physical phone manufacturer. Android versions
without hide-from-picker support do not enable the compatibility receiver.

On fresh One UI and Pixel installs, the original Followers/Brief metadata,
component names, rendering branches and default styles remain in use. If shared
widget data migrates out of ColorOS, the app restores only metadata overrides
that it previously applied. App and onboarding Add Widget buttons use the same
provider choice as the picker.

Placed widgets keep their component ids and per-widget settings. A placed test
widget becomes the normal Followers adapter without re-adding it. An older
ordinary Followers placement remains supported with a fallback background; it
must be re-added through the normal Followers entry to acquire native ColorOS
blur. Android does not support transferring its existing id to another provider.

## Rendering

ColourOS style on the native Followers adapter keeps the existing host-owned
blur/contour-glow surface and live resize handling. Other styles on that adapter
reuse their original Canvas renderer through the bounded ColorOS size wrapper.
Ordinary Followers placements with ColorOS style use an untagged rounded fallback
surface. Compact cards use the existing jointly fitted compact artwork: a
centred logo/count/delta row for 2x1, and the handle-bearing layout only for
wider one-row cards. The foreground is transparent, with native material on a
separate sibling. Custom fonts work on both native and fallback cards; tall
custom-font cards reuse the full existing artwork renderer without its background.

Your Brief keeps its original provider, data/presentation renderer, selected
font and tap action. ColorOS style uses a rounded, theme-aware fallback surface
and the same bounded size-variant selection. It never includes `OplusBlurBg` and
does not request native blur or contour glow. Existing non-ColorOS style paths
are unchanged. ColorOS becomes the default widget style on its vendor framework;
explicit stored style choices continue to win.

Resize and theme-artwork helpers are shared only within these ColorOS adapters.
Followers' day/night count bitmap pair is reused across variants. Brief and
other-style artwork use a 5 MB base budget plus at most three 1 MB compact
budgets, with theme variants sharing each budget. No launcher or system settings
are patched. Debug builds are produced locally.

The responsive default child is populated, bounded compact artwork, shared with
the minimum-size variant. An invisible zero-size default can leave ColorOS's
asynchronous update path blank until another size selection. Tests exercise
the default before host sizing and repeated asynchronous updates at unchanged
host bounds, alongside the resize and bitmap-budget checks.

## Verification

Tests cover default manifest picker metadata, runtime ColorOS picker visibility,
idempotent reconciliation without changing bound ids, native-blur registration
only for Followers, visible untagged fallback surfaces, serialised fallback
resize handling, and One UI/Pixel style defaults and existing renderer roots.
The previous OEM-rule/live-host resize regression tests remain in place.

## Settings

This launcher builds the native adapter's long-press Settings action from
`oplus.appwidget.shortcuts`, rather than its standard reconfigurable flag alone.
The `common_settings` shortcut targets the exact installed application variant.
It supplies no widget ID: the app opens the sole configured instance directly,
or offers an instance chooser when there are several. The standard configuration
activity and reconfigurable metadata remain available for other launchers.

On ColorOS, the ColorOS preset follows the system's day/night appearance and
material. It hides per-widget opacity and colour-mode controls, retaining fonts,
language, logo, delta, account and tap behavior. There are no system-settings links.
