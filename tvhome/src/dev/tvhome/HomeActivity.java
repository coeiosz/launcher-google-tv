package dev.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
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
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.graphics.BlurMaskFilter;
import android.graphics.RectF;
import android.graphics.drawable.LayerDrawable;


import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight tvOS-style home screen: a row-based grid of app banners over a soft
 * color wash taken from the focused app. Framework-only, no libraries.
 */
@SuppressWarnings("unused") // started by the system through AndroidManifest.xml, not from code
public final class HomeActivity extends Activity {
    private static final int COLS = 5;
    private static final String SETTINGS_PKG = "com.android.tv.settings/";
    private static final String PREF_ORDER = "order";

    // Layout in dp, for the 960x540dp TV canvas.
    private static final float SIDE = 64, GAP = 32, ROW_GAP = 46, TILE_W = 140;
    private static final float SHELF_ROW_Y = 278;
    private static final float FOCUS_SCALE = 1.12f, MOVE_SCALE = 1.2f;
    // Focused-row panel: padding around the tiles (and their label) and the glow margin, in dp.
    private static final float ROW_PAD_X = 22, ROW_PAD_TOP = 16, ROW_PAD_BOTTOM = 38, ROW_GLOW = 18, ROW_RADIUS = 24;

    // The wash is blurred at this tiny size and upscaled by the GPU for free.
    private static final int WASH_W = 64, WASH_H = 36;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private final List<Entry> entries = new ArrayList<>();

    private SharedPreferences prefs;
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
    private Bitmap shownWash;

    private static final class Entry {
        String key;
        CharSequence name;
        Intent intent;
        Bitmap banner, wash;
        int accent;
        ImageView view;
    }

    private final ViewOutlineProvider rounded = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
        }
    };

    private final Shine shine = new Shine();

    /** Glass highlight for the focused tile: a soft top gloss plus one diagonal sweep on focus. */
    private final class Shine extends Drawable {
        private final Paint gloss = new Paint(), band = new Paint();
        private final Matrix shift = new Matrix();
        private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        private LinearGradient bandShader;
        private float pos = -1f;

        Shine() {
            anim.setDuration(750);
            anim.setInterpolator(ease);
            anim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidateSelf(); });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { pos = -1f; invalidateSelf(); }
            });
        }

        void sweep() {
            anim.cancel();
            anim.start();
        }

        @Override protected void onBoundsChange(Rect b) {
            int h = b.height(), bw = Math.round(h * 0.9f);
            gloss.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            bandShader = new LinearGradient(0, 0, bw, bw, new int[] {0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            band.setShader(bandShader);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            c.drawRect(b.left, b.top, b.right, b.top + b.height() * 0.55f, gloss);
            if (pos < 0 || bandShader == null) return;
            float span = b.width() + b.height() * 1.8f;
            shift.setTranslate(b.left - b.height() * 1.8f + pos * span, b.top);
            bandShader.setLocalMatrix(shift);
            c.drawRect(b, band);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
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

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        washBack = washView();
        washFront = washView();
        root.addView(washBack, fill());
        root.addView(washFront, fill());
        washBack.setImageDrawable(new ColorDrawable(Color.BLACK));

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
        ImageView body = new ImageView(this);
        body.setImageBitmap(drawRowPanel(false));
        rowPanel.addView(rowGlow, fill());
        rowPanel.addView(body, fill());
        rowPanel.setFocusable(false);
        rowPanel.setAlpha(0f);
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

        hint = text(16, false);
        hint.setText("Use as setas para mover  •  OK para soltar");
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = wrap(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(28);
        root.addView(hint, hp);

        // Ambient hand-off: a plain black layer (cheap to fade) plus a centred clock, both on top.
        // The layers end exactly as PhotoDream starts (same photo, same corner clock), so the
        // screensaver takes over with no visible cut.
        ambientShade = new View(this);
        ambientShade.setBackgroundColor(Color.BLACK);
        root.addView(ambientShade, fill());

        ambientPhoto = new ImageView(this);
        ambientPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(ambientPhoto, fill());
        ambientPhotoShade = PhotoDream.addBottomShade(root);

        ambientIntro = new LinearLayout(this);
        ambientIntro.setOrientation(LinearLayout.VERTICAL);
        ambientClock = PhotoDream.dreamText(this, 76, "sans-serif-light");
        TextView caption = PhotoDream.dreamText(this, 16, "sans-serif");
        caption.setText("Modo ambiente");
        caption.setAlpha(0.75f);
        ambientProgress = new View(this);
        ambientProgress.setBackgroundColor(0x99FFFFFF);
        ambientIntro.addView(ambientClock, centered(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        ambientIntro.addView(caption, centered(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        ambientIntro.addView(ambientProgress, centered(dp(140), dp(2), dp(16)));
        root.addView(ambientIntro, wrap(Gravity.CENTER));

        // Same views, sizes and margins as PhotoDream's clock block.
        ambientCorner = new LinearLayout(this);
        ambientCorner.setOrientation(LinearLayout.VERTICAL);
        ambientCorner.setGravity(Gravity.END);
        ambientCornerClock = PhotoDream.dreamText(this, 56, "sans-serif-light");
        ambientCornerDate = PhotoDream.dreamText(this, 18, "sans-serif");
        ambientCornerDate.setAlpha(0.85f);
        ambientCornerClock.setGravity(Gravity.END);
        ambientCornerDate.setGravity(Gravity.END);
        ambientCorner.addView(ambientCornerClock);
        ambientCorner.addView(ambientCornerDate);
        FrameLayout.LayoutParams acp = wrap(Gravity.BOTTOM | Gravity.END);
        acp.rightMargin = dp(36);
        acp.bottomMargin = dp(22);
        root.addView(ambientCorner, acp);

        for (View v : ambientLayers()) v.setVisibility(View.GONE);

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
        registerReceiver(dreaming, new IntentFilter(Intent.ACTION_DREAMING_STARTED));
        restoreTimeout(); // in case a previous run died during the hand-off

        reload();
    }

    @Override protected void onDestroy() {
        restoreTimeout();
        unregisterReceiver(dreaming);
        unregisterReceiver(packagesChanged);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
        super.onDestroy();
    }

    // ---- Top bar: date and shortcuts --------------------------------------------------------

    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            boolean ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            main.post(() -> setOnline(ok));
        }

        @Override public void onLost(Network n) { main.post(() -> setOnline(false)); }
    };

    // ---- Ambient-mode shortcut --------------------------------------------------------------
    // Starting a dream directly needs a system-only permission, so instead the screen-off
    // timeout is dropped to the platform minimum; the dream then starts on its own and the
    // saved timeout is put back as soon as it does (or on cancel, timeout, or next launch).

    private static final int AMBIENT_DELAY_MS = 10_000, HANDOFF_MS = 9_600;
    private static final String PREF_TIMEOUT = "savedScreenOffTimeout";
    private View ambientShade, ambientPhotoShade, ambientProgress;
    private ImageView ambientPhoto;
    private LinearLayout ambientIntro, ambientCorner;
    private TextView ambientClock, ambientCornerClock, ambientCornerDate;
    private final List<android.animation.Animator> ambientAnims = new ArrayList<>();
    private boolean ambient;
    private final Runnable ambientGiveUp = this::cancelAmbient;

    private final BroadcastReceiver dreaming = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            restoreTimeout();
            // The dream window is on top by now; drop the overlay just after so nothing flashes.
            main.postDelayed(() -> endAmbient(false), 600);
        }
    };

    private View[] ambientLayers() {
        return new View[] {ambientShade, ambientPhoto, ambientPhotoShade, ambientIntro, ambientCorner};
    }

    private void startAmbient() {
        if (ambient) return;
        if (!Settings.System.canWrite(this)) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { }
            return;
        }
        android.content.ContentResolver cr = getContentResolver();
        if (!prefs.contains(PREF_TIMEOUT)) {
            int current = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 600_000);
            prefs.edit().putInt(PREF_TIMEOUT, current).commit();
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, AMBIENT_DELAY_MS);
        ambient = true;
        main.postDelayed(ambientGiveUp, AMBIENT_DELAY_MS + 15_000);

        ambientClock.setText(clock.getText());
        ambientCornerClock.setText(clock.getText());
        ambientCornerDate.setText(date.getText());
        ambientPhoto.setImageDrawable(null);
        for (View v : ambientLayers()) {
            v.setAlpha(0f);
            v.setVisibility(View.VISIBLE);
        }
        loadHandoffPhoto();

        // 0-1.3s dim to a centred clock; a hairline fills while it breathes; from ~4.3s the
        // first photo rises behind it; the centred clock gives way to the dream's corner clock.
        animate(ambientShade, alpha(0, 0, .10f, .9f, .5f, 1));
        animate(ambientIntro, alpha(0, 0, .04f, 0, .14f, 1, .62f, 1, .74f, 0),
                scale(View.SCALE_X), scale(View.SCALE_Y));
        ambientProgress.setScaleX(0f);
        animate(ambientProgress, PropertyValuesHolder.ofKeyframe(View.SCALE_X,
                Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(.08f, 0f), Keyframe.ofFloat(.70f, 1f), Keyframe.ofFloat(1f, 1f)));
        ambientProgress.setAlpha(1f);
        animate(ambientPhoto, alpha(0, 0, .45f, 0, .72f, 1));
        animate(ambientPhotoShade, alpha(0, 0, .45f, 0, .72f, 1));
        animate(ambientCorner, alpha(0, 0, .66f, 0, .84f, 1));
    }

    private void animate(View v, PropertyValuesHolder... values) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v, values).setDuration(HANDOFF_MS);
        a.start();
        ambientAnims.add(a);
    }

    /** Alpha keyframes as (fraction, value) pairs. */
    private static PropertyValuesHolder alpha(float... pairs) {
        Keyframe[] k = new Keyframe[pairs.length / 2];
        for (int i = 0; i < k.length; i++) k[i] = Keyframe.ofFloat(pairs[i * 2], pairs[i * 2 + 1]);
        return PropertyValuesHolder.ofKeyframe(View.ALPHA, k);
    }

    private static PropertyValuesHolder scale(android.util.Property<View, Float> axis) {
        return PropertyValuesHolder.ofKeyframe(axis, Keyframe.ofFloat(0f, .94f), Keyframe.ofFloat(.14f, 1f),
                Keyframe.ofFloat(.62f, 1.04f), Keyframe.ofFloat(.74f, 1.1f), Keyframe.ofFloat(1f, 1.1f));
    }

    /** Decodes a random photo now and hands the same bitmap to PhotoDream, which opens on it. */
    private void loadHandoffPhoto() {
        new Thread(() -> {
            java.io.File f = PhotoDream.pickPhoto(this);
            Bitmap b = f == null ? null : PhotoDream.decode(this, f);
            main.post(() -> {
                if (!ambient || b == null) return;
                ambientPhoto.setImageBitmap(b);
                PhotoDream.setHandoff(b, f);
            });
        }, "ambient-photo").start();
    }

    private void cancelAmbient() {
        restoreTimeout();
        PhotoDream.setHandoff(null, null);
        endAmbient(true);
    }

    private void endAmbient(boolean fade) {
        if (!ambient) return;
        ambient = false;
        main.removeCallbacks(ambientGiveUp);
        for (android.animation.Animator a : ambientAnims) a.cancel();
        ambientAnims.clear();
        for (View v : ambientLayers()) {
            v.animate().cancel();
            if (fade) {
                v.animate().alpha(0f).setDuration(250).withEndAction(() -> v.setVisibility(View.GONE)).start();
            } else {
                v.setVisibility(View.GONE);
            }
        }
        // The dream now owns the shared photo; drop our reference so it can be freed with it.
        if (!fade) ambientPhoto.setImageDrawable(null);
    }

    private void restoreTimeout() {
        if (!prefs.contains(PREF_TIMEOUT) || !Settings.System.canWrite(this)) return;
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, prefs.getInt(PREF_TIMEOUT, 600_000));
        prefs.edit().remove(PREF_TIMEOUT).commit();
    }

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
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x26FFFFFF);
        PillGlow glow = new PillGlow(dp(10),dp(18));
        v.setBackground(new LayerDrawable(new Drawable[] {glow, bg}));
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
                startAmbient();
                return;
            }
            if (glyph == Glyph.PAD && checkSelfPermission(BT) != PackageManager.PERMISSION_GRANTED) {
                pendingShortcut = intent;
                requestPermissions(new String[] {BT}, 1);
                return;
            }
            try { startActivity(intent); } catch (RuntimeException ignored) { }
        });
        v.setOnFocusChangeListener((x, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x26FFFFFF);
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

    /** Small line icons for the shortcut pills, drawn with paths instead of image assets. */
    private static final class PillGlow extends Drawable {
        private static final int COLOR = 0x50FFFFFF; //os 2 números após o x controlam a opacidade e as letras são um hex de cor.
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Float radius;

        PillGlow(float blur, float radius) {
            this.radius = radius;
            p.setColor(COLOR);
            p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.OUTER)); // borra fora da pilula.
            p.setAlpha(0);
        }

        @Override public void draw(Canvas c) {
            c.drawRoundRect(new RectF(getBounds()),radius, radius, p);
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha * Color.alpha(COLOR) / 255);
            invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Glyph extends Drawable {
        static final int WIFI = 0, PAD = 1, TV = 2, MOON = 3;
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        boolean off;

        Glyph(int type) {
            this.type = type;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int c) {
            p.setColor(c);
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), cx = b.exactCenterX(), cy = b.exactCenterY();
            int color = p.getColor();
            if (off) p.setAlpha(120);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.1f);
            switch (type) {
                case WIFI: {
                    float by = cy + s * 0.3f;
                    for (int i = 1; i <= 3; i++) {
                        float rad = s * 0.2f * i;
                        r.set(cx - rad, by - rad, cx + rad, by + rad);
                        c.drawArc(r, 225, 90, false, p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, by, s * 0.07f, p);
                    if (off) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setAlpha(255);
                        c.drawLine(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, p);
                    }
                    break;
                }
                case PAD: {
                    r.set(cx - s * 0.48f, cy - s * 0.26f, cx + s * 0.48f, cy + s * 0.3f);
                    c.drawRoundRect(r, s * 0.24f, s * 0.24f, p);
                    float dx = cx - s * 0.22f, dy = cy + s * 0.02f, arm = s * 0.1f;
                    c.drawLine(dx - arm, dy, dx + arm, dy, p);
                    c.drawLine(dx, dy - arm, dx, dy + arm, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx + s * 0.18f, cy - s * 0.04f, s * 0.06f, p);
                    c.drawCircle(cx + s * 0.3f, cy + s * 0.08f, s * 0.06f, p);
                    break;
                }
                case MOON: {
                    android.graphics.Path moon = new android.graphics.Path();
                    moon.addCircle(cx, cy, s * 0.4f, android.graphics.Path.Direction.CW);
                    android.graphics.Path bite = new android.graphics.Path();
                    bite.addCircle(cx + s * 0.22f, cy - s * 0.18f, s * 0.32f, android.graphics.Path.Direction.CW);
                    moon.op(bite, android.graphics.Path.Op.DIFFERENCE);
                    p.setStyle(Paint.Style.FILL);
                    c.drawPath(moon, p);
                    break;
                }
                default: {
                    r.set(cx - s * 0.46f, cy - s * 0.34f, cx + s * 0.46f, cy + s * 0.2f);
                    c.drawRoundRect(r, s * 0.07f, s * 0.07f, p);
                    c.drawLine(cx - s * 0.2f, cy + s * 0.38f, cx + s * 0.2f, cy + s * 0.38f, p);
                    break;
                }
            }
            p.setColor(color);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
        if (moving != null) endMove();
        super.onPause();
    }

    /** Pressing Home while already home jumps back to the first app, like tvOS. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) endMove();
        if (!entries.isEmpty()) entries.get(0).view.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent ev) {
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (ambient) {
            // Any key during the hand-off means "never mind".
            if (down) cancelAmbient();
            return true;
        }
        // Home is the root screen: Back never leaves it, it only drops an app being moved.
        // (Handled here instead of the deprecated onBackPressed.)
        if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (down && moving != null) endMove();
            return true;
        }
        if (moving == null || !down) return super.dispatchKeyEvent(ev);
        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1);
            case KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(1);
            case KeyEvent.KEYCODE_DPAD_UP -> moveBy(-COLS);
            case KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(COLS);
            case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> endMove();
            default -> { }
        }
        return true;
    }

    // ---- Loading -------------------------------------------------------------------------

    private void reload() {
        new Thread(() -> {
            List<Entry> list = loadEntries();
            main.post(() -> show(list));
        }, "home-load").start();
    }

    private List<Entry> loadEntries() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        int bw = Math.round(tileW * MOVE_SCALE), bh = bw * 9 / 16;

        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(getPackageName())) continue;
            Entry e = new Entry();
            e.key = ai.packageName + "/" + ai.name;
            e.name = ri.loadLabel(pm);
            e.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = ai.loadBanner(pm);
            if (e.key.startsWith(SETTINGS_PKG)) bake(e, drawSettings(bw, bh));
            else bake(e, banner != null ? drawBanner(banner, bw, bh) : drawIcon(ri.loadIcon(pm), bw, bh));
            byKey.put(e.key, e);
        }

        // Saved order first; new apps alphabetically after it, system Settings last.
        List<Entry> out = new ArrayList<>();
        for (String k : prefs.getString(PREF_ORDER, "").split("\n")) {
            Entry e = byKey.remove(k);
            if (e != null) out.add(e);
        }
        List<Entry> rest = new ArrayList<>(byKey.values());
        Collator c = Collator.getInstance();
        rest.sort((a, b) -> {
            boolean sa = a.key.startsWith(SETTINGS_PKG), sb = b.key.startsWith(SETTINGS_PKG);
            return sa != sb ? (sa ? 1 : -1) : c.compare(a.name.toString(), b.name.toString());
        });
        out.addAll(rest);
        return out;
    }

    /** Derives the blurred wash, then moves both bitmaps to GPU memory (off the Java heap). */
    private static void bake(Entry e, Bitmap sw) {
        Bitmap small = Bitmap.createScaledBitmap(sw, WASH_W, WASH_H, true);
        int[] px = new int[WASH_W * WASH_H];
        small.getPixels(px, 0, WASH_W, 0, 0, WASH_W, WASH_H);
        e.accent = accent(px);
        for (int pass = 0; pass < 3; pass++) blur(px);
        tame(px, e.accent);
        shade(px);
        small.setPixels(px, 0, WASH_W, 0, 0, WASH_W, WASH_H);
        e.wash = toGpu(small);
        e.banner = toGpu(sw);
    }

    /**
     * Light banners (white Netflix/YouTube/Play tiles) would wash the screen pale gray: darken them
     * and lean toward a deep shade of the app's accent so white text keeps its contrast.
     */
    private static void tame(int[] px, int accent) {
        double sum = 0;
        for (int p : px) sum += 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
        float luma = (float) (sum / px.length / 255);
        if (luma <= 0.34f) return;
        float dim = 0.3f / luma, t = Math.min(1f, (luma - 0.34f) / 0.35f) * 0.65f;
        float ar = ((accent >> 16) & 0xFF) * 0.32f, ag = ((accent >> 8) & 0xFF) * 0.32f, ab = (accent & 0xFF) * 0.32f;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (int) (((p >> 16) & 0xFF) * dim * (1 - t) + ar * t);
            int g = (int) (((p >> 8) & 0xFF) * dim * (1 - t) + ag * t);
            int b = (int) ((p & 0xFF) * dim * (1 - t) + ab * t);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    /** Saturation-weighted average color of the banner, pushed bright for the glow; white if it has none. */
    private static int accent(int[] px) {
        float[] hsv = new float[3];
        double r = 0, g = 0, b = 0, sum = 0;
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            if (hsv[2] < 0.15f) continue;
            float w = hsv[1] * hsv[1] * hsv[2];
            r += ((p >> 16) & 0xFF) * w;
            g += ((p >> 8) & 0xFF) * w;
            b += (p & 0xFF) * w;
            sum += w;
        }
        if (sum < px.length * 0.02) return Color.WHITE;
        Color.colorToHSV(Color.rgb((int) (r / sum), (int) (g / sum), (int) (b / sum)), hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private static Bitmap toGpu(Bitmap b) {
        Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
        if (hw == null) return b;
        b.recycle();
        return hw;
    }

    private static Bitmap drawBanner(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF2A2A31);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    private static Bitmap drawIcon(Drawable icon, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF2A2A31);
        int s = h * 6 / 10;
        icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
        icon.draw(c);
        return b;
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
        android.graphics.RectF r = new android.graphics.RectF(glow, glow, w - glow, h - glow);
        float rad = dp(ROW_RADIUS);

        if (glowOnly) {
            Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
            halo.setColor(0x80FFFFFF);
            halo.setMaskFilter(new android.graphics.BlurMaskFilter(glow * 0.8f, android.graphics.BlurMaskFilter.Blur.OUTER));
            c.drawRoundRect(r, rad, rad, halo);
            return toGpu(b);
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
        return toGpu(b);
    }

    /** Graphite tile with a white gear, in place of the stock gray Settings banner. */
    private static Bitmap drawSettings(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, h, 0xFF6A6F7C, 0xFF24272E, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);

        float cx = w / 2f, cy = h / 2f, r = h * 0.29f;
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x40000000);
        Paint gear = new Paint(Paint.ANTI_ALIAS_FLAG);
        gear.setShader(new LinearGradient(0, cy - r, 0, cy + r, 0xFFFFFFFF, 0xFFD9DBE2, Shader.TileMode.CLAMP));
        for (Paint p : new Paint[] {shadow, gear}) {
            float dy = p == shadow ? h * 0.02f : 0;
            for (int i = 0; i < 12; i++) {
                c.save();
                c.rotate(i * 30, cx, cy + dy);
                c.drawRoundRect(cx - r * 0.12f, cy + dy - r * 1.1f, cx + r * 0.12f, cy + dy - r * 0.7f, r * 0.05f, r * 0.05f, p);
                c.restore();
            }
            c.drawCircle(cx, cy + dy, r * 0.9f, p);
        }
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(r * 0.06f);
        ring.setColor(0xFF9EA2AD);
        c.drawCircle(cx, cy, r * 0.66f, ring);
        c.drawCircle(cx, cy, r * 0.3f, bg);
        return b;
    }

    /** Bakes the top-to-bottom darkening and an edge vignette into the wash (no per-frame overlay). */
    private static void shade(int[] px) {
        final int w = WASH_W, h = WASH_H;
        for (int y = 0; y < h; y++) {
            float t = y / (float) (h - 1);
            float keep = t < 0.5f ? 0.82f - 0.3f * t : 0.67f - (t - 0.5f);
            float dy = t * 2 - 1;
            for (int x = 0; x < w; x++) {
                float dx = x / (float) (w - 1) * 2 - 1;
                float k = keep * Math.max(0f, 1f - 0.42f * (dx * dx * 0.7f + dy * dy * 0.5f));
                int p = px[y * w + x];
                int r = (int) (((p >> 16) & 0xFF) * k), g = (int) (((p >> 8) & 0xFF) * k), b = (int) ((p & 0xFF) * k);
                px[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** One horizontal + vertical box-blur pass (radius 5) over the wash's packed ARGB pixels. */
    private static void blur(int[] px) {
        final int r = 5;
        int[] tmp = new int[px.length];
        boxPass(px, tmp, WASH_W, WASH_H, r, 1, WASH_W);
        boxPass(tmp, px, WASH_H, WASH_W, r, WASH_W, 1);
    }

    private static void boxPass(int[] src, int[] dst, int len, int lines, int r, int step, int lineStep) {
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int p = src[base + k * step];
                    rs += (p >> 16) & 0xFF;
                    gs += (p >> 8) & 0xFF;
                    bs += p & 0xFF;
                    n++;
                }
                dst[base + i * step] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
            }
        }
    }

    // ---- Grid ----------------------------------------------------------------------------

    private void show(List<Entry> list) {
        if (isDestroyed()) return;
        String keep = focused != null ? focused.key : null;
        grid.removeAllViews();
        entries.clear();
        entries.addAll(list);
        focused = null;
        focusedRow = -1;

        // Behind the tiles: first child of the grid, so it scrolls with the rows.
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(panelW, panelH);
        pl.leftMargin = dp(SIDE) - dp(ROW_PAD_X) - dp(ROW_GLOW);
        grid.addView(rowPanel, pl);
        rowPanel.setAlpha(0f);

        Entry target = list.isEmpty() ? null : list.get(0);
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            e.view = tile(e);
            grid.addView(e.view, new FrameLayout.LayoutParams(tileW, tileH));
            place(e, i, false);
            if (e.key.equals(keep)) target = e;
        }
        grid.addView(label, new FrameLayout.LayoutParams(Math.round(tileW * 1.5f), FrameLayout.LayoutParams.WRAP_CONTENT));
        if (target != null) target.view.requestFocus();
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
        v.setOnLongClickListener(x -> { startMove(e); return true; });
        v.setOnFocusChangeListener((x, has) -> onTileFocus(e, has));
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

    private void onTileFocus(Entry e, boolean has) {
        float s = has ? (e == moving ? MOVE_SCALE : FOCUS_SCALE) : 1f;
        e.view.animate().scaleX(s).scaleY(s).translationZ(has ? dp(16) : 0)
                .setDuration(170).setInterpolator(ease).start();
        // One shared shine, attached only to the focused tile so idle tiles cost nothing.
        e.view.setForeground(has ? shine : null);
        if (!has) return;
        shine.sweep();
        focused = e;
        int i = entries.indexOf(e);
        showLabel(e, i);
        setWash(e);
        scrollTo(i / COLS);
    }

    private void showLabel(Entry e, int i) {
        label.setText(e.name);
        label.setX(tileX(i) - tileW * 0.25f);
        label.setY(tileY(i) + tileH * (1 + (MOVE_SCALE - 1) / 2) + dp(6));
        label.setAlpha(0f);
        label.animate().alpha(1f).setDuration(200).start();
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
        if (moving != null) return;
        try {
            startActivity(e.intent);
        } catch (RuntimeException ignored) {
            // App vanished or refused; the package receiver will refresh the grid.
        }
    }

    // ---- Reordering ----------------------------------------------------------------------

    private void startMove(Entry e) {
        moving = e;
        e.view.animate().scaleX(MOVE_SCALE).scaleY(MOVE_SCALE).setDuration(150).start();
        e.view.setAlpha(0.85f);
        hint.setVisibility(View.VISIBLE);
    }

    private void moveBy(int d) {
        int i = entries.indexOf(moving), j = i + d;
        if (j < 0 || j >= entries.size()) return;
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

    // ---- Helpers -------------------------------------------------------------------------

    private void updateClock() {
        long now = System.currentTimeMillis();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        String d = DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM"), now).toString();
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
    }

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

    private static LinearLayout.LayoutParams centered(int w, int h, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = top;
        return lp;
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams wrap(int gravity) {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
    }

    private int dp(float v) { return Math.round(v * density); }
}
