package dev.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Tile banners and the blurred background wash derived from them. Pure functions of their
 * inputs, safe to call off the main thread.
 */
final class Banners {
    private Banners() { }

    // The wash is blurred at this tiny size and upscaled by the GPU for free.
    private static final int WASH_W = 64, WASH_H = 36;

    /** Derives the blurred wash, then moves both bitmaps to GPU memory (off the Java heap). */
    static void bake(Entry e, Bitmap sw) {
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

    static Bitmap toGpu(Bitmap b) {
        Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
        if (hw == null) return b;
        b.recycle();
        return hw;
    }

    static Bitmap drawBanner(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF2A2A31);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    static Bitmap drawIcon(Drawable icon, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF2A2A31);
        int s = h * 6 / 10;
        icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
        icon.draw(c);
        return b;
    }

    /** Graphite tile with a white gear, in place of the stock gray Settings banner. */
    static Bitmap drawSettings(int w, int h) {
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
}
