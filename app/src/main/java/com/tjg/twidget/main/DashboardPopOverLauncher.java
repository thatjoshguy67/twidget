package com.tjg.twidget.main;

import android.app.Activity;
import android.content.Intent;
import androidx.activity.result.ActivityResultLauncher;
import dev.oneuiproject.oneui.ktx.ActivityKt;
import dev.oneuiproject.oneui.popover.PopOverOptions;

/** Java calls the library's JVM 21 inline API without changing the app's JVM 17 target. */
final class DashboardPopOverLauncher {
    static void launch(Activity activity, Intent intent, ActivityResultLauncher<Intent> launcher) {
        ActivityKt.startPopOverActivityForResult(activity, intent,
                PopOverOptions.Companion.centerRightAnchored(activity), launcher);
    }
}
