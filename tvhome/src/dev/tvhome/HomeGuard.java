package dev.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Keeps this launcher on screen without being the system's home app. Making dev.tvhome the HOME
 * activity means disabling Google's launcherx, and with setupwraith enabled (Cast needs it) that
 * can loop setupwraith's RecoveryActivity until system_server crashes. So launcherx stays the real
 * home, and whenever its home screen comes up (Home key, waking from standby) this opens ours on top.
 */
public final class HomeGuard extends AccessibilityService {
    private static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx.home.HomeActivity";
    private static final long MIN_GAP_MS = 1_000;

    // At boot the service binds after Google's home is already up, so no event would come.
    private static final long BOOT_WINDOW_MS = 180_000;

    private long lastLaunch;

    @Override protected void onServiceConnected() {
        // Only right after boot: a reconnect later (process restart) could be mid-movie.
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) bringForward();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = e.getClassName();
        if (cls == null || !GOOGLE_HOME.contentEquals(cls)) return;
        bringForward();
    }

    private void bringForward() {
        // One launch per appearance: the window-state event can fire more than once per transition.
        long now = SystemClock.elapsedRealtime();
        if (lastLaunch != 0 && now - lastLaunch < MIN_GAP_MS) return;
        lastLaunch = now;
        try {
            // singleTask: an existing instance gets onNewIntent, which jumps back to the first app.
            startActivity(new Intent(this, HomeActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ex) {
            // Worst case Google's home stays on screen, which is still a working TV.
            Log.w("tvhome", "could not bring the launcher forward", ex);
        }
    }

    @Override public void onInterrupt() { }
}
