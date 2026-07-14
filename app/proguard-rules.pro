# Twidget release shrinker rules. Debug builds stay unobfuscated.

-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*

# Manifest components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.appwidget.AppWidgetProvider
-keep public class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# WorkManager
-keep class * extends androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# MainDrawerController refreshes the One UI drawer presenter reflectively. R8
# cannot infer this member because the receiver class is only known at runtime.
-keepclassmembers class dev.oneuiproject.oneui.navigation.menu.DrawerMenuPresenter {
    void updateMenuView(boolean);
}

# JSON models are parsed explicitly via org.json; no reflection keep rules needed.

# Preserve line numbers for crash reports
-renamesourcefileattribute SourceFile
