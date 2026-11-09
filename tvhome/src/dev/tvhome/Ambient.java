package dev.tvhome;

import android.animation.Animator;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Property;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Modo ambiente" shortcut. Starting a dream directly needs a system-only permission, so
 * instead the screen-off timeout is dropped to the platform minimum; the dream then starts on its
 * own and the saved timeout is put back as soon as it does (or on cancel, timeout, or next launch).
 * Meanwhile an overlay fades the home screen into the dream's first frame. Main thread only.
 */
final class Ambient {
    private static final int AMBIENT_DELAY_MS = 10_000, HANDOFF_MS = 9_600;
    private static final String PREF_TIMEOUT = "savedScreenOffTimeout";

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler main;
    private final float density;
    private final View shade, photoShade, progress;
    private final ImageView photo;
    private final LinearLayout intro, corner;
    private final TextView introClock, cornerClock, cornerDate;
    private final List<Animator> anims = new ArrayList<>();
    private boolean active;
    private final Runnable giveUp = this::cancel;

    private final BroadcastReceiver dreaming = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            restoreTimeout();
            // The dream window is on top by now; drop the overlay just after so nothing flashes.
            main.postDelayed(() -> end(false), 600);
        }
    };

    /**
     * Adds the (hidden) overlay on top of everything already in root: a plain black layer (cheap
     * to fade) plus a centred clock. The layers end exactly as PhotoDream starts (same photo, same
     * corner clock), so the screensaver takes over with no visible cut.
     */
    Ambient(Activity activity, FrameLayout root, SharedPreferences prefs, Handler main) {
        this.activity = activity;
        this.prefs = prefs;
        this.main = main;
        density = activity.getResources().getDisplayMetrics().density;

        shade = new View(activity);
        shade.setBackgroundColor(Color.BLACK);
        root.addView(shade, fill());

        photo = new ImageView(activity);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(photo, fill());
        photoShade = PhotoDream.addBottomShade(root);

        intro = new LinearLayout(activity);
        intro.setOrientation(LinearLayout.VERTICAL);
        introClock = PhotoDream.dreamText(activity, 76, "sans-serif-light");
        TextView caption = PhotoDream.dreamText(activity, 16, "sans-serif");
        caption.setText("Modo ambiente");
        caption.setAlpha(0.75f);
        progress = new View(activity);
        progress.setBackgroundColor(0x99FFFFFF);
        intro.addView(introClock, centered(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        intro.addView(caption, centered(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        intro.addView(progress, centered(dp(140), dp(2), dp(16)));
        root.addView(intro, wrap(Gravity.CENTER));

        // Same views, sizes and margins as PhotoDream's clock block.
        corner = new LinearLayout(activity);
        corner.setOrientation(LinearLayout.VERTICAL);
        corner.setGravity(Gravity.END);
        cornerClock = PhotoDream.dreamText(activity, 56, "sans-serif-light");
        cornerDate = PhotoDream.dreamText(activity, 18, "sans-serif");
        cornerDate.setAlpha(0.85f);
        cornerClock.setGravity(Gravity.END);
        cornerDate.setGravity(Gravity.END);
        corner.addView(cornerClock);
        corner.addView(cornerDate);
        FrameLayout.LayoutParams cp = wrap(Gravity.BOTTOM | Gravity.END);
        cp.rightMargin = dp(36);
        cp.bottomMargin = dp(22);
        root.addView(corner, cp);

        for (View v : layers()) v.setVisibility(View.GONE);
    }

    /** From onCreate: listens for the dream, and undoes a hand-off a previous run died during. */
    void register() {
        activity.registerReceiver(dreaming, new IntentFilter(Intent.ACTION_DREAMING_STARTED));
        restoreTimeout();
    }

    /** From onDestroy. */
    void unregister() {
        restoreTimeout();
        activity.unregisterReceiver(dreaming);
    }

    /** True while the hand-off overlay is up; any key then means "never mind" (see cancel). */
    boolean isActive() { return active; }

    void start(CharSequence clock, CharSequence date) {
        if (active) return;
        if (!Settings.System.canWrite(activity)) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { }
            return;
        }
        ContentResolver cr = activity.getContentResolver();
        if (!prefs.contains(PREF_TIMEOUT)) {
            int current = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 600_000);
            prefs.edit().putInt(PREF_TIMEOUT, current).commit();
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, AMBIENT_DELAY_MS);
        active = true;
        main.postDelayed(giveUp, AMBIENT_DELAY_MS + 15_000);

        introClock.setText(clock);
        cornerClock.setText(clock);
        cornerDate.setText(date);
        photo.setImageDrawable(null);
        for (View v : layers()) {
            v.setAlpha(0f);
            v.setVisibility(View.VISIBLE);
        }
        loadHandoffPhoto();

        // 0-1.3s dim to a centred clock; a hairline fills while it breathes; from ~4.3s the
        // first photo rises behind it; the centred clock gives way to the dream's corner clock.
        animate(shade, alpha(0, 0, .10f, .9f, .5f, 1));
        animate(intro, alpha(0, 0, .04f, 0, .14f, 1, .62f, 1, .74f, 0),
                scale(View.SCALE_X), scale(View.SCALE_Y));
        progress.setScaleX(0f);
        animate(progress, PropertyValuesHolder.ofKeyframe(View.SCALE_X,
                Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(.08f, 0f), Keyframe.ofFloat(.70f, 1f), Keyframe.ofFloat(1f, 1f)));
        progress.setAlpha(1f);
        animate(photo, alpha(0, 0, .45f, 0, .72f, 1));
        animate(photoShade, alpha(0, 0, .45f, 0, .72f, 1));
        animate(corner, alpha(0, 0, .66f, 0, .84f, 1));
    }

    void cancel() {
        restoreTimeout();
        PhotoDream.setHandoff(null, null);
        end(true);
    }

    private View[] layers() {
        return new View[] {shade, photo, photoShade, intro, corner};
    }

    private void animate(View v, PropertyValuesHolder... values) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v, values).setDuration(HANDOFF_MS);
        a.start();
        anims.add(a);
    }

    /** Alpha keyframes as (fraction, value) pairs. */
    private static PropertyValuesHolder alpha(float... pairs) {
        Keyframe[] k = new Keyframe[pairs.length / 2];
        for (int i = 0; i < k.length; i++) k[i] = Keyframe.ofFloat(pairs[i * 2], pairs[i * 2 + 1]);
        return PropertyValuesHolder.ofKeyframe(View.ALPHA, k);
    }

    private static PropertyValuesHolder scale(Property<View, Float> axis) {
        return PropertyValuesHolder.ofKeyframe(axis, Keyframe.ofFloat(0f, .94f), Keyframe.ofFloat(.14f, 1f),
                Keyframe.ofFloat(.62f, 1.04f), Keyframe.ofFloat(.74f, 1.1f), Keyframe.ofFloat(1f, 1.1f));
    }

    /** Decodes a random photo now and hands the same bitmap to PhotoDream, which opens on it. */
    private void loadHandoffPhoto() {
        new Thread(() -> {
            File f = PhotoDream.pickPhoto(activity);
            Bitmap b = f == null ? null : PhotoDream.decode(activity, f);
            main.post(() -> {
                if (!active || b == null) return;
                photo.setImageBitmap(b);
                PhotoDream.setHandoff(b, f);
            });
        }, "ambient-photo").start();
    }

    private void end(boolean fade) {
        if (!active) return;
        active = false;
        main.removeCallbacks(giveUp);
        for (Animator a : anims) a.cancel();
        anims.clear();
        for (View v : layers()) {
            v.animate().cancel();
            if (fade) {
                v.animate().alpha(0f).setDuration(250).withEndAction(() -> v.setVisibility(View.GONE)).start();
            } else {
                v.setVisibility(View.GONE);
            }
        }
        // The dream now owns the shared photo; drop our reference so it can be freed with it.
        if (!fade) photo.setImageDrawable(null);
    }

    private void restoreTimeout() {
        if (!prefs.contains(PREF_TIMEOUT) || !Settings.System.canWrite(activity)) return;
        Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                prefs.getInt(PREF_TIMEOUT, 600_000));
        prefs.edit().remove(PREF_TIMEOUT).commit();
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
