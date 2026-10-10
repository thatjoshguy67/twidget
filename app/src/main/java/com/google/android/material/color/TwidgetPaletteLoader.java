package com.google.android.material.color;

import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import java.util.Map;

/**
 * Adapter to the resource-table writer in our pinned SESL Material dependency.
 * Kept in this package to access its package-private factory without reflection.
 * Unlike ColorResourcesOverride, this does not install a Material theme overlay.
 * Recheck this adapter when upgrading SESL Material.
 */
@RequiresApi(30)
public final class TwidgetPaletteLoader {
    private TwidgetPaletteLoader() {}

    @Nullable
    public static ResourcesLoader create(Context context, Map<Integer, Integer> colors) {
        return ColorResourcesLoaderCreator.create(context, colors);
    }
}
