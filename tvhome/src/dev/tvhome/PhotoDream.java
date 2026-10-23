package dev.tvhome;

import android.content.Context;
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
    private static final long SHOW_MS = 7_000, FADE_MS = 1_200;
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

    // Hand-off from HomeActivity's transition (same process): the photo already on screen there,
    // so the dream opens on it instead of on black.
    private static Bitmap handoff;
    private static File handoffFile;
    private static long handoffAt;

    static synchronized void setHandoff(Bitmap b, File f) {
        handoff = b;
        handoffFile = f;
        handoffAt = System.currentTimeMillis();
    }

    private static synchronized Bitmap takeHandoff(File[] file) {
        Bitmap b = System.currentTimeMillis() - handoffAt < 60_000 ? handoff : null;
        file[0] = handoffFile;
        handoff = null;
        handoffFile = null;
        return b;
    }

    static File pickPhoto(Context c) {
        File[] files = listPhotos(c);
        return files == null || files.length == 0 ? null : files[new Random().nextInt(files.length)];
    }

    private static File[] listPhotos(Context c) {
        File dir = c.getExternalFilesDir("fotos");
        return dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)"));
    }

    private boolean openedOnHandoff;
    private File handoffShown;
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

        shade = addBottomShade(root);

        when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        when.setGravity(Gravity.END);
        clock = dreamText(this, 56, "sans-serif-light");
        date = dreamText(this, 18, "sans-serif");
        date.setAlpha(0.85f);
        // Children of a vertical LinearLayout default to full width, so align the text itself.
        clock.setGravity(Gravity.END);
        date.setGravity(Gravity.END);
        when.addView(clock);
        when.addView(date);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        // Close to the corner but still inside the TV-safe area; HomeActivity's hand-off clock matches.
        wp.rightMargin = dp(36);
        wp.bottomMargin = dp(22);
        root.addView(when, wp);

        setContentView(root);

        // Open on the transition's photo so the very first frame matches what was on screen.
        File[] f = new File[1];
        Bitmap b = takeHandoff(f);
        openedOnHandoff = b != null;
        if (openedOnHandoff) {
            front.setImageBitmap(b);
            handoffShown = f[0];
        }
        updateClock();
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        photos.clear();
        File[] files = listPhotos(this);
        if (files != null) Collections.addAll(photos, files);
        Collections.shuffle(photos, random);
        next = 0;
        main.post(minute);
        if (photos.isEmpty()) {
            date.setText("Sem fotos: rode tvhome\\fotos-sync.ps1 no PC");
        } else if (openedOnHandoff) {
            // Already showing; continue its slow zoom from where the transition left it (1.0).
            photos.remove(handoffShown);
            photos.add(0, handoffShown);
            next = 1;
            front.animate().scaleX(ZOOM).scaleY(ZOOM).translationX(dp(24) * (random.nextBoolean() ? 1 : -1))
                    .setDuration(SHOW_MS + FADE_MS * 2).setInterpolator(new LinearInterpolator()).start();
            main.postDelayed(advance, SHOW_MS);
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
            Bitmap b = decode(this, f);
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

    static Bitmap decode(Context c, File f) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
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
        if (running && photos.isEmpty()) return; // keeps the "no photos" hint
        String d = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now).toString();
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    // Shared with HomeActivity's hand-off overlay, so both draw the clock block identically.

    static TextView dreamText(Context c, float sp, String family) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(8 * d, 0, d, 0x80000000);
        return t;
    }

    /** Darkening at the bottom of the photo so the corner clock stays readable. */
    static View addBottomShade(FrameLayout root) {
        float d = root.getResources().getDisplayMetrics().density;
        View v = new View(root.getContext());
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0x8C000000}));
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Math.round(280 * d), Gravity.BOTTOM));
        return v;
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) { return Math.round(v * density); }
}
