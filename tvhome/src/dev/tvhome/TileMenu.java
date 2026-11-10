package dev.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Small list menu over a dimmed grid. It never takes focus: while it is open HomeActivity feeds it
 * the keys (like move mode), so the tile underneath keeps focus and its pressed state clears
 * normally. Long lists scroll by a window of MAX_ROWS. Main thread only.
 */
final class TileMenu {
    static final class Item {
        final String text;
        final Runnable action;

        Item(String text, Runnable action) {
            this.text = text;
            this.action = action;
        }
    }

    private static final int MAX_ROWS = 8, FADE_MS = 150;
    // Same colors as a focused shortcut pill.
    private static final int SELECTED_BG = 0xF2FFFFFF, SELECTED_FG = 0xFF15161A;
    // The scrim's black at 60% leaves 40% of what is behind it.
    private static final int SCRIM = 0x99000000;
    private static final float BEHIND = 0.4f;

    private final float density;
    private final FrameLayout layer;
    private final TextView header;
    private final TextView[] rows = new TextView[MAX_ROWS];
    private final GradientDrawable[] rowBgs = new GradientDrawable[MAX_ROWS];
    private final Glass.Surface[] rowGlass = new Glass.Surface[MAX_ROWS];
    private final List<Item> items = new ArrayList<>();
    private int selected, first;
    private boolean open;

    /** Adds the (hidden) menu on top of everything already in root. */
    TileMenu(Context c, FrameLayout root, Glass glass) {
        density = c.getResources().getDisplayMetrics().density;

        // Full-screen scrim, so the panel reads over the tiles and the wash.
        layer = new FrameLayout(c);
        layer.setBackgroundColor(SCRIM);
        layer.setFocusable(false);

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel.setMinimumWidth(dp(260));
        if (glass.enabled()) {
            // Thick frosted glass: the tiles behind can't be sampled, and through this much frost
            // they would only be a smear of color anyway. So it is opaque, drawn from the wash
            // under the same scrim as its surroundings, a little lighter than them.
            panel.setBackground(glass.surface(panel, new Glass.Style().radius(24).bevel(16).refract(14)
                    .frost(0.1f).shine(0.7f).base(BEHIND, 0f).opaque()));
        } else {
            // Frosted like the row panel: translucent white fill, 1dp light edge.
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0x24FFFFFF, 0x12FFFFFF});
            bg.setCornerRadius(dp(24));
            bg.setStroke(dp(1), 0x47FFFFFF);
            panel.setBackground(bg);
        }

        header = text(c, 14);
        header.setAlpha(0.7f);
        header.setPadding(dp(16), dp(6), dp(16), dp(8));
        panel.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < MAX_ROWS; i++) {
            TextView t = text(c, 15);
            t.setPadding(dp(16), dp(9), dp(16), dp(9));
            if (glass.enabled()) {
                // The selected row is lit glass over the menu's glass (what it bends at its rim).
                Glass.Surface g = glass.surface(t, new Glass.Style().radius(18).bevel(9).refract(6)
                        .shine(0.8f).base(BEHIND, 0.07f).hideIdle());
                t.setBackground(g);
                rowGlass[i] = g;
            } else {
                GradientDrawable rb = new GradientDrawable();
                rb.setCornerRadius(dp(18));
                t.setBackground(rb);
                rowBgs[i] = rb;
            }
            rows[i] = t;
            panel.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // Centred: well inside the TV-safe area at any list length (MAX_ROWS caps the height).
        layer.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        layer.setAlpha(0f);
        layer.setVisibility(View.GONE);
        root.addView(layer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    boolean isOpen() { return open; }

    /** Shows the items (replacing any open list), first one selected. */
    void open(CharSequence title, List<Item> list) {
        items.clear();
        items.addAll(list);
        selected = 0;
        first = 0;
        header.setText(title);
        header.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        bind();
        open = true;
        layer.animate().cancel();
        layer.setVisibility(View.VISIBLE);
        layer.animate().alpha(1f).setDuration(FADE_MS).start();
    }

    void close() {
        if (!open) return;
        open = false;
        layer.animate().cancel();
        // A cancelled fade skips its end action, and open() cancels it: no stale hide.
        layer.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> layer.setVisibility(View.GONE)).start();
    }

    void move(int d) {
        if (items.isEmpty()) return;
        selected = Math.max(0, Math.min(items.size() - 1, selected + d));
        if (selected < first) first = selected;
        if (selected >= first + MAX_ROWS) first = selected - MAX_ROWS + 1;
        bind();
    }

    /** Closes the menu, then runs the selected item (which may open another list). */
    void choose() {
        if (!open || items.isEmpty()) return;
        Item it = items.get(selected);
        close();
        it.action.run();
    }

    private void bind() {
        for (int r = 0; r < MAX_ROWS; r++) {
            int i = first + r;
            TextView t = rows[r];
            if (i >= items.size()) {
                t.setVisibility(View.GONE);
                continue;
            }
            boolean sel = i == selected;
            t.setVisibility(View.VISIBLE);
            t.setText(items.get(i).text);
            t.setTextColor(sel ? SELECTED_FG : Color.WHITE);
            if (rowGlass[r] != null) rowGlass[r].lift(sel, 0);
            else rowBgs[r].setColor(sel ? SELECTED_BG : Color.TRANSPARENT);
        }
    }

    private TextView text(Context c, float sp) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(420));
        t.setFocusable(false);
        return t;
    }

    private int dp(float v) { return Math.round(v * density); }
}
