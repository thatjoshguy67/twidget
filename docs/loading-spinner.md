# Gradient loading spinner

Source: user-supplied Gallery Cloud Sync APK, package `com.samsung.android.agent.storage`, version `1.6.09.31` (`160900031`). APK SHA-256: `f80d12226a9ae4a77193eda0b36ea5959c053dd2c87765e94f6686ce9c13afbd`.

The newer spinner is `res/drawable-v36/sesl_progress.xml`, referencing `res/drawable-v36/sesl_vector_drawable_progress.xml`. The APK also has the older solid-dot default variant; the pinned SESL9 dependency ships that older artwork.

The app's `oneui85_*` XML resources preserve the extracted vector paths, gradients, groups, independent X/Y tracks, timings and interpolators. Resource names are prefixed to avoid collisions; AAPT-generated gradient files are inlined with `aapt:attr`, and authoring-only AAPT labels/fps metadata is omitted. Default resource folders make the gradient artwork available across the app's supported Android versions, rather than only API 36+.

The old hand-built backport incorrectly gave the opacity interpolator a `controlY1` of `1.399`, causing the two affected dots to exceed full opacity as they faded back in. In this APK, the resource name still contains `1_399` but the actual control value is `1.0`. Preserve the XML value, not the misleading name. An instrumentation regression samples the opacity throughout this transition.

The AVD has a 2000 ms cycle. `LoadingSpinner` retains SESL progress-bar looping with a callback-stop guard for hidden/detached views. Widgets use the framework `ProgressBar`; a drawable alias supplies the same animation to SESL's native pull-to-refresh control without replacing its internal ImageView or gesture handling.
