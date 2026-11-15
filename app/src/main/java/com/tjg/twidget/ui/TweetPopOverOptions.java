package com.tjg.twidget.ui;

import android.app.Activity;
import android.content.Intent;
import androidx.activity.result.ActivityResultLauncher;
import dev.oneuiproject.oneui.ktx.ActivityKt;
import dev.oneuiproject.oneui.popover.PopOverOptions;

/** Bridge to the library's newer JVM inline API, matching dashboard tweet launching. */
final class TweetPopOverOptions {
    static void launch(Activity activity, Intent intent, ActivityResultLauncher<Intent> launcher) {
        ActivityKt.startPopOverActivityForResult(activity, intent,
                PopOverOptions.Companion.centerRightAnchored(activity), launcher);
    }
}
