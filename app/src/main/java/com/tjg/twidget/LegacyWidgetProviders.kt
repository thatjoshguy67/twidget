package com.tjg.twidget

// AppWidgetManager permanently stores the provider ComponentName of every
// placed widget, so these original class names must stay resolvable or
// existing home/lock screen widgets die on update. The implementations live
// in com.tjg.twidget.widget; the manifest and all ComponentName lookups keep
// pointing at these stubs.
class TwidgetWidget : com.tjg.twidget.widget.TwidgetWidget()

class LockScreenFollowerSmallWidget : com.tjg.twidget.widget.LockScreenFollowerSmallWidget()

class LockScreenFollowerWideWidget : com.tjg.twidget.widget.LockScreenFollowerWideWidget()
