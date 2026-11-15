# Top Followers SESL 9.5 experiment

This screen-scoped experiment uses the current SESL9 dependencies. It does not replace the app's library family or the search backend.

- `Sesl95SearchView` retains the existing SearchView contract, microphone, clear button, query listener and IME handling. It draws a purple/blue/cyan/teal empty hint with the reference's 350ms/500ms reveal and 600ms/1300ms one-shot sheen timing. Typing restores normal hint drawing; disabled animations retain ordinary hints. This is an app-owned rendition of the observed effect, not a binary transplant of Samsung SearchView.
- `SeslKeyboardFocus` composes primary-colour, 3dp keyboard focus outlines with existing foregrounds. Search, toolbar actions and follower rows receive the treatment. Ctrl+F focuses search; Escape leaves it without deleting the query. Hardware-keyboard focus expands the search field even without an IME inset.
- The rejected custom toolbar-capsule overlay has been removed. The existing native toolbar owns the back and action surfaces again.

## Verification

A focused instrumented test covers keyboard entry/exit, query filtering, recreation, floating-button expansion and collapse without leaving the list. Existing follower-browser tests cover microphone and clear behavior, list/search overlap and insets. Device execution/visual parity is separate from compiling these tests.

Manual checks: open Top Followers in light/dark mode, watch the initial empty hint, type and clear a query, try voice input, scroll the list, fold/unfold or rotate, and use Ctrl+F/Tab/Escape with a keyboard. Check that the search button expands into its bar and collapses without replacing the list. The user requested install-only handoff and will perform the device checks.

## Revision after the Voice Recorder recording

The first capsule-toolbar prototype was rejected after device review. Its custom toolbar layer has been removed, restoring the existing native toolbar. Top Followers now starts with an end-aligned 56dp search button. Opening it expands the floating surface with a width spring (420 stiffness/1 damping); closing uses 439/.84. The reference APK's `showAsFab` resources and SearchView were used to identify the interaction and tuning. This is an app-local port because the pinned AppCompat lacks that attribute.

The same SearchView stays on the same Activity and keeps the existing list query callback. There is no in-field back arrow. Escape, key-based Back or keyboard dismissal collapses it and clears the filter. IME visible-to-hidden transitions handle gesture Back before the activity receives a back callback; transitions while another window has focus are ignored. Query and expanded state are retained across recreation. Ctrl+F expands and focuses it. The ordinary keyboard and voice-search integrations remain. The coloured hint only starts once the bar opens. No search subpage is launched.

Validation of this revision: local build, lint and unit tests; the revised device-test APK is built for later execution. Installation remains an install-only handoff to the user, so actual animation/layout parity is not claimed.

## Search refinements revision

The standalone filters capsule was removed after device review. The expanded search bar now uses the native overflow-button slot for the One UI list-filter icon and a native dialog with radio groups and OK/Cancel/Reset controls, replacing microphone input. Sorting and follower-count thresholds compose with the text query. Reset clears refinements only; active refinements tint the menu button and collapsed FAB. Dialog focus/IME transitions do not collapse search. Ctrl+Shift+F expands search and opens this dialog. Selections are applied with OK; Cancel or Back discards pending changes. The search overlay coordinator allows shadow overflow while retaining the 8dp popover lift.


### Adaptive minimum followers

The minimum threshold uses the widget settings track, ticks and outlined thumb with continuous travel between numeric milestones (100, 1K, 10K, 100K, etc.). Each milestone interval gets equal track space so lower counts remain usable with large outliers. The range follows the highest loaded follower count, while retaining an existing selection above the refreshed maximum. Releasing within a 6dp zone around a mark snaps to it; outside those zones, the intermediate value is retained. Empty/zero-count lists disable the slider. A live query-aware match count previews the draft, which is applied only with OK. Custom values survive recreation.
