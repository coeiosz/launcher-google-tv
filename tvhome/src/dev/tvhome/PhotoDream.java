package dev.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Screensaver showing the user's own photos (pushed by fotos-sync.ps1 into the app's external
 * files dir), so ambient photos work without Google's launcher or setup app. One photo is decoded
 * ahead at a time, straight into GPU memory; each slowly zooms (Ken Burns) and crossfades.
 */
public final class PhotoDream extends DreamService {
    private static final long SHOW_MS = 12_000, FADE_MS = 1_600;
    private static final float ZOOM = 1.08f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<File> photos = new ArrayList<>();
    private ImageView front, back;
    private View shade;
    private LinearLayout when;
    private TextView clock, date;
    private int next;
    private volatile boolean running;
    private float density;

    private final Runnable advance = this::loadNext;
    private final Runnable minute = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            main.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        density = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = photoView();
        front = photoView();
        root.addView(back, fill());
        root.addView(front, fill());

        shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x8C000000}));
        root.addView(shade, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(280), Gravity.BOTTOM));

        when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        when.setGravity(Gravity.END);
        clock = text(56, "sans-serif-light");
        date = text(18, "sans-serif");
        date.setAlpha(0.85f);
        when.addView(clock);
        when.addView(date);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        wp.rightMargin = dp(64);
        wp.bottomMargin = dp(48);
        root.addView(when, wp);

        setContentView(root);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        photos.clear();
        File dir = getExternalFilesDir("fotos");
        File[] files = dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)"));
        if (files != null) Collections.addAll(photos, files);
        Collections.shuffle(photos, random);
        next = 0;
        main.post(minute);
        if (photos.isEmpty()) {
            date.setText("Sem fotos: rode tvhome\\fotos-sync.ps1 no PC");
        } else {
            loadNext();
        }
    }

    @Override public void onDreamingStopped() {
        running = false;
        main.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        running = false;
        main.removeCallbacksAndMessages(null);
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        super.onDetachedFromWindow();
    }

    /** Decodes the next photo off the main thread, then crossfades it in. */
    private void loadNext() {
        if (!running || photos.isEmpty()) return;
        File f = photos.get(next++ % photos.size());
        new Thread(() -> {
            Bitmap b = decode(f);
            main.post(() -> {
                if (!running) return;
                if (b == null) {
                    photos.remove(f);
                    loadNext();
                } else {
                    present(b);
                }
            });
        }, "dream-decode").start();
    }

    private Bitmap decode(File f) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= m.widthPixels && o.outHeight / (sample * 2) >= m.heightPixels) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.HARDWARE;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private void present(Bitmap b) {
        ImageView incoming = back, outgoing = front;
        front = incoming;
        back = outgoing;

        incoming.animate().cancel();
        incoming.setImageBitmap(b);
        incoming.setAlpha(0f);
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.setTranslationX(0f);
        incoming.bringToFront();
        clockToFront();

        // Ken Burns: a slow zoom with a slight drift in a random direction, running through the fade.
        float drift = dp(24) * (random.nextBoolean() ? 1 : -1);
        incoming.animate().scaleX(ZOOM).scaleY(ZOOM).translationX(drift)
                .setDuration(SHOW_MS + FADE_MS * 2).setInterpolator(new LinearInterpolator()).start();
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            outgoing.animate().cancel();
            outgoing.setImageDrawable(null);
        });
        main.postDelayed(advance, SHOW_MS);
    }

    /** Photos swap z-order on every crossfade; the shade and clock must stay above both. */
    private void clockToFront() {
        shade.bringToFront();
        when.bringToFront();
    }

    private void updateClock() {
        long now = System.currentTimeMillis();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        if (photos.isEmpty()) return;
        String d = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now).toString();
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private TextView text(float sp, String family) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        return t;
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) { return Math.round(v * density); }
}
