# Haptic feedback

Twidget uses Android's action-based `HapticFeedbackConstants` through
`TwidgetHaptics`. The platform chooses the device effect and respects system
touch-feedback preferences. Do not use ignore-setting flags or custom timed
vibrations for these interactions.

| Interaction | Feedback |
| --- | --- |
| Long press a dashboard card to enter editing | Long press, before the card is replaced |
| Pick up a dashboard card or thread item | Drag start on Android 14+, automatic long press on older versions |
| Change a reorder position | Selection tick, only when the position changes |
| Drop an item in a different position | Confirmation |
| Add, remove or reset dashboard cards | Confirmation; rejection for removing the last card |
| Tap a different chart bar to reveal its tooltip | Selection tick; hover stays quiet |
| Pull to refresh data | Gesture start, then confirmation or rejection while the same account has focus |
| Add/remove a thread item or save a draft/schedule | Confirmation |
| Invalid post length or a scheduling error dialog | Rejection |

Selection ticks are limited to one per 60 ms per feedback view. Repeated drag
location events over the same position do not produce ticks. Cancelled drags
and unchanged drops do not produce confirmation. Launch and background syncs
stay quiet.

Android 14+ long-click listeners suppress the default long-press effect when
we supply drag-start feedback, avoiding a doubled pickup pulse. Earlier
Android versions retain the automatic long-press pickup effect. Selection
falls back to `CLOCK_TICK` before Android 14; confirmation, rejection and
refresh have fallbacks before Android 11.

## Device checks

Build and lint validate API compatibility, but physical feel requires hardware.
Check on both a current Android device and an Android 8–13 device if available:

- Enter dashboard editing, pick up a card, cross several positions, then drop.
  Expect one pickup pulse, discrete ticks and a completion pulse only when moved.
- Cancel a drag and drop without moving; neither should emit completion feedback.
- Reorder thread items and repeat the same checks.
- Add/remove cards and thread items; try removing the last dashboard card.
- Tap chart bars repeatedly, scroll past the chart, and hover with a pointer.
  Feedback should accompany a newly selected bar only.
- Refresh manually, check success and failure, then check an automatic launch sync.
  Leave the app or change accounts during a manual refresh; completion stays quiet.
- Save a draft/schedule and trigger a scheduling validation error.
- Disable system touch feedback and repeat. All added haptics should be silent.

Avoid adding pulses to every tap or ordinary scrolling. New feedback should
mark a meaningful state change and use the same action mapping.
