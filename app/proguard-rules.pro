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

# SESL9 FloatingToolbarAware locates the navigation button through
# SeslBaseReflector.getDeclaredField(Toolbar.class, "mNavButtonView"). Keep this
# field name in minified builds so both back and drawer buttons get their surface.
-keepclassmembers class androidx.appcompat.widget.Toolbar {
    android.widget.ImageButton mNavButtonView;
}

# JSON models are parsed explicitly via org.json; no reflection keep rules needed.

# oneui-design 0.9.13+oneui8's immersive-scroll API names a class removed in SESL9.
# Twidget never calls it; native SESL9 floating toolbars own scrolling instead.
# Android's default View-setter rule retains setImmersiveScroll(), so explicitly
# disable its legacy activation call. Keep this scoped to the wrapper API, and
# require R8 to discard the incompatible helper rather than just hiding errors.
# Re-audit on wrapper upgrades: this API becomes a no-op in optimized builds.
-assumenosideeffects class dev.oneuiproject.oneui.layout.ToolbarLayout {
    public boolean activateImmersiveScroll(boolean, float) return false;
}
-dontwarn com.google.android.material.appbar.SeslImmersiveScrollBehavior
-checkdiscard class dev.oneuiproject.oneui.layout.internal.util.ImmersiveScrollHelper**

# Preserve line numbers for crash reports
-renamesourcefileattribute SourceFile
