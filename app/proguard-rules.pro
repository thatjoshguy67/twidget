# Twidget release shrinker rules. Debug builds stay unobfuscated.

-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*

# AGP generates keep rules for manifest components, including legacy widget names and launcher aliases.
# WorkManager's consumer rules retain instantiated worker names/constructors.
# App workers can still be named in a persisted pre-upgrade WorkManager database,
# even when a distribution currently disables the feature that enqueues one.
-keep class com.tjg.twidget.** extends androidx.work.ListenableWorker {
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

# The SESL9 circular-mode compatibility check distinguishes this private drawable
# from horizontal and gradient tracks; its binary name is part of that check.
-keepnames class androidx.appcompat.widget.SeslProgressBar$CirCleProgressDrawable
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    *** mIndexTipController;
}
-keepclassmembers class androidx.recyclerview.widget.SeslIndexTipController {
    androidx.recyclerview.widget.SeslIndexTipView mView;
}

# SESL exposes no setter for the native fast-scroller overlay typeface.
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    *** mFastScroller;
}
-keepclassmembers class androidx.recyclerview.widget.SeslRecyclerViewFastScroller {
    android.view.View mPreviewImage;
    android.widget.TextView mPrimaryText;
    android.widget.TextView mSecondaryText;
}

# ML Kit / Firebase-style components are discovered from manifest metadata and
# created reflectively through their no-argument constructor. Newer R8 versions
# no longer keep that constructor implicitly, which left ML Kit with no
# registered components in release builds (Gemini Nano probes then threw a
# NullPointerException because SharedPrefManager was never registered).
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
