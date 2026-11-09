package dev.tvhome;

import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.ImageView;

/** One app on the grid. Filled in off the main thread; `view` is set and used on the main thread. */
final class Entry {
    String key, pkg;
    long updated; // the package's lastUpdateTime; 0 when unknown (then never cached)
    CharSequence name;
    Intent intent;
    Bitmap banner, wash;
    int accent;
    ImageView view;

    /** Same app and tile, no view. Bitmaps are shared: they are never recycled once baked. */
    Entry copy() {
        Entry e = new Entry();
        e.key = key;
        e.pkg = pkg;
        e.updated = updated;
        e.name = name;
        e.intent = new Intent(intent);
        e.banner = banner;
        e.wash = wash;
        e.accent = accent;
        return e;
    }
}
