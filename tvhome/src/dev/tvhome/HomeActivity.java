package dev.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.tvhome.Drawables.Glyph;
import dev.tvhome.Drawables.PillGlow;
import dev.tvhome.Drawables.Shine;

/**
 * Lightweight tvOS-style home screen: a row-based grid of app banners over a soft
 * color wash taken from the focused app. Framework-only, no libraries.
 */
@SuppressWarnings("unused") // started by the system through AndroidManifest.xml, not from code
public final class HomeActivity extends Activity {
    private static final String TAG = "tvhome";
    private static final int COLS = 5;
    private static final String SETTINGS_PKG = "com.android.tv.settings/";
    private static final String PREF_ORDER = "order", PREF_HIDDEN = "hidden";

    // Layout in dp, for the 960x540dp TV canvas.
    private static final float SIDE = 64, GAP = 32, ROW_GAP = 46, TILE_W = 140;
    private static final float SHELF_ROW_Y = 278;
    private static final float FOCUS_SCALE = 1.12f, MOVE_SCALE = 1.2f;
    // Focused-row panel: padding around the tiles (and their label) and the glow margin, in dp.
    private static final float ROW_PAD_X = 22, ROW_PAD_TOP = 16, ROW_PAD_BOTTOM = 38, ROW_GLOW = 18, ROW_RADIUS = 24;
    private static final long RELOAD_DEBOUNCE_MS = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private final List<Entry> entries = new ArrayList<>();
    // One shared shine, attached only to the focused tile so idle tiles cost nothing.
    private final Shine shine = new Shine();

    private SharedPreferences prefs;
    private Ambient ambient;
    private Glass glass;
    private TileMenu menu;
    private TileCache cache;
    private Load lastLoad; // the shown load, hidden apps included; hide/unhide re-show it
    private FrameLayout grid;
    private FrameLayout rowPanel;
    private ImageView rowGlow;
    private int panelW, panelH, glowColor = Color.WHITE;
    private final ValueAnimator glowAnim = ValueAnimator.ofArgb(Color.WHITE, Color.WHITE);
    private ImageView washFront, washBack;
    private TextView title, clock, date, label, hint, wifiText;
    private LinearLayout shortcuts;
    private Glyph wifiGlyph;
    private float density;
    private int tileW, tileH, colStep, rowStep, radius;
    private int focusedRow = -1;
    private Entry focused, moving;
    private boolean restoring; // show() is moving focus to a reloaded copy of the focused tile
    private Bitmap shownWash;

    private final ViewOutlineProvider rounded = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
        }
    };

    // Main thread only. Every load gets a number; only the newest one's result is shown.
    private int loadGen;
    private final Runnable reloadNow = this::reload;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        // An app update sends several broadcasts in a row: load once, after they settle.
        @Override public void onReceive(Context c, Intent i) {
            main.removeCallbacks(reloadNow);
            main.postDelayed(reloadNow, RELOAD_DEBOUNCE_MS);
        }
    };

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        tileW = dp(TILE_W);
        tileH = tileW * 9 / 16;
        colStep = tileW + dp(GAP);
        rowStep = tileH + dp(ROW_GAP);
        radius = dp(12);
        cache = new TileCache(new File(getCacheDir(), "tiles"), bannerW(), bannerW() * 9 / 16);

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        washBack = washView();
        washFront = washView();
        root.addView(washBack, fill());
        root.addView(washFront, fill());
        washBack.setImageDrawable(new ColorDrawable(Color.BLACK));
        glass = new Glass(washFront, washBack, density);

        title = text(42, true);
        title.setMaxLines(1);
        FrameLayout.LayoutParams tp = wrap(Gravity.TOP | Gravity.START);
        tp.leftMargin = dp(SIDE);
        tp.topMargin = dp(196);
        root.addView(title, tp);

        grid = new FrameLayout(this);
        grid.setClipChildren(false);
        root.addView(grid, fill());

        panelW = COLS * colStep - dp(GAP) + 2 * dp(ROW_PAD_X) + 2 * dp(ROW_GLOW);
        panelH = tileH + dp(ROW_PAD_TOP) + dp(ROW_PAD_BOTTOM) + 2 * dp(ROW_GLOW);
        rowPanel = new FrameLayout(this);
        rowGlow = new ImageView(this);
        rowGlow.setImageBitmap(drawRowPanel(true));
        rowGlow.setColorFilter(glowColor, PorterDuff.Mode.SRC_IN);
        rowPanel.addView(rowGlow, fill());
        if (glass.enabled()) {
            // The glass sits inside the glow margin; it is the panel's own body, not a bitmap.
            View body = new View(this);
            body.setBackground(glass.surface(body, new Glass.Style().radius(ROW_RADIUS)
                    .bevel(16).refract(14).frost(0.13f).shine(0.65f)));
            FrameLayout.LayoutParams bl = fill();
            bl.setMargins(dp(ROW_GLOW), dp(ROW_GLOW), dp(ROW_GLOW), dp(ROW_GLOW));
            rowPanel.addView(body, bl);
        } else {
            ImageView body = new ImageView(this);
            body.setImageBitmap(drawRowPanel(false));
            rowPanel.addView(body, fill());
        }
        rowPanel.setFocusable(false);
        rowPanel.setAlpha(0f);
        // Behind the tiles: first child of the grid, so it scrolls with the rows.
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(panelW, panelH);
        pl.leftMargin = dp(SIDE) - dp(ROW_PAD_X) - dp(ROW_GLOW);
        grid.addView(rowPanel, pl);
        glowAnim.setDuration(320);
        glowAnim.addUpdateListener(a -> {
            glowColor = (int) a.getAnimatedValue();
            rowGlow.setColorFilter(glowColor, PorterDuff.Mode.SRC_IN);
        });

        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        when.setGravity(Gravity.END);
        clock = text(28, false);
        date = text(14, false);
        date.setAlpha(0.75f);
        // Children of a vertical LinearLayout default to full width, so align the text itself.
        clock.setGravity(Gravity.END);
        date.setGravity(Gravity.END);
        when.addView(clock);
        when.addView(date);
        FrameLayout.LayoutParams cp = wrap(Gravity.TOP | Gravity.END);
        cp.topMargin = dp(28);
        cp.rightMargin = dp(SIDE);
        root.addView(when, cp);

        shortcuts = new LinearLayout(this);
        shortcuts.setClipChildren(false);
        shortcuts.addView(pill(Glyph.WIFI, "Wi-Fi", new Intent(Settings.ACTION_WIFI_SETTINGS)));
        shortcuts.addView(pill(Glyph.PAD, "Parear controle", new Intent("com.google.android.intent.action.CONNECT_INPUT")));
        shortcuts.addView(pill(Glyph.TV, "Imagem e som", new Intent(Settings.ACTION_SOUND_SETTINGS)));
        shortcuts.addView(pill(Glyph.MOON, "Modo ambiente", null));
        FrameLayout.LayoutParams sp = wrap(Gravity.TOP | Gravity.START);
        sp.topMargin = dp(34);
        sp.leftMargin = dp(SIDE);
        root.addView(shortcuts, sp);

        label = text(15, true);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setMaxLines(1);
        // Also in the grid so it scrolls with it; kept above the tiles (see show and moveBy).
        grid.addView(label, new FrameLayout.LayoutParams(Math.round(tileW * 1.5f), FrameLayout.LayoutParams.WRAP_CONTENT));

        hint = text(16, false);
        hint.setText("Use as setas para mover  •  OK para soltar");
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = wrap(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(28);
        if (glass.enabled()) {
            // A glass capsule around it; the margin shrinks by the padding so the text stays put.
            hint.setPadding(dp(20), dp(8), dp(20), dp(8));
            hint.setBackground(glass.surface(hint, new Glass.Style().radius(19).bevel(8).refract(7)
                    .frost(0.14f).shine(0.7f)));
            hp.bottomMargin = dp(20);
        }
        root.addView(hint, hp);

        menu = new TileMenu(this, root, glass);

        // Last, so the hand-off overlay covers everything above.
        ambient = new Ambient(this, root, prefs, main);

        // The opaque wash is the only full-screen layer: no window or root background.
        getWindow().setBackgroundDrawable(null);
        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(network);
        ambient.register();

        reload();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(reloadNow);
        ambient.unregister();
        unregisterReceiver(packagesChanged);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        updateClock();
    }

    @Override protected void onPause() {
        unregisterReceiver(tick);
        menu.close();
        if (moving != null) endMove();
        super.onPause();
    }

    /** Pressing Home while already home jumps back to the first app, like tvOS. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        menu.close();
        if (moving != null) endMove();
        if (!entries.isEmpty()) entries.get(0).view.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent ev) {
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (ambient.isActive()) {
            // Any key during the hand-off means "never mind".
            if (down) ambient.cancel();
            return true;
        }
        // Home is the root screen: Back never leaves it, it only closes the menu or drops an app
        // being moved. (Handled here instead of the deprecated onBackPressed.)
        int code = ev.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (down) {
                if (menu.isOpen()) menu.close();
                else if (moving != null) endMove();
            }
            return true;
        }
        // Ups always go through, so the tile's pressed state from the long-press clears.
        if (!down || (moving == null && !menu.isOpen())) return super.dispatchKeyEvent(ev);
        // OK is still held from the long-press that opened the menu (and, after "Mover", from
        // the press that chose it) and keeps repeating: only a fresh press acts.
        boolean fresh = ev.getRepeatCount() == 0;
        if (menu.isOpen()) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP -> menu.move(-1);
                case KeyEvent.KEYCODE_DPAD_DOWN -> menu.move(1);
                case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (fresh) menu.choose();
                }
                default -> { }
            }
            return true;
        }
        switch (code) {
            // Arrow repeats count: holding an arrow keeps moving the tile.
            case KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1);
            case KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(1);
            case KeyEvent.KEYCODE_DPAD_UP -> moveBy(-COLS);
            case KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(COLS);
            case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (fresh) endMove();
            }
            default -> { }
        }
        return true;
    }

    // ---- Top bar: clock, network state and shortcuts ----------------------------------------

    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            boolean ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            main.post(() -> setOnline(ok));
        }

        @Override public void onLost(Network n) { main.post(() -> setOnline(false)); }
    };

    private static final String BT = "android.permission.BLUETOOTH_CONNECT";
    private Intent pendingShortcut;

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (pendingShortcut != null && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            try { startActivity(pendingShortcut); } catch (RuntimeException ignored) { }
        }
        pendingShortcut = null;
    }

    private void setOnline(boolean online) {
        wifiText.setText(online ? "Wi-Fi" : "Sem internet");
        wifiGlyph.off = !online;
        wifiGlyph.invalidateSelf();
    }

    private View pill(int glyph, String text, Intent intent) {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(14), dp(7), dp(16), dp(7));
        GradientDrawable bg = null;
        Glass.Surface pane = null;
        if (glass.enabled()) {
            // Focused: lit, bright glass (the wash's color still shows through) under dark text.
            pane = glass.surface(v, new Glass.Style().radius(18).bevel(8).refract(7).frost(0.13f).shine(0.7f));
        } else {
            bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(0x26FFFFFF);
        }
        PillGlow glow = new PillGlow(dp(10), dp(18));
        v.setBackground(new LayerDrawable(new Drawable[] {glow, pane != null ? pane : bg}));
        v.setFocusable(true);

        Glyph g = new Glyph(glyph);
        g.setColor(Color.WHITE);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(g);
        v.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView t = text(14, true);
        t.setText(text);
        t.setShadowLayer(0, 0, 0, 0);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(8);
        v.addView(t, tl);

        if (glyph == Glyph.WIFI) { wifiGlyph = g; wifiText = t; }
        if (intent != null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickListener(x -> {
            if (glyph == Glyph.MOON) {
                menu.close();
                ambient.start(clock.getText(), date.getText());
                return;
            }
            if (glyph == Glyph.PAD && checkSelfPermission(BT) != PackageManager.PERMISSION_GRANTED) {
                pendingShortcut = intent;
                requestPermissions(new String[] {BT}, 1);
                return;
            }
            try { startActivity(intent); } catch (RuntimeException ignored) { }
        });
        GradientDrawable flatBg = bg;
        Glass.Surface glassBg = pane;
        v.setOnFocusChangeListener((x, has) -> {
            if (glassBg != null) glassBg.lift(has, 150);
            else flatBg.setColor(has ? 0xF2FFFFFF : 0x26FFFFFF);
            int fg = has ? 0xFF15161A : Color.WHITE;
            t.setTextColor(fg);
            g.setColor(fg);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).setInterpolator(ease).start();
            ValueAnimator fade = ValueAnimator.ofInt(has ? 0 : 255, has ? 255 : 0);
            fade.setDuration(150);
            fade.addUpdateListener(a -> glow.setAlpha((int) a.getAnimatedValue()));
            fade.start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    private void updateClock() {
        long now = System.currentTimeMillis();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        String d = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now).toString();
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
    }

    // ---- Loading -------------------------------------------------------------------------

    /** One load: the apps that loaded, and the keys of every launchable app, loaded or not. */
    private static final class Load {
        final String locales;
        final List<Entry> apps = new ArrayList<>();
        final Set<String> keys = new HashSet<>();

        Load(String locales) { this.locales = locales; }
    }

    /** A tile rendered from the app's own resources, to be written to the cache afterward. */
    private static final class Fresh {
        final Entry entry;
        final Bitmap banner; // CPU copy of the banner; null for the code-drawn Settings tile

        Fresh(Entry entry, Bitmap banner) {
            this.entry = entry;
            this.banner = banner;
        }
    }

    private void reload() {
        int gen = ++loadGen;
        new Thread(() -> {
            List<Fresh> fresh = new ArrayList<>();
            Load load;
            try {
                load = loadEntries(fresh);
            } catch (Throwable t) {
                // Keep the grid already on screen: as the home app, dying here would repeat on
                // every restart and leave the TV with no launcher.
                Log.w(TAG, "app list load failed", t);
                return;
            }
            // Copied before the entries reach the main thread, which then owns them. Only
            // reference copies: no IO, no real delay.
            try {
                cache.remember(load.apps, load.locales);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "tile cache remember failed", e);
            }
            // Loads can overlap and finish in any order; a superseded one is just dropped.
            main.post(() -> { if (gen == loadGen) show(load); });

            // File upkeep only once the grid is on its way, so it never delays it.
            try {
                for (Fresh f : fresh) {
                    cache.write(f.entry.key, f.entry.updated, load.locales, f.entry.name, f.banner);
                }
                cache.prune(load.keys);
            } catch (Throwable t) {
                Log.w(TAG, "tile cache upkeep failed", t);
            } finally {
                for (Fresh f : fresh) if (f.banner != null) f.banner.recycle();
            }
        }, "home-load").start();
    }

    /** Off the main thread. An app whose label or banner fails to load is left out, not fatal. */
    private Load loadEntries(List<Fresh> fresh) {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        int bw = bannerW(), bh = bw * 9 / 16;

        Load load = new Load(getResources().getConfiguration().getLocales().toLanguageTags());
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(getPackageName())) continue;
            load.keys.add(key(ai));
            try {
                load.apps.add(loadEntry(pm, ri, bw, bh, load.locales, fresh));
            } catch (RuntimeException | OutOfMemoryError e) {
                // OOM included: a huge banner bitmap fails alone, its memory is freed with it.
                Log.w(TAG, "skipping " + key(ai), e);
            }
        }
        return load;
    }

    /**
     * One app's tile: reused from the last load when the package is unchanged, else read from its
     * cache file, else rendered from the app's own resources (and queued in `fresh` for caching).
     * Only that last path loads anything from the other app.
     */
    private Entry loadEntry(PackageManager pm, ResolveInfo ri, int bw, int bh, String locales, List<Fresh> fresh) {
        ActivityInfo ai = ri.activityInfo;
        String key = key(ai);
        long updated = lastUpdate(pm, ai.packageName);
        boolean settings = key.startsWith(SETTINGS_PKG);
        if (updated != 0) {
            Entry hit = cache.fromMemory(key, updated, locales);
            if (hit != null) return hit;
        }

        Entry e = new Entry();
        e.key = key;
        e.pkg = ai.packageName;
        e.updated = updated;
        e.intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(ai.packageName, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        if (updated != 0) {
            TileCache.Stored s = cache.read(key, updated, locales);
            if (s != null) {
                // Settings is drawn in code; only its label is cached.
                if (settings && s.banner != null) s.banner.recycle();
                Bitmap sw = settings ? Banners.drawSettings(bw, bh) : s.banner;
                if (sw != null) {
                    e.name = s.name;
                    Banners.bake(e, sw);
                    return e;
                }
            }
        }

        e.name = ri.loadLabel(pm);
        Bitmap sw;
        if (settings) {
            sw = Banners.drawSettings(bw, bh);
        } else {
            Drawable banner = ai.loadBanner(pm);
            sw = banner != null ? Banners.drawBanner(banner, bw, bh) : Banners.drawIcon(ri.loadIcon(pm), bw, bh);
        }
        // bake moves sw to the GPU and frees it: keep a CPU copy to write the cache file later.
        Bitmap copy = updated == 0 || settings ? null : sw.copy(Bitmap.Config.ARGB_8888, false);
        Banners.bake(e, sw);
        if (updated != 0 && (settings || copy != null)) fresh.add(new Fresh(e, copy));
        return e;
    }

    private static String key(ActivityInfo ai) { return ai.packageName + "/" + ai.name; }

    /** The package's lastUpdateTime, or 0 if it can't be read (then the app is never cached). */
    private static long lastUpdate(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return 0;
        }
    }

    /** Banners are rendered at the moving-tile size, the largest a tile is ever drawn. */
    private int bannerW() { return Math.round(tileW * MOVE_SCALE); }

    /**
     * Saved order first; new apps alphabetically after it, system Settings last. Runs on the main
     * thread at show time, so it sees an order saved while the load was still running.
     */
    private List<Entry> order(List<Entry> loaded) {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Entry e : loaded) byKey.put(e.key, e);
        List<Entry> out = new ArrayList<>();
        for (String k : prefs.getString(PREF_ORDER, "").split("\n")) {
            Entry e = byKey.remove(k);
            if (e != null) out.add(e);
        }
        List<Entry> rest = new ArrayList<>(byKey.values());
        Collator c = Collator.getInstance();
        rest.sort((a, b) -> {
            boolean sa = isSettings(a), sb = isSettings(b);
            return sa != sb ? (sa ? 1 : -1) : c.compare(a.name.toString(), b.name.toString());
        });
        out.addAll(rest);
        return out;
    }

    private static boolean isSettings(Entry e) { return e.key.startsWith(SETTINGS_PKG); }

    // ---- Grid ----------------------------------------------------------------------------

    /**
     * Puts a load on screen (a fresh one, or the current one again after hiding or un-hiding an
     * app, in which case Entry objects are reused with new views). New tiles go in before the old
     * ones come out, so the focused view is never removed (that would bounce focus to a shortcut
     * pill). On a reload the row, title, label, wash and scroll stay as they were, and so does focus.
     */
    private void show(Load load) {
        if (isDestroyed()) return;
        if (load.apps.isEmpty() && !entries.isEmpty()) {
            // Every app failed to load (Settings alone always exists): keep the grid we have.
            Log.w(TAG, "load returned no apps; keeping the current grid");
            return;
        }
        // The menu's actions hold Entry objects of the grid being replaced.
        menu.close();
        // The new grid has new Entry objects, so a move in progress can't carry over: drop the
        // tile where the user has it now. endMove saves that order, which order() then reads.
        if (moving != null) endMove();
        lastLoad = load;

        Set<String> hidden = hiddenKeys();
        if (hidden.retainAll(load.keys)) saveHidden(hidden); // forget uninstalled apps
        List<Entry> visible = new ArrayList<>();
        for (Entry e : load.apps) if (!hidden.contains(e.key)) visible.add(e);
        // Never lock the user out: with nothing left to show, show the hidden apps too.
        if (visible.isEmpty()) visible.addAll(load.apps);
        List<Entry> list = order(visible);

        // Captured before the loop: reused entries get a new view in it.
        List<Entry> old = new ArrayList<>(entries);
        List<View> oldViews = new ArrayList<>();
        for (Entry e : old) oldViews.add(e.view);
        Entry was = focused;
        View wasView = was != null ? was.view : null;
        View current = getCurrentFocus();

        entries.clear();
        entries.addAll(list);
        Entry target = null;
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            e.view = tile(e);
            grid.addView(e.view, new FrameLayout.LayoutParams(tileW, tileH));
            place(e, i, false);
            if (was != null && e.key.equals(was.key)) target = e;
        }
        label.bringToFront();

        if (was == null) {
            // First load: start on the first app, with the usual focus animation.
            if (!list.isEmpty()) list.get(0).view.requestFocus();
        } else {
            boolean same = target != null;
            // The app is gone (uninstalled or hidden): use whichever app took its place.
            if (!same) target = list.get(Math.min(Math.max(old.indexOf(was), 0), list.size() - 1));
            if (current != null && current != wasView) {
                // Focus is on a shortcut pill: leave it there, keep the backdrop on the app. The
                // pills hide when the grid scrolls past the shelf, so stay on the shelf.
                if (entries.indexOf(target) / COLS > 1) target = list.get(0);
                restore(target);
            } else if (same && current != null) {
                // Carry the focused look over to the new tile instead of replaying it.
                target.view.setScaleX(FOCUS_SCALE);
                target.view.setScaleY(FOCUS_SCALE);
                target.view.setTranslationZ(dp(16));
                restoring = true;
                try {
                    target.view.requestFocus();
                } finally {
                    restoring = false;
                }
                restore(target);
            } else {
                target.view.requestFocus();
            }
        }
        for (View v : oldViews) grid.removeView(v);
    }

    /** Points label, title, wash and row panel at e without the focus effects (after a reload). */
    private void restore(Entry e) {
        focused = e;
        int i = entries.indexOf(e);
        label.setText(e.name);
        placeLabel(i);
        setWash(e);
        scrollTo(i / COLS);
    }

    private ImageView tile(Entry e) {
        ImageView v = new ImageView(this);
        v.setImageBitmap(e.banner);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setFocusable(true);
        v.setOutlineProvider(rounded);
        v.setClipToOutline(true);
        v.setContentDescription(e.name);
        v.setOnClickListener(x -> launch(e));
        v.setOnLongClickListener(x -> { openMenu(e); return true; });
        v.setOnFocusChangeListener((x, has) -> onTileFocus(e, x, has));
        return v;
    }

    private float tileX(int i) { return dp(SIDE) + (i % COLS) * colStep; }
    private float tileY(int i) {
        int row = i / COLS; // integer division on purpose: index -> row
        return dp(SHELF_ROW_Y) + row * rowStep;
    }

    /** Real layout position (focus search ignores translation); moves animate via translation. */
    private void place(Entry e, int i, boolean animate) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) e.view.getLayoutParams();
        int x = Math.round(tileX(i)), y = Math.round(tileY(i));
        if (animate) {
            e.view.setTranslationX(lp.leftMargin - x + e.view.getTranslationX());
            e.view.setTranslationY(lp.topMargin - y + e.view.getTranslationY());
            e.view.animate().translationX(0).translationY(0).setDuration(180).setInterpolator(ease).start();
        }
        lp.leftMargin = x;
        lp.topMargin = y;
        e.view.setLayoutParams(lp);
    }

    /** v, not e.view: when show() reuses an Entry, its old view loses focus after e.view is replaced. */
    private void onTileFocus(Entry e, View v, boolean has) {
        float s = has ? (e == moving ? MOVE_SCALE : FOCUS_SCALE) : 1f;
        v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(16) : 0)
                .setDuration(170).setInterpolator(ease).start();
        v.setForeground(has ? shine : null);
        if (!has) return;
        focused = e;
        if (restoring) return; // show() puts the rest back as it was, without the effects
        shine.sweep();
        int i = entries.indexOf(e);
        showLabel(e, i);
        setWash(e);
        scrollTo(i / COLS);
    }

    private void showLabel(Entry e, int i) {
        label.setText(e.name);
        placeLabel(i);
        label.setAlpha(0f);
        label.animate().alpha(1f).setDuration(200).start();
    }

    private void placeLabel(int i) {
        label.setX(tileX(i) - tileW * 0.25f);
        label.setY(tileY(i) + tileH * (1 + (MOVE_SCALE - 1) / 2) + dp(6));
    }

    /** The first two rows fit under the shelf; deeper rows scroll up into the second row's slot. */
    private void scrollTo(int row) {
        if (row == focusedRow) return;
        boolean wasShelf = focusedRow <= 1, first = focusedRow < 0;
        focusedRow = row;
        float panelY = tileY(row * COLS) - dp(ROW_PAD_TOP) - dp(ROW_GLOW);
        if (first) {
            rowPanel.setTranslationY(panelY);
            rowPanel.animate().alpha(1f).setDuration(300).start();
        } else {
            rowPanel.animate().translationY(panelY).setDuration(240).setInterpolator(ease).start();
            // Glow dips while sliding and settles bright on the newly selected row.
            ObjectAnimator pulse = ObjectAnimator.ofFloat(rowPanel, View.ALPHA, 0.55f, 1f);
            pulse.setStartDelay(120);
            pulse.setDuration(260);
            pulse.start();
        }
        boolean shelf = row <= 1;
        grid.animate().translationY(shelf ? 0 : -(row - 1) * rowStep).setDuration(280).setInterpolator(ease).start();
        if (shelf != wasShelf) {
            title.animate().alpha(shelf ? 1f : 0f).translationY(shelf ? 0 : -dp(24)).setDuration(220).start();
            shortcuts.animate().alpha(shelf ? 1f : 0f).setDuration(220).start();
        }
    }

    private void setWash(Entry e) {
        title.setText(e.name);
        if (e.wash == shownWash) return;
        shownWash = e.wash;
        glowAnim.cancel();
        glowAnim.setIntValues(glowColor, e.accent);
        glowAnim.start();
        if (washFront.getDrawable() != null) washBack.setImageDrawable(washFront.getDrawable());
        washBack.setVisibility(View.VISIBLE);
        washFront.setImageBitmap(e.wash);
        washFront.setAlpha(0f);
        washFront.animate().alpha(1f).setDuration(380)
                .withEndAction(() -> washBack.setVisibility(View.GONE)).start();
    }

    private void launch(Entry e) {
        if (moving != null || menu.isOpen()) return;
        try {
            startActivity(e.intent);
        } catch (RuntimeException ignored) {
            // App vanished or refused; the package receiver will refresh the grid.
        }
    }

    /**
     * Frosted rounded panel, rendered once and only translated afterward. The glow is a separate
     * white mask so it can be tinted per app with a color filter instead of being re-rasterized.
     */
    private Bitmap drawRowPanel(boolean glowOnly) {
        int glow = dp(ROW_GLOW);
        int w = panelW, h = panelH;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        RectF r = new RectF(glow, glow, w - glow, h - glow);
        float rad = dp(ROW_RADIUS);

        if (glowOnly) {
            Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
            halo.setColor(0x80FFFFFF);
            halo.setMaskFilter(new BlurMaskFilter(glow * 0.8f, BlurMaskFilter.Blur.OUTER));
            c.drawRoundRect(r, rad, rad, halo);
            return Banners.toGpu(b);
        }

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x24FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);

        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp(1));
        edge.setColor(0x47FFFFFF);
        r.inset(dp(0.5f), dp(0.5f));
        c.drawRoundRect(r, rad, rad, edge);
        return Banners.toGpu(b);
    }

    // ---- Reordering ----------------------------------------------------------------------

    private void startMove(Entry e) {
        if (moving != null || !entries.contains(e)) return; // stale menu action: tile is gone
        moving = e;
        e.view.animate().scaleX(MOVE_SCALE).scaleY(MOVE_SCALE).setDuration(150).start();
        e.view.setAlpha(0.85f);
        hint.setVisibility(View.VISIBLE);
    }

    private void moveBy(int d) {
        int i = entries.indexOf(moving), j = i + d;
        if (i < 0 || j < 0 || j >= entries.size()) return; // i < 0: stale tile, never remove(-1)
        entries.remove(i);
        entries.add(j, moving);
        for (int k = Math.min(i, j); k <= Math.max(i, j); k++) place(entries.get(k), k, true);
        moving.view.bringToFront();
        label.bringToFront();
        showLabel(moving, j);
        scrollTo(j / COLS);
    }

    private void endMove() {
        Entry e = moving;
        moving = null;
        e.view.setAlpha(1f);
        float s = e.view.hasFocus() ? FOCUS_SCALE : 1f;
        e.view.animate().scaleX(s).scaleY(s).setDuration(150).start();
        hint.setVisibility(View.GONE);
        StringBuilder sb = new StringBuilder();
        for (Entry x : entries) sb.append(x.key).append('\n');
        prefs.edit().putString(PREF_ORDER, sb.toString()).apply();
    }

    // ---- Long-press menu -----------------------------------------------------------------

    private void openMenu(Entry e) {
        if (moving != null || !entries.contains(e)) return;
        List<TileMenu.Item> items = new ArrayList<>();
        items.add(new TileMenu.Item("Mover", () -> startMove(e)));
        // Settings can't be hidden: it's the way back to everything else.
        if (!isSettings(e)) items.add(new TileMenu.Item("Ocultar", () -> setHidden(e.key, true)));
        items.add(new TileMenu.Item("Informações do app", () -> openAppInfo(e)));
        List<Entry> hiddenApps = hiddenApps();
        if (!hiddenApps.isEmpty()) {
            items.add(new TileMenu.Item("Apps ocultos (" + hiddenApps.size() + ")", () -> openHiddenMenu(hiddenApps)));
        }
        menu.open(e.name, items);
    }

    private void openHiddenMenu(List<Entry> apps) {
        List<TileMenu.Item> items = new ArrayList<>();
        for (Entry h : apps) items.add(new TileMenu.Item(h.name.toString(), () -> setHidden(h.key, false)));
        menu.open("Apps ocultos", items);
    }

    /** Hidden apps that are installed and loaded, by name. */
    private List<Entry> hiddenApps() {
        List<Entry> out = new ArrayList<>();
        if (lastLoad == null) return out;
        Set<String> hidden = hiddenKeys();
        for (Entry e : lastLoad.apps) if (hidden.contains(e.key)) out.add(e);
        Collator c = Collator.getInstance();
        out.sort((a, b) -> c.compare(a.name.toString(), b.name.toString()));
        return out;
    }

    /** Re-shows the current load: no reload needed, every app (hidden ones too) is in it. */
    private void setHidden(String key, boolean hide) {
        Set<String> hidden = hiddenKeys();
        if (!(hide ? hidden.add(key) : hidden.remove(key))) return;
        saveHidden(hidden);
        if (lastLoad != null) show(lastLoad);
    }

    private Set<String> hiddenKeys() {
        Set<String> s = new HashSet<>();
        for (String k : prefs.getString(PREF_HIDDEN, "").split("\n")) if (!k.isEmpty()) s.add(k);
        return s;
    }

    private void saveHidden(Set<String> s) {
        prefs.edit().putString(PREF_HIDDEN, String.join("\n", s)).apply();
    }

    private void openAppInfo(Entry e) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    // ---- Helpers -------------------------------------------------------------------------

    private ImageView washView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        return v;
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        t.setFocusable(false);
        return t;
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams wrap(int gravity) {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
    }

    private int dp(float v) { return Math.round(v * density); }
}
